package net.gfxmonk.sequentialstate

import monix.execution.misc.NonFatal

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try,Success,Failure}

trait EnqueueableTask {
	// Simplification for the scheduler, which doesn't
	// care about the type parameter in UnitOfWork
	def enqueuedAsync(): Unit
	def run(): Option[Future[_]]
}

trait UnitOfWork[A] extends EnqueueableTask {
	protected val fn: Function0[A]

	def enqueuedAsync(): Unit
	protected def reportSuccess(result: A): Option[Future[_]]
	protected def reportFailure(error: Throwable): Option[Future[_]]

	final def run(): Option[Future[_]] = {
		try {
			reportSuccess(fn())
		} catch {
			case e:Throwable => {
				if (NonFatal(e)) {
					reportFailure(e)
				} else {
					throw e
				}
			}
		}
	}
}

object UnitOfWork {
	trait HasExecutionContext {
		protected val ec: ExecutionContext
	}

	trait IgnoresResult[A] {
		final def reportSuccess(result: A): Option[Future[_]] = None
	}

	trait HasEnqueuePromise[A] {
		var enqueuedPromise = Promise[A]()
	}

	trait HasResultPromise[A] {
		val resultPromise: Promise[A] = Promise[A]()
		final def reportFailure(error: Throwable): Option[Future[_]] = {
			resultPromise.failure(error)
			None
		}
	}

	trait HasSyncResult[A] { self: HasResultPromise[A] =>
		final def reportSuccess(result: A): Option[Future[_]] = {
			resultPromise.success(result)
			None
		}
	}

	trait HasEnqueueAndResultPromise[A] { self: HasEnqueuePromise[Future[A]] with HasResultPromise[A] =>
		final def enqueuedAsync() {
			enqueuedPromise.success(resultPromise.future)
		}
	}

	case class Full[A](fn: Function0[A])
		extends UnitOfWork[A]
		with HasEnqueuePromise[Future[A]]
		with HasResultPromise[A]
		with HasSyncResult[A]
		with HasEnqueueAndResultPromise[A]
	{
	}

	trait HasStagedResult[A] { self: UnitOfWork[StagedFuture[A]] with HasExecutionContext with HasResultPromise[A] =>
		final def reportSuccess(result: StagedFuture[A]): Option[Future[_]] = {
			result.onComplete(resultPromise.complete)(ec)
			Some(result.accepted)
		}
	}

	trait HasFutureResult[A] { self: UnitOfWork[Future[A]] with HasExecutionContext with HasResultPromise[A] =>
		final def reportSuccess(result: Future[A]): Option[Future[_]] = {
			result.onComplete(resultPromise.complete)(ec)
			Some(result)
		}
	}

	case class FullStaged[A](fn: Function0[StagedFuture[A]])(implicit protected val ec: ExecutionContext)
		extends UnitOfWork[StagedFuture[A]]
			with HasExecutionContext
			with HasEnqueuePromise[Future[A]]
			with HasResultPromise[A]
			with HasEnqueueAndResultPromise[A]
			with HasStagedResult[A]
	{
	}

	case class FullAsync[A](fn: Function0[Future[A]])(implicit protected val ec: ExecutionContext)
		extends UnitOfWork[Future[A]]
			with HasExecutionContext
			with HasEnqueuePromise[Future[A]]
			with HasResultPromise[A]
			with HasEnqueueAndResultPromise[A]
			with HasFutureResult[A]
	{
	}

	trait IsEnqueueOnly { self: HasEnqueuePromise[Unit] =>
		final def enqueuedAsync() {
			enqueuedPromise.success(())
		}

		final def reportFailure(error: Throwable): Option[Future[_]] = {
			None
		}
	}

	case class EnqueueOnly[A](fn: Function0[A])
		extends UnitOfWork[A]
		with HasEnqueuePromise[Unit]
		with IsEnqueueOnly
		with IgnoresResult[A]
	{
	}

	case class EnqueueOnlyStaged[A](fn: Function0[StagedFuture[A]])(implicit ec: ExecutionContext)
		extends UnitOfWork[StagedFuture[A]]
		with HasEnqueuePromise[Unit]
		with IsEnqueueOnly
	{
		final def reportSuccess(send: StagedFuture[A]): Option[Future[_]] = {
			Some(send.accepted)
		}
	}

	case class EnqueueOnlyAsync[A](fn: Function0[Future[A]])
		extends UnitOfWork[Future[A]]
		with HasEnqueuePromise[Unit]
		with IsEnqueueOnly
	{
		final def reportSuccess(f: Future[A]): Option[Future[_]] = {
			Some(f)
		}
	}
}
