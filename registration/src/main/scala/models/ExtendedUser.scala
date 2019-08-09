package models

import java.util.Date

import play.api.libs.json.{Json, OFormat}

/**
  *
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  *         created on 03/02/17
  */
case class ExtendedUser(_id: Long,
                        gender: Option[Int] = None, //According to ISO/IEC 5218
                        birthDate: Option[Date] = None,
                        invitationCode: Option[String] = None,
                        inviterCode: Option[String] = None,
                        created: Date = new Date()
                       )

object ExtendedUser {
  implicit val fmt: OFormat[ExtendedUser] = Json.format[ExtendedUser]

  val serviceFields = Seq("invitationCode", "inviterCode")

}
