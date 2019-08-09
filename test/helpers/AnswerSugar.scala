package helpers

import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.Future

trait AnswerSugar {

  implicit def toAnswerWithArguments[T](f: (InvocationOnMock) => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f(invocation)
  }

  implicit def toAnswer[T](f: () => T): Answer[T] = new Answer[T] {
    override def answer(invocation: InvocationOnMock): T = f()
  }

  def future[T](o: T): Future[T] = Future.successful(o)
}

object AnswerSugar extends AnswerSugar