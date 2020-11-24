// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.server.vsi

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


class VerificationServerException extends Exception
case class JobNotFoundException() extends VerificationServerException

/** This trait provides state and process management functionality for verification servers.
  *
  * The server runs on Akka's actor system. This means that the entire server's state
  * and process management are run by actors. The 3 actors in charge are:
  *
  *   1) Job Actor
  *   2) Queue Actor
  *   3) Terminator Actor
  *
  *  The first two actors manage individual verification processes. I.e., on
  *  initializeVerificationProcess() and instance of each actor is created. The JobActor launches
  *  the actual VerificationTask, while the QueueActor acts as a middleman for communication
  *  between a VerificationTask's backend and the server. The Terminator Actor is in charge of
  *  terminating both individual processes and the server.
  */
trait VerificationServer extends Post {

  type AST

  implicit val system: ActorSystem = ActorSystem("Main")
  implicit val executionContext: ExecutionContextExecutor = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  protected var _termActor: ActorRef = _

  implicit val jid_fact: Int => VerJobId = VerJobId.apply
  protected var jobs: JobPool[VerJobId, VerHandle] = _
  var isRunning: Boolean = false

  /** Configures an instance of VerificationServer.
    *
    * This function must be called before any other. Calling any other function before this one
    * will result in an IllegalStateException.
    * */
  def start(active_jobs: Int): Unit = {
    jobs = new JobPool(active_jobs)
    _termActor = system.actorOf(Terminator.props(jobs), "terminator")
    isRunning = true
  }

  /** This method starts a verification process.
    *
    * As such, it accepts an instance of a VerificationTask, which it will pass to the JobActor.
    */
  protected def initializeVerificationProcess(task: MessageStreamingTask[AST]): VerJobId = {
    if(!isRunning) {
      throw new IllegalStateException("Instance of VerificationServer already stopped")
    }

    if (jobs.newJobsAllowed) {
      def createJob(new_jid: VerJobId): Future[VerHandle] = {

        implicit val askTimeout: Timeout = Timeout(5000 milliseconds)
        val job_actor = system.actorOf(JobActor.props(new_jid), s"job_actor_$new_jid")
        val (queue, publisher) = Source.queue[Envelope](10000, OverflowStrategy.backpressure)
                                       .toMat(Sink.asPublisher(false))(Keep.both)
                                       .run()
        val message_actor = system.actorOf(QueueActor.props(new_jid, queue), s"queue_actor_$new_jid")
        task.setQueueActor(message_actor)
        val task_with_actor = new Thread(task)
        val answer = job_actor ? VerificationProtocol.Verify(task_with_actor, queue, publisher)
        val new_job_handle: Future[VerHandle] = answer.mapTo[VerHandle]
        new_job_handle
      }
      val id = jobs.bookNewJob(createJob)
      id
    } else {
      VerJobId(-1) // Process Management running  at max capacity.
    }
  }

  /** Stream all messages generated by the backend to some actor.
    *
    * Deletes the JobHandle on completion.
    */
  protected def streamMessages(jid: VerJobId, clientActor: ActorRef): Option[Future[Unit]] = {
    if(!isRunning) {
      throw new IllegalStateException("Instance of VerificationServer already stopped")
    }

    jobs.lookupJob(jid) match {
      case Some(handle_future) =>
        def mapHandle(handle: VerHandle): Future[Unit] = {
          val src_envelope: Source[Envelope, NotUsed] = Source.fromPublisher((handle.publisher))
          val src_msg: Source[A , NotUsed] = src_envelope.map(e => unpack(e))
          src_msg.runWith(Sink.actorRef(clientActor, Success))
          _termActor ! Terminator.WatchJobQueue(jid, handle)
          handle.queue.watchCompletion().map(_ => ())
        }
        Some(handle_future.flatMap(mapHandle))
      case None => None
    }
  }

  /** Stops an instance of VerificationServer from running.
    *
    * As such it should be the last method called. Calling any other function after stop will
    * result in an IllegalStateException.
    * */
  def stop(): Unit = {
    if(!isRunning) {
      throw new IllegalStateException("Instance of VerificationServer already stopped")
    }
    isRunning = false

    getInterruptFutureList() onComplete {
      case Success(_) =>
        _termActor ! Terminator.Exit
        println(s"shutting down...")
      case Failure(err_msg) =>
        _termActor ! Terminator.Exit
        println(s"forcibly shutting down...")
    }
  }

  /** This method interrupts active jobs upon termination of the server.
    */
  protected def getInterruptFutureList(): Future[List[String]] = {
    val interrupt_future_list: List[Future[String]] = jobs.jobHandles map { case (jid, handle_future) =>
      handle_future.flatMap {
        case VerHandle(actor, _, _) =>
          implicit val askTimeout: Timeout = Timeout(1000 milliseconds)
          (actor ? VerificationProtocol.Stop).mapTo[String]
      }
    } toList
    val overall_interrupt_future: Future[List[String]] = Future.sequence(interrupt_future_list)
    overall_interrupt_future
  }
}