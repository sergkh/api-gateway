package models

import scala.compat.Platform

case class Session(_id: String,
                   userId: String,
                   createdAt: Long,
                   expiredAt: Long,
                   agent: String,
                   ip: String) {
  val onlineTime = expiredAt - createdAt
}

object Session {

  def apply(_id: String, userId: String, expiredAt: Long, agent: String, ip: String): Session = {
    new Session(_id, userId, Platform.currentTime, expiredAt, agent, ip)
  }

}

