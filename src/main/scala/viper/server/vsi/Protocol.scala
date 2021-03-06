// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2020 ETH Zurich.

package viper.server.vsi

import akka.stream.scaladsl.SourceQueueWithComplete
import org.reactivestreams.Publisher
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

// Protocol to communicate with QueueActor
object TaskProtocol {
  case class BackendReport(msg: Envelope)
  case class FinalBackendReport(success: Boolean)
}

object VerificationProtocol {

  sealed trait StartProcessRequest[T] {
    val task: TaskThread[T]
    val queue: SourceQueueWithComplete[Envelope]
    val publisher: Publisher[Envelope]
  }

  // Request Job Actor to execute an AST construction task
  case class ConstructAst[T](task: TaskThread[T],
                             queue: SourceQueueWithComplete[Envelope],
                             publisher: Publisher[Envelope]) extends StartProcessRequest[T]

  // Request Job Actor to execute a verification task
  case class Verify[T](task: TaskThread[T],
                       queue: SourceQueueWithComplete[Envelope],
                       publisher: Publisher[Envelope],
                       prev_job_id: Option[AstJobId]) extends StartProcessRequest[T]

  sealed trait StopProcessRequest

  // Request Job Actor to stop its verification task
  case object StopAstConstruction extends StopProcessRequest

  // Request Job Actor to stop its verification task
  case object StopVerification extends StopProcessRequest
}


object Requests extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport with DefaultJsonProtocol {

  case class VerificationRequest(arg: String)
  implicit val VerificationRequest_format: RootJsonFormat[VerificationRequest] = jsonFormat1(VerificationRequest.apply)

  case class CacheResetRequest(backend: String, file: String)
  implicit val CacheResetRequest_format: RootJsonFormat[CacheResetRequest] = jsonFormat2(CacheResetRequest.apply)
}