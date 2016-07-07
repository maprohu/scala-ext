package maprohu.scalaext.common

import scala.collection.immutable._
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Created by pappmar on 29/06/2016.
  */
object Stateful {

  def apply[S](state: S) = new Stateful(state)

  def seq[T] = new StatefulSeq[T]

  def shutdown = new StatefulShutdown

  def map[K, V](factory: (StatefulMap[K, V], K) => V) = new StatefulMap(factory)

  def futures[T] = new StatefulFutures[T]

  def cancels(implicit executionContext: ExecutionContext) = new StatefulCancels

}

class Stateful[S](private var state: S) {

  def transform[T](fn: S => (T, S)) : T = synchronized {
    val (out, newState) = fn(state)
    state = newState
    out
  }

  def process[T](fn: S => T) : T = synchronized {
    fn(state)
  }

  def extract = process(s => s)

  def update(fn: S => Option[S]) : Unit = synchronized {
    fn(state).foreach( state = _ )
  }

}

class StatefulSeq[T] extends Stateful[Seq[T]](Seq()) {

  def add(item: T) = {
    transform(seq => ((), seq :+ item))
  }

  def remove(item: T) = {
    transform({ seq =>
      ((), seq diff Seq(item))
    })
  }

}

class StatefulMap[K, V](factory: (StatefulMap[K, V], K) => V) extends Stateful[Map[K, V]](Map()) {

  def get(key: K) = transform { map =>
    map
      .get(key)
      .map(value => (value, map))
      .getOrElse {
        val value = factory(this, key)

        (value, map.updated(key, value))
      }
  }

  def remove(key: K) = transform { map =>
    (map.contains(key), map - key)
  }

}

object StatefulShutdown {

  type Listener = () => Future[Unit]

  sealed trait State
  case class Running(
    listeners: Seq[Listener] = Seq()
  ) extends State
  case class Stopped(
    done: Future[Boolean]
  ) extends State

}

class StatefulShutdown {
  import StatefulShutdown._

  private val stateful = Stateful[State](Running())

  def register(listener: Listener) : Boolean =
    stateful.transform {
      case state @ Running(listeners) =>
        (true, state.copy(listeners :+ listener))
      case state =>
        (false, state)
    }

  def shutdown(implicit
    executionContext: ExecutionContext
  ) : Future[Boolean] =
    stateful.transform {
      case state @ Running(listeners) =>
        val future =
          Future.sequence(
            listeners.map(l => l())
          )
            .map(_ => true)
            .recover({ case _ => false})

        (future, Stopped(future))
      case state @ Stopped(done)=>
        (done, state)
    }


}

class StatefulFutures[T] {

  val futures = Stateful.seq[Future[T]]

  def add(future: Future[T])(implicit executionContext: ExecutionContext) = {
    future
      .onComplete(_ => futures.remove(future))

    futures.add(future)
  }

  def future(implicit executionContext: ExecutionContext) =
    Future.sequence(
      futures.extract
    )

}

class StatefulCancels(implicit
  executionContext: ExecutionContext
) {

  private case class State(
    cancelled: Boolean = false,
    cancels: Seq[Cancel] = Seq()
  )

  private val promise = Promise[Any]()

  private val state = Stateful(State())

  val cancel = Cancel(
    cancel = () => state.update({ s =>
      if (!s.cancelled) {
        val fut =
          Future.sequence(
            s.cancels.map(_.done)
          )

        promise.completeWith(
          fut
        )
        s.cancels.foreach(_.cancel())
        Some(s.copy(cancelled = true))
      } else {
        None
      }
    }),
    promise.future
  )

  def add(cf: () => Cancel) : Option[Cancel] = {
    state.transform({ s =>
      if (s.cancelled) {
        (None, s)
      } else {
        val c = cf()
        c.done.onComplete({ _ =>
          state.transform({ s =>
            ((), s.copy(cancels = s.cancels diff Seq(c)))
          })
        })
        (Some(c), s.copy(cancels = s.cancels :+ c))
      }
    })

  }

  def addValue[T](cf: () => (Cancel, T)) : Option[T] = {
    state.transform({ s =>
      if (s.cancelled) {
        (None, s)
      } else {
        val (c, t) = cf()
        c.done.onComplete({ _ =>
          state.transform({ s =>
            ((), s.copy(cancels = s.cancels diff Seq(c)))
          })
        })
        (Some(t), s.copy(cancels = s.cancels :+ c))
      }
    })

  }


}

