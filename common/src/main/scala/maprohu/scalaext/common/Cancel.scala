package maprohu.scalaext.common

import scala.concurrent.{Future, Promise}

object Cancel {

  def apply(cancel: () => Future[Any]) : Cancel = {
    val promise = Promise[Any]()

    Cancel(
      () => promise.completeWith(cancel()),
      promise.future
    )
  }

}

case class Cancel(
  cancel: () => Unit,
  done: Future[Any]
) {

  def perform = {
    cancel()
    done
  }

}
