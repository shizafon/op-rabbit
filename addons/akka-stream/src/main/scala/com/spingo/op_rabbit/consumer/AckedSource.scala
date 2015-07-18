package com.spingo.op_rabbit.consumer

import akka.actor._
import akka.pattern.pipe
import akka.stream.Graph
import akka.stream.Materializer
import akka.stream.SinkShape
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.{Source, Sink}
import com.spingo.op_rabbit.AckedSink
import com.spingo.op_rabbit.SameThreadExecutionContext
import com.thenewmotion.akka.rabbitmq.Channel
import org.reactivestreams.{Publisher, Subscriber}
import scala.annotation.tailrec
import scala.collection.mutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import shapeless._
import shapeless.ops.hlist.Tupler

private [op_rabbit] case class StreamException(e: Throwable)

private [op_rabbit] class LostPromiseWatcher[T]() {
  // the key is a strong reference to the upstream promise; the weak-key is the representative promise.
  private var watched = scala.collection.concurrent.TrieMap.empty[Promise[T], scala.ref.WeakReference[Promise[T]]]

  def apply(p: Promise[T])(implicit ec : ExecutionContext): Promise[T] = {
    val weakPromise = Promise[T]
    watched(p) = scala.ref.WeakReference(weakPromise)
    p.completeWith(weakPromise.future) // this will not cause p to have a strong reference to weakPromise
    p.future.onComplete { _ => watched.remove(p) }
    weakPromise
  }

  // polls for lost promises
  // If the downstream weak promise is unallocated, and the upstream
  // promise is not yet fulfilled, it's certain that the weak
  // promise cannot fulfill the upstream.
  def lostPromises: Seq[Promise[T]] = {
    val keys = for { (k,v) <- watched.toSeq if v.get.isEmpty } yield {
      watched.remove(k)
      k
    }

    keys.filterNot(_.isCompleted)
  }
}

class RabbitSourceActor[T](
  name: String,
  abort: Promise[Unit],
  consumerStopped: Future[Unit],
  initialQos: Int,
  MessageReceived: MessageExtractor[T] ) extends ActorPublisher[(Promise[Unit], T)] with ActorLogging {

  type Out = (Promise[Unit], T)
  import ActorPublisherMessage.{Cancel, Request}

  // State
  var stopping = false
  var presentQos = initialQos
  val queue = scala.collection.mutable.Queue.empty[Out]
  val promiseWatcher = new LostPromiseWatcher[Unit]

  protected case object PollLostPromises

  override def preStart: Unit = {
    implicit val ec = SameThreadExecutionContext
    consumerStopped.foreach { _ => self ! Status.Success }
    context.system.scheduler.schedule(15 seconds, 15 seconds, self, PollLostPromises)(context.dispatcher)
  }

  var subscriptionActor: Option[ActorRef] = None
  val bufferMax = initialQos / 2


  def receive = {
    case PollLostPromises =>
      val lost = promiseWatcher.lostPromises.foreach {
        _.failure(new Exception(s"Promise for stream consumer ${name} was garbage collected before it was fulfilled."))
      }

    case Request(demand) =>
      drain()
      if (stopping) tryStop()

    // A stream consumer detached
    case Cancel =>
      context stop self


    // sent by the StreamRabbitConsumer if there is a deserialization error or other issue
    case StreamException(ex) =>
      onError(ex)
      abort.success(())
      context stop self

    case MessageReceived(promise, msg) =>
      val watchedPromise = promiseWatcher(promise)(context.dispatcher)
      queue.enqueue((promise, msg))
      drain()
      limitQosOnOverflow()

    case Status.Success =>
      subscriptionActor = None
      stopping = true
      tryStop()
  }

  private def tryStop(): Unit =
    if (queue.length == 0)
      onCompleteThenStop()


  private def drain(): Unit =
    while ((totalDemand > 0) && (queue.length > 0)) {
      onNext(queue.dequeue())
    }

  private def limitQosOnOverflow(): Unit = {
    subscriptionActor.foreach { ref =>
      // TODO - think this through
      val desiredQos = if(queue.length > bufferMax) 1 else presentQos
      if (desiredQos == presentQos) subscriptionActor.foreach { ref =>
        ref ! Subscription.SetQos(desiredQos)
        presentQos = desiredQos
      }
    }
  }
}

trait MessageExtractor[Out] {
  def unapply(m: Any): Option[(Promise[Unit], Out)]
}


class AckedSource[+Out, +Mat](val wrappedRepr: Source[(Promise[Unit], Out), Mat]) extends AckedFlowOps[Out, Mat] {
  type UnwrappedRepr[+O, +M] = Source[O, M]
  type WrappedRepr[+O, +M] = Source[(Promise[Unit], O), M]
  type Repr[+O, +M] = AckedSource[O, M]

  /**
   * Connect this [[akka.stream.scaladsl.Source]] to a [[akka.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def runAckMat[Mat2](combine: (Mat, Future[Unit]) ⇒ Mat2)(implicit materializer: Materializer): Mat2 =
    wrappedRepr.toMat(AckedSink.ack.akkaSink)(combine).run

  def runAck(implicit materializer: Materializer) = runAckMat(Keep.right)

  def runWith[Mat2](sink: AckedSink[Out, Mat2])(implicit materializer: Materializer): Mat2 =
    wrappedRepr.runWith(sink.akkaSink)

  def runFold[U](zero: U)(f: (U, Out) ⇒ U)(implicit materializer: Materializer): Future[U] =
    runWith(AckedSink.fold(zero)(f))

  def runForeach(f: (Out) ⇒ Unit)(implicit materializer: Materializer): Future[Unit] =
    runWith(AckedSink.foreach(f))

  def to[Mat2](sink: AckedSink[Out, Mat2]): RunnableGraph[Mat] =
    wrappedRepr.to(sink.akkaSink)

  def toMat[Mat2, Mat3](sink: AckedSink[Out, Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableGraph[Mat3] =
    wrappedRepr.toMat(sink.akkaSink)(combine)

  protected def andThen[U, Mat2 >: Mat](next: WrappedRepr[(Promise[Unit], U), Mat2]): Repr[U, Mat2] = {
    new AckedSource(next)
  }
}

object AckedSource {
  type OUTPUT[T] = (Promise[Unit], T)

  def apply[T](iterable: scala.collection.immutable.Iterable[(Promise[Unit], T)]): AckedSource[T, Unit] = {
    new AckedSource(Source(iterable))
  }
  def consume[L <: HList](
    name: String,
    rabbitControl: ActorRef,
    channelDirective: ChannelDirective,
    binding: SubscriptionDirective,
    directive: Directive[L]
  )(implicit refFactory: ActorRefFactory, tupler: HListToValueOrTuple[L]) = {
    type Out = (Promise[Unit], tupler.Out)
    case class MessageReceived(promise: Promise[Unit], msg: tupler.Out)

    val messageReceivedExtractor = new MessageExtractor[tupler.Out] {
      type AcceptedType = MessageReceived
      def unapply(m: Any) = m match {
        case d: MessageReceived =>
          Some((d.promise, d.msg))
        case _ =>
          None
      }
    }

    val abort = Promise[Unit]
    val consumerStopped = Promise[Unit]
    val leActor: ActorRef = refFactory.actorOf(Props(new RabbitSourceActor(name, abort, consumerStopped.future, channelDirective.config.qos, messageReceivedExtractor )))

    def interceptingRecoveryStrategy = new RecoveryStrategy {
      def apply(ex: Throwable, channel: Channel, queueName: String, delivery: Delivery): Future[Boolean] = {
        val downstream = binding.recoveryStrategy(ex, channel, queueName, delivery)
        // if recovery strategy fails, then yield the exception through the stream
        downstream.onFailure({ case ex => leActor ! StreamException(ex) })(SameThreadExecutionContext)
        downstream
      }
    }

    val streamConsumerDirective = binding.copy(recoveryStrategy = interceptingRecoveryStrategy)
    val subscription = new Subscription {
      def config = {
        channelDirective {
          streamConsumerDirective.copy(executionContext = SameThreadExecutionContext) {
            directive.happly { l =>
              val p = Promise[Unit]

              leActor ! MessageReceived(p, tupler(l))

              ack(p.future)
            }
          }
        }
      }
    }

    rabbitControl ! subscription

    implicit val ec = SameThreadExecutionContext
    abort.future.foreach { _ => subscription.abort() }
    consumerStopped.completeWith(subscription.closed)
    new AckedSource(Source(ActorPublisher[Out](leActor)).mapMaterializedValue(_ => subscription))
  }
}