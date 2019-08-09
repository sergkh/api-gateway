package helpers

import com.impactua.bouncer.commons.models.{User => CommonsUser}
import models.User
import utils.UuidGenerator

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>.
  *         created on 07.08.2016.
  */
trait Context {

  /**
    * An identity.
    */
  val identity = User(
    uuid = UuidGenerator.generateId,
    firstName = None,
    lastName = None,
    email = Some("user" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password1"
  )

  val newIdentity = User(
    uuid = UuidGenerator.generateId,
    email = Some("newuser" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password12",
    permissions = Seq("users:edit", "users:read")
  )

  val referralIdentity = User(
    uuid = UuidGenerator.generateId,
    email = Some("referral" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password12",
    permissions = Seq("users:edit", "users:read")
  )

  val adminIdentity = User(
    uuid = UuidGenerator.generateId,
    firstName = None,
    lastName = None,
    email = Some("admin@cross.com"),
    phone = None,
    passHash = "admin-pass",
    permissions = Seq("internal_web", "users:edit", "users:read", "config:edit")
  )

}
