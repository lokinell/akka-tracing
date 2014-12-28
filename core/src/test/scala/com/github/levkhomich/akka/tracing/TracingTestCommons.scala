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

import scala.concurrent.TimeoutException
import scala.concurrent.duration.{ FiniteDuration, SECONDS }
import scala.util.Random

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

final case class TestMessage(value: String) extends TracingSupport

trait TracingTestCommons {

  def nextRandomMessage: TestMessage =
    TestMessage(Random.nextLong.toString)

  def testActorSystem(sampleRate: Int = 1): ActorSystem =
    ActorSystem("AkkaTracingTestSystem" + sampleRate,
      ConfigFactory.parseMap(scala.collection.JavaConversions.mapAsJavaMap(
        Map(
          TracingExtension.AkkaTracingSampleRate -> sampleRate,
          TracingExtension.AkkaTracingPort -> (this match {
            case mc: MockCollector =>
              mc.collectorPort
            case _ =>
              9410
          })
        )
      ))
    )

  def generateTraces(count: Int, trace: TracingExtensionImpl): Unit = {
    println(s"sending $count messages")
    for (_ <- 1 to count) {
      val msg = nextRandomMessage
      trace.sample(msg, "test")
      trace.finish(msg)
    }
  }

}

trait TracingTestActorSystem { this: TracingTestCommons with Specification =>

  val sampleRate = 1

  implicit lazy val system = testActorSystem(sampleRate)
  implicit lazy val trace = TracingExtension(system)

  def shutdown(): Unit = {
    system.shutdown()
    this match {
      case mc: MockCollector =>
        mc.collector.stop()
      case _ =>
    }
    system.awaitTermination(FiniteDuration(5, SECONDS)) must not(throwA[TimeoutException])
  }
}