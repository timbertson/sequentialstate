package net.gfxmonk.capsul

import scala.util._
import scala.concurrent._
import scala.concurrent.duration._

/**
A staged future represents both asynchronous acceptance and asynchronous
fulfilment of a piece of work.

By explicitly representing asynchronous acceptance, consumers can implement
backpressure without resorting to synchronous blocking.

It's conceptually just a `Future[Future[T]]`, where the outer future is
resolved once the receiver has accepted the work, and the inner future
is resolved once the work is actually complete. For convenience, it also
implements [[Future]][T] so you can wait for the final result easily when
necessary.

The only additional methods on top of [[Future]] are:

 - [[accepted]]
 - [[isAccepted]]
 - [[onAccept]]

*/
trait StagedFuture[T] extends Future[T] {
	def accepted: Future[Future[T]]
	def isAccepted: Boolean
	def onAccept[U](fn: Future[T] => U)(implicit ex: ExecutionContext): Unit
}

object StagedFuture {
	def successful[A](value: A): StagedFuture[A] = new Accepted(Future.successful(value)) // could be more specialised, but used rarely
	def accepted[A](future: Future[A]): StagedFuture[A] = new Accepted(future)
	def apply[A](future: Future[Future[A]]): StagedFuture[A] = new Wrapped(future)
	def apply[T](body: => T)(implicit ec: ExecutionContext): StagedFuture[T] = {
		val accepted = Promise[Future[T]]()
		Future {
			val inner = Promise[T]()
			accepted.success(inner.future)
			inner.complete(Try(body))
		}
		new Wrapped(accepted.future)
	}

	private class Accepted[T](f: Future[T]) extends StagedFuture[T] {
		final def accepted: Future[Future[T]] = Future.successful(f)
		final def isAccepted: Boolean = true
		final def onAccept[U](fn: Future[T] => U)(implicit ex: ExecutionContext): Unit = fn(f)

		// scala.concurrent.Awaitable
		final def ready(atMost: Duration)(implicit permit: scala.concurrent.CanAwait): this.type = {
			f.ready(atMost)(permit)
			this
		}
		final def result(atMost: Duration)(implicit permit: CanAwait): T = f.result(atMost)(permit)

		// scala.concurrent.Future
		final def isCompleted: Boolean = f.isCompleted
		final def onComplete[U](fn: Try[T] => U)(implicit executor: ExecutionContext): Unit = f.onComplete(fn)
		final def transform[S](fn: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] = f.transform(fn)
		final def transformWith[S](fn: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] = f.transformWith(fn)
		final def value: Option[Try[T]] = f.value
	}

	private class Wrapped[T](f: Future[Future[T]]) extends StagedFuture[T] {
		// StagedFuture extra interface
		final def accepted: Future[Future[T]] = f
		final def isAccepted: Boolean = f.isCompleted
		final def onAccept[U](fn: Future[T] => U)(implicit ex: ExecutionContext): Unit = {
			f.onComplete {
				case Success(f) => fn(f)
				case Failure(e) => fn(Future.failed(e)) // extremely uncommon
			}
		}

		// scala.concurrent.Awaitable
		def ready(atMost: Duration)(implicit permit: scala.concurrent.CanAwait): this.type = {
			f.ready(atMost)(permit)
			f.value match {
				case None => this
				case Some(Success(inner)) => {
					// XXX need to subtract the already-waited time from `atMost`
					inner.ready(atMost)(permit)
					this
				}
				case Some(Failure(_)) => this
			}
		}

		def result(atMost: Duration)(implicit permit: CanAwait): T = {
			f.ready(atMost)(permit)
			// XXX need to subtract the already-waited time from `atMost`
			f.value.get.get.result(atMost)(permit)
		}

		// scala.concurrent.Future
		def isCompleted: Boolean = f.value match {
			case None => false
			case Some(Success(inner)) => inner.isCompleted
			case Some(Failure(_)) => true
		}

		def onComplete[U](fn: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
			f.onComplete {
				case Success(inner) => inner.onComplete(fn)
				case Failure(e) => fn(Failure(e))
			}
		}

		def transform[S](fn: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] = {
			f.flatMap(inner => inner.transform(fn))
		}

		def transformWith[S](fn: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] = {
			f.flatMap(inner => inner.transformWith(fn))
		}

		def value: Option[Try[T]] = f.value match {
			case None => None
			case Some(Success(next)) => next.value
			case Some(Failure(e)) => Some(Failure(e))
		}
	}

	private class DiscardingFuture[T](f: Future[T]) extends Future[Unit] {
		def ready(atMost: Duration)(implicit permit: scala.concurrent.CanAwait): this.type = {
			f.ready(atMost)(permit)
			this
		}

		def result(atMost: Duration)(implicit permit: scala.concurrent.CanAwait): Unit = {
			f.ready(atMost)(permit)
			()
		}

		// Members declared in scala.concurrent.Future
		def isCompleted: Boolean = f.isCompleted
		def onComplete[U](fn: Try[Unit] => U)(implicit executor: ExecutionContext): Unit = {
			f.onComplete(result => fn(discardTry(result)))
		}

		private def discardTry[U](t: Try[U]) : Try[Unit] = t match {
			case Success(_) => Success(())
			case Failure(e) => Failure(e)
		}

		def transform[S](fn: Try[Unit] => Try[S])(implicit executor: ExecutionContext): Future[S] = {
			f.transform(x => fn(discardTry(x)))
		}

		def transformWith[S](fn: Try[Unit] => Future[S])(implicit executor: ExecutionContext): Future[S] = {
			f.transformWith(x => fn(discardTry(x)))
		}

		def value: Option[Try[Unit]] = f.value.map(discardTry)
	}
}
