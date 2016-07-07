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

  def fromPromise(factory: Promise[Any] => Unit) : Cancel = {
    val promise = Promise[Any]()
    factory(promise)
    Cancel(
      () => promise.trySuccess(()),
      promise.future
    )
  }

  def cancelled = Cancel(
    () => (),
    Future.successful(())
  )


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
