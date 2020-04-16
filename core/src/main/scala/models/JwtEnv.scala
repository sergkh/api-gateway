package models

import com.mohiva.play.silhouette.api.Env
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
trait JwtEnv extends Env {
  type I = User
  type A = JWTAuthenticator
}
