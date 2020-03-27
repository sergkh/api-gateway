package helpers

import models.User
import utils.UuidGenerator

/**
  * @author Yaroslav Derman <yaroslav.derman@gmail.com>
  */
trait Context {

  /**
    * An identity.
    */
  val identity = User(
    id = UuidGenerator.generateId,
    firstName = None,
    lastName = None,
    email = Some("user" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password1"
  )

  val newIdentity = User(
    id = UuidGenerator.generateId,
    email = Some("newuser" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password12",
    permissions = List("users:edit", "users:read")
  )

  val referralIdentity = User(
    id = UuidGenerator.generateId,
    email = Some("referral" + UuidGenerator.generateId + "@gmail.com"),
    phone = None,
    passHash = "Strong_Password12",
    permissions = List("users:edit", "users:read")
  )

  val adminIdentity = User(
    id = UuidGenerator.generateId,
    firstName = None,
    lastName = None,
    email = Some("admin@cross.com"),
    phone = None,
    passHash = "admin-pass",
    permissions = List("internal_web", "users:edit", "users:read", "config:edit")
  )

}
