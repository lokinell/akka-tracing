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

package com.github.levkhomich.akka.tracing

import scala.util.Random

import org.specs2.mutable.Specification

class SpanSpec extends Specification {

  sequential

  "Span" should {

    val IterationsCount = 1000000

    "provide id serialization conforming to Finagle's implementation" in {
      def checkValue(x: Long): Unit = {
        val actual = Span.asString(x)
        val expected = new com.twitter.finagle.tracing.SpanId(x).toString()
        if (actual != expected)
          failure(s"SpanId serialization failed for value $x (was $actual instead of $expected)")
      }

      checkValue(Long.MaxValue)
      checkValue(Long.MinValue)
      checkValue(0)
      checkValue(10)
      checkValue(100)
      checkValue(100000)
      checkValue(-10)
      checkValue(-100)
      checkValue(-100000)

      (1 to IterationsCount).foreach(_ => checkValue(Random.nextLong()))

      success
    }

    "provide id deserialization conforming to Finagle's implementation" in {
      def checkValue(x: String): Unit = {
        val actual = Span.fromString(x)
        val expected = com.twitter.finagle.tracing.SpanId.fromString(x).get.toLong
        if (actual != expected)
          failure(s"SpanId deserialization failed for value $x (was $actual instead of $expected)")
      }

      checkValue("FFFFFFFFFFFFFFFF")
      checkValue("0")
      checkValue("00")
      checkValue("0000000000000000")
      checkValue("1")
      checkValue("11")
      checkValue("111")

      for (_ <- 1L to IterationsCount)
        checkValue {
          val str = Random.nextLong().toString.replace("-", "")
          str.substring(0, (Random.nextInt(15) + 1) min str.length)
        }

      success
    }

    "handle invalid input" in {
      Span.fromString(null) must throwAn[NumberFormatException]
      Span.fromString("") must throwAn[NumberFormatException]
      Span.fromString("not a number") must throwAn[NumberFormatException]
      Span.fromString("11111111111111111") must throwAn[NumberFormatException]
      Span.fromString("11111111111111111") must throwAn[NumberFormatException]
    }

    "provide span id data holder" in {
      val traceId = Random.nextLong
      val span = Span(traceId, Random.nextLong,
        if (Random.nextLong > 0) Some(Random.nextLong) else None, Random.nextBoolean)
      span.asChildOf(new TracingSupport {})(null) must throwA[UnsupportedOperationException]
      span.spanName must throwA[UnsupportedOperationException]
    }
  }

}
