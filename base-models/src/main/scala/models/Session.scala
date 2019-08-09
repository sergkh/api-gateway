package models

import scala.compat.Platform

case class Session(_id: String,
                   userId: Long,
                   createdAt: Long,
                   expiredAt: Long,
                   token: String,
                   agent: String,
                   ip: String) {
  val onlineTime = expiredAt - createdAt
}

object Session {

  def apply(_id: String, userId: Long, expiredAt: Long, token: String, agent: String, ip: String): Session = {
    new Session(_id, userId, Platform.currentTime, expiredAt, token, agent, ip)
  }

}

