package utils

import com.twitter.chill.{KryoInstantiator, KryoPool, ScalaKryoInstantiator}

import scala.reflect.ClassTag

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 26/05/17
  */
object KryoSerializer {

  val pool = ScalaKryoInstantiator.defaultPool

  def toBytes[T](t: T): Array[Byte] = pool.toBytesWithoutClass(t)
  def fromBytes[T](bytes: Array[Byte])(implicit classTag: ClassTag[T]): T =
    pool.fromBytes(bytes, classTag.runtimeClass.asInstanceOf[Class[T]])
}
