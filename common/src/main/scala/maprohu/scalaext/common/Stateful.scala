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

  def map[K, V](factory: K => V) = new StatefulMap(factory)

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

  def get = process(s => s)

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

class StatefulMap[K, V](factory: K => V) extends Stateful[Map[K, V]](Map()) {

  def get(key: K) = transform { map =>
    map
      .get(key)
      .map(value => (value, map))
      .getOrElse {
        val value = factory(key)

        (value, map.updated(key, value))
      }
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


