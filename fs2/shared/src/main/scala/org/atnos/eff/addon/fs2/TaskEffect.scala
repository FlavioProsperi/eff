package org.atnos.eff.addon.fs2

import cats._
import cats.implicits._
import fs2._
import org.atnos.eff._
import org.atnos.eff.syntax.all._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.TimeoutException
import scala.util._

case class TimedTask[A](task: (Strategy, Scheduler) => Task[A], timeout: Option[FiniteDuration] = None) {
  def runNow(implicit strategy: Strategy, scheduler: Scheduler): Task[A] = timeout.fold(task(strategy, scheduler)) { t =>
    for {
      ref <- Task.ref[A]
      _ <- ref.set(
        (Task.fail(new TimeoutException).schedule(t): Task[A])
          .race(task(strategy, scheduler))
          .map(_.merge)
      )
      result <- ref.get
    } yield result
  }
}

object TimedTask {
  final def TimedTaskApplicative: Applicative[TimedTask] = new Applicative[TimedTask] {
    def pure[A](x: A) = TimedTask((_, _) => Task.now(x))

    def ap[A, B](ff: TimedTask[(A) => B])(fa: TimedTask[A]) =
      TimedTask((strategy, scheduler) =>
        for {
          fStarted <- Task.start(ff.runNow(strategy, scheduler))(strategy)
          aStarted <- Task.start(fa.runNow(strategy, scheduler))(strategy)
          fDone <- fStarted
          aDone <- aStarted
        } yield fDone(aDone)
      )
  }

  implicit final def TimedTaskMonad: Monad[TimedTask] = new Monad[TimedTask] {
    def pure[A](x: A) = TimedTask((_, _) => Task.now(x))

    def flatMap[A, B](fa: TimedTask[A])(f: (A) => TimedTask[B]) =
      TimedTask((strategy, scheduler) => fa.runNow(strategy, scheduler).flatMap(f(_).runNow(strategy, scheduler)))

    def tailRecM[A, B](a: A)(f: (A) => TimedTask[Either[A, B]]): TimedTask[B] =

      TimedTask[B]({ (strategy, scheduler) =>
        def loop(na: A): Task[B] = f(na).runNow(strategy, scheduler).flatMap(_.fold(loop, Task.now))
        loop(a)
      })
  }

  final def now[A](value: A): TimedTask[A] = TimedTask((_, _) => Task.now(value))

  implicit final def fromTask[A](task: Task[A]): TimedTask[A] =
    TimedTask((_, _) => task)

  final def fromTask[A](task: Task[A], timeout: Option[FiniteDuration] = None): TimedTask[A] =
    TimedTask((_, _) => task, timeout)

}

trait TaskTypes {
  type _task[R] = |=[TimedTask, R]
  type _Task[R] = <=[TimedTask, R]
}

trait TaskCreation extends TaskTypes {

  final def taskWithContext[R :_task, A](c: (Strategy, Scheduler) => Task[A],
                                         timeout: Option[FiniteDuration] = None): Eff[R, A] =
    TimedTask(c, timeout).send

  final def fromTask[R :_task, A](task: Task[A], timeout: Option[FiniteDuration] = None): Eff[R, A] =
    TimedTask((_, _) => task, timeout).send[R]

  final def taskFailed[R :_task, A](t: Throwable): Eff[R, A] =
    fromTask[R, A](Task.fail(t))

  final def taskSuspend[R :_task, A](tisk: => TimedTask[Eff[R, A]]): Eff[R, A] =
    TimedTask((strategy, scheduler) => Task.suspend(tisk.runNow(strategy, scheduler))).send.flatten

  final def taskDelay[R :_task, A](call: => A, timeout: Option[FiniteDuration] = None): Eff[R, A] =
    fromTask[R, A](Task.delay(call), timeout)

  final def taskForkStrategy[R :_task, A](call: TimedTask[A])(implicit strategy: Strategy): Eff[R, A] =
    TimedTask[A]((_, scheduler) => Task.start(call.runNow(strategy, scheduler)).flatMap(identity)).send

  final def taskFork[R :_task, A](call: TimedTask[A]): Eff[R, A] =
    TimedTask[A]((strategy, scheduler) => Task.start(call.runNow(strategy, scheduler))(strategy).flatMap(identity)).send[R]

  final def taskAsync[R :_task, A](callbackConsumer: ((Throwable Either A) => Unit) => Unit,
                                   timeout: Option[FiniteDuration] = None): Eff[R, A] =
    TimedTask[A]((strategy, _) => Task.async[A](callbackConsumer)(strategy), timeout).send[R]

  final def taskAsyncStrategy[R :_task, A](callbackConsumer: ((Throwable Either A) => Unit) => Unit,
                                           strategy: Strategy,
                                           timeout: Option[FiniteDuration] = None): Eff[R, A] =
    fromTask(Task.async[A](callbackConsumer)(strategy), timeout)

}

object TaskCreation extends TaskCreation

trait TaskInterpretation extends TaskTypes {

  def runAsync[A](e: Eff[Fx.fx1[TimedTask], A])(implicit strat: Strategy, sched: Scheduler): Task[A] =
    Eff.detachA(e)(TimedTask.TimedTaskMonad, TimedTask.TimedTaskApplicative).runNow(strat, sched)

  def runSequential[A](e: Eff[Fx.fx1[TimedTask], A])(implicit strat: Strategy, sched: Scheduler): Task[A] =
    Eff.detach(e).runNow(strat, sched)

  def attempt[A](task: TimedTask[A]): TimedTask[Throwable Either A] =
    TimedTask[Throwable Either A](task.runNow(_, _).attempt)

  import interpret.of

  def taskAttempt[R, A](e: Eff[R, A])(implicit task: TimedTask /= R): Eff[R, Throwable Either A] =
    interpret.interceptNatM[R, TimedTask, Throwable Either ?, A](e,
      new (TimedTask ~> (TimedTask of (Throwable Either ?))#l) {
        override def apply[X](fa: TimedTask[X]): TimedTask[Throwable Either X] = attempt(fa)
    })

  def memoize[A](key: AnyRef, cache: Cache, task: Task[A]): Task[A] =
    Task.suspend {
      cache.get[A](key).fold(task.map { r => cache.put(key, r); r })(Task.now)
    }

  /**
    * Memoize tasks using a cache
    *
    * if this method is called with the same key the previous value will be returned
    */
  def taskMemo[R, A](key: AnyRef, cache: Cache, e: Eff[R, A])(implicit task: TimedTask /= R): Eff[R, A] =
    interpret.interceptNat[R, TimedTask, A](e)(new (TimedTask ~> TimedTask) {
      override def apply[X](fa: TimedTask[X]): TimedTask[X] =
        fa.copy(task = (strat, sched) => memoize(key, cache, fa.task(strat, sched)))
    })

  /**
    * Memoize task values using a memoization effect
    *
    * if this method is called with the same key the previous value will be returned
    */
  def taskMemoized[R, A](key: AnyRef, e: Eff[R, A])(implicit task: TimedTask /= R, m: Memoized |= R): Eff[R, A] =
    MemoEffect.getCache[R].flatMap(cache => taskMemo(key, cache, e))

  def runTaskMemo[R, U, A](cache: Cache)(effect: Eff[R, A])(implicit m: Member.Aux[Memoized, R, U], task: TimedTask |= U): Eff[U, A] = {
    interpret.translate(effect)(new Translate[Memoized, U] {
      def apply[X](mx: Memoized[X]): Eff[U, X] =
        mx match {
          case Store(key, value) => TaskCreation.taskDelay(cache.memo(key, value()))
          case GetCache()        => TaskCreation.taskDelay(cache)
        }
    })
  }

}

object TaskInterpretation extends TaskInterpretation

trait TaskEffect extends TaskInterpretation with TaskCreation

object TaskEffect extends TaskEffect