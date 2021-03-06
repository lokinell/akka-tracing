/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.levkhomich.akka.tracing.play

import scala.concurrent.{ Await, Future }
import scala.util.Random

import play.api.{ GlobalSettings, Play }
import play.api.http.Writeable
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.api.test._

import com.github.levkhomich.akka.tracing._
import com.github.levkhomich.akka.tracing.http.TracingHeaders

class PlayTracingSpec extends PlaySpecification with TracingTestCommons with MockCollector with Results {

  sequential

  val TestPath = "/request"
  val TestErrorPath = "/error"
  val npe = new NullPointerException
  implicit def trace: TracingExtensionImpl = TracingExtension(_root_.play.libs.Akka.system)

  def fakeApplication: FakeApplication = FakeApplication(
    withRoutes = {
      case ("GET", TestPath) =>
        Action {
          Ok("response") as "text/plain"
        }
      case ("GET", TestErrorPath) =>
        Action {
          throw npe
          Ok("response") as "text/plain"
        }
    },
    withGlobal = Some(new GlobalSettings with TracingSettings),
    additionalConfiguration = Map(
      TracingExtension.AkkaTracingPort -> collectorPort
    )
  )

  "Play tracing" should {
    "sample requests" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      success
    }

    "not allow to use RequestHeaders as child of other request" in new WithApplication(fakeApplication) {
      val parent = new TracingSupport {}
      val request = FakeRequest("GET", TestPath)
      new PlayControllerTracing {
        request.asChildOf(parent)
      } must throwA[IllegalStateException]
    }

    "annotate sampled requests (general)" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.path", TestPath)
      checkBinaryAnnotation(span, "request.method", "GET")
      checkBinaryAnnotation(span, "request.secure", false)
      checkBinaryAnnotation(span, "request.proto", "HTTP/1.1")
    }

    "annotate sampled requests (query params, headers)" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq("Content-Type" -> Seq("text/plain"))), AnyContentAsEmpty
      )).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers.Content-Type", "text/plain")
      checkBinaryAnnotation(span, "request.query.key", "value")
    }

    "propagate tracing headers" in new WithApplication(fakeApplication) {
      val spanId = Random.nextLong
      val parentId = Random.nextLong

      val result = route(FakeRequest("GET", TestPath + "?key=value",
        FakeHeaders(Seq(
          TracingHeaders.TraceId -> Seq(Span.asString(spanId)),
          TracingHeaders.ParentSpanId -> Seq(Span.asString(parentId))
        )), AnyContentAsEmpty
      )).map(Await.result(_, defaultAwaitTimeout.duration))

      val span = receiveSpan()
      checkBinaryAnnotation(span, "request.headers." + TracingHeaders.TraceId, Span.asString(spanId))
      checkBinaryAnnotation(span, "request.headers." + TracingHeaders.ParentSpanId, Span.asString(parentId))
    }

    "record server errors to traces" in new WithApplication(fakeApplication) {
      val result = route(FakeRequest("GET", TestErrorPath)).map(Await.result(_, defaultAwaitTimeout.duration))
      val span = receiveSpan()
      checkAnnotation(span, TracingExtension.getStackTrace(npe))
    }

  }

  step {
    collector.stop()
  }

  // it seems that play-test doesn't call global.onRequestCompletion and global.onError
  override def call[T](action: EssentialAction, rh: RequestHeader, body: T)(implicit w: Writeable[T]): Future[Result] = {
    val rhWithCt = w.contentType.map(ct => rh.copy(
      headers = FakeHeaders((rh.headers.toMap + ("Content-Type" -> Seq(ct))).toSeq)
    )).getOrElse(rh)
    val requestBody = Enumerator(body) &> w.toEnumeratee
    val result = requestBody |>>> action(rhWithCt).recover {
      case e =>
        Play.current.global.onError(rh, e)
        InternalServerError
    }
    result.onComplete {
      case _ =>
        Play.current.global.onRequestCompletion(rh)
    }
    result
  }

}