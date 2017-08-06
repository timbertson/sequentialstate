package net.gfxmonk.sequentialstate.examples.chain
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import monix.eval.TaskSemaphore
import monix.execution.Scheduler.Implicits.global
import net.gfxmonk.sequentialstate.examples.FutureUtils
import net.gfxmonk.sequentialstate._
import net.gfxmonk.sequentialstate.staged._

import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._

// Example of a chaining together multiple sequential states, maintaining backpressure

object StateBased {

	class Prefixer(next: Option[Prefixer]) {
		private val state = SequentialState("")
		def initPrefix(prefix: String): Future[Unit] = state.sendSet(prefix)

		def prefix(value: String): Future[Future[String]] = {
			// rawAccessAsync will delay `enqueue` until the outer
			// future is complete. Which is the `enqueue` of the following actor.
			//
			// The result is that if the final actor in the chain is at capacity,
			// intermediate buffers will fill but not overflow, since they
			// propagate the downstream state's acceptance.
			state.rawAccessAsync(prefix => {
				val prefixed = prefix + value
				next match {
					case Some(prefixer) => prefixer.prefix(prefixed)
					case None => StagedFuture.successful(prefixed)
				}
			})
		}

		def prefixAsync(value: String): Future[Future[String]] = {
			state.rawAccessAsync(prefix => {
				val prefixed = Future {
					//inexplicably async, for demonstration purposes
					Thread.sleep(1000)
					prefix + value
				}

				next match {
					case Some(prefixer) => prefixed.flatMap(prefixed => prefixer.prefixAsync(prefixed))
					case None => Future.successful(prefixed)
				}
			})
		}
	}

	def applyMultiplePrefixes(value: String): Future[String] = {
		val a = new Prefixer(None)
		val b = new Prefixer(Some(a))
		val c = new Prefixer(Some(b))

		for {
			() <- a.initPrefix("a ")
			() <- b.initPrefix("then b ")
			() <- c.initPrefix("then c:")
			result <- c.prefix(value).resolved
		} yield result
	}

	def applyMultiplePrefixesAsync(value: String): Future[String] = {
		val a = new Prefixer(None)
		val b = new Prefixer(Some(a))
		val c = new Prefixer(Some(b))

		for {
			() <- a.initPrefix("a ")
			() <- b.initPrefix("then b ")
			() <- c.initPrefix("then c:")
			result <- c.prefixAsync(value).resolved
		} yield result
	}
}

object ExampleMain {
	def main() {
		println("Multi-prefix: " + Await.result(StateBased.applyMultiplePrefixes("value"), Duration.Inf))
		println("Multi-prefix (async): " + Await.result(StateBased.applyMultiplePrefixesAsync("value"), Duration.Inf))
	}
}