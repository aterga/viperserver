// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.server.core

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import viper.server.vsi.{JobNotFoundException, VerJobId}
import viper.silver.reporter.{EntityFailureMessage, Message}
import viper.silver.verifier.{AbstractError, VerificationResult, Failure => VerificationFailure, Success => VerificationSuccess}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

object ViperCoreServerUtils {
  implicit private val executionContext: ExecutionContextExecutor = ExecutionContext.global

  private object SeqActor {
    case object Result
    def props(): Props = Props(new SeqActor())
  }

  class SeqActor() extends Actor {

    var messages: List[Message] = List()

    override def receive: PartialFunction[Any, Unit] = {
      case m: Message =>
        messages = messages :+ m
      case SeqActor.Result =>
        sender() ! messages
    }
  }

  /** Get a Future containing all messages generated by the backend.
    *
    * This is a utility function and not part of ViperCoreServer. Therefore, an instance of ViperCoreServer as well as
    * an instance of an actor system must be provided.
    *
    * Deletes the jobHandle on completion.
    */
  def getMessagesFuture(core: ViperCoreServer, jid: VerJobId)(implicit actor_system: ActorSystem): Future[List[Message]] = {
    import scala.language.postfixOps

    val actor = actor_system.actorOf(SeqActor.props())
    val complete_future = core.streamMessages(jid, actor).getOrElse(return Future.failed(JobNotFoundException))
    val res: Future[List[Message]] = complete_future.flatMap(_ => {
      implicit val askTimeout: Timeout = Timeout(core.config.actorCommunicationTimeout() milliseconds)
      (actor ? SeqActor.Result).mapTo[List[Message]]
    })
    res
  }

  /** Get a Future containing only verification results.
    *
    * This is a utility function and not part of ViperCoreServer. Therefore, an instance of ViperCoreServer as well as
    * an instance of an actor system must be provided.
    *
    * Deletes the jobHandle on completion.
    */
  def getResultsFuture(core: ViperCoreServer, jid: VerJobId)(implicit actor_system: ActorSystem): Future[VerificationResult] = {
    val messages_future = getMessagesFuture(core, jid)
    val result_future: Future[VerificationResult] = messages_future.map(msgs => {

      val abstract_errors: Seq[AbstractError] = msgs.foldLeft(Seq(): Seq[AbstractError]) {(errors, msg) =>
        msg match {
          case EntityFailureMessage(_, _, _, VerificationFailure(errs), _) => errs ++ errors
          case _ => errors
        }
      }
      abstract_errors match {
        case Seq() => VerificationSuccess
        case errors => VerificationFailure(errors)
      }
    })
    result_future
  }
}