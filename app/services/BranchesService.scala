package services

import forms.BranchForm.CreateBranch
import javax.inject.{Inject, Singleton}
import models.{AppException, Branch, ErrorCodes, User}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import play.api.{Configuration, Logging}
import services.MongoApi._
import utils.TaskExt._
import zio._

import scala.concurrent.ExecutionContext

trait BranchesService {
  def create(create: CreateBranch, user: User): Task[Branch]
  def update(branchId: String, update: CreateBranch, user: User): Task[(Branch, Branch)]
  def isAuthorized(branchId: String, user: User): Task[Boolean]
  def get(branchId: String): Task[Option[Branch]]
  def list(parent: String): Task[List[Branch]]
  def remove(branchId: String): Task[Branch]
}

@Singleton
class MongoBranchesService @Inject()(conf: Configuration, userService: UserService, mongoApi: MongoApi)
                                    (implicit exec: ExecutionContext)
  extends BranchesService with Logging {

  private val MAX_TRIES = 5

  val col = mongoApi.collection[Branch]("branches")

  def create(create: CreateBranch, user: User): Task[Branch] = {
    val id = Branch.nextId
    val branch = create.parentOrRoot match {
      case Branch.ROOT =>
        Task(
          Branch(create.name, user.id, create.description, id :: Nil, id)
        )
      case parentId: String =>
        getOrFail(parentId) map { parent =>
          Branch(create.name, user.id, create.description, id :: parent.hierarchy, id)
        }
    }

    branch flatMap { b =>
      createSafe(b, MAX_TRIES)
    }
  }

  def update(id: String, update: CreateBranch, user: User): Task[(Branch, Branch)] = {

    for {
      newParentHierarchy <- update.parentOrRoot match {
        case Branch.ROOT => Task.succeed(Nil)
        case parentId: String => getOrFail(parentId) map (parent => parent.hierarchy)
      }
      updateBranch = Branch(update.name, user.id, update.description, id :: newParentHierarchy, id)
      oldBranch   <- col.findOneAndReplace(equal("_id", id), updateBranch)
                        .toOptionTask.orFail(AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id isn't found"))
      oldParentHierarchy = oldBranch.hierarchy.drop(1)
      _ <- if (oldParentHierarchy != newParentHierarchy) {
        val selector = all("hierarchy", oldBranch.hierarchy)
        val push = Updates.pushEach("hierarchy", newParentHierarchy:_*)
        val pull = Updates.pullAll("hierarchy", oldParentHierarchy:_*)
        for {
          _ <- col.updateOne(selector, push).toTask
          _ <- col.updateMany(selector, pull).toTask
          _ <- userService.updateHierarchy(oldBranch.hierarchy, newParentHierarchy)
        } yield ()
      } else { Task.unit }
    } yield oldBranch -> updateBranch
  }

  def get(id: String): Task[Option[Branch]] = col.find(equal("_id", id)).first.toOptionTask

  override def list(parent: String): Task[List[Branch]] = parent match {
    case Branch.ROOT =>
      col.find(size("hierarchy", 1)).toTask.map(_.toList)
    case branch: String =>
      col.find(equal("hierarchy.1", branch)).toTask.map(_.toList)
  }

  def remove(id: String): Task[Branch] = {
    for {
      assignedUsers <- userService.count(Some(id))
      childBranches <- col.countDocuments(equal("hierarchy.1", id)).toTask
      _ <- if (childBranches > 0) Task.fail(AppException(ErrorCodes.NON_EMPTY_SET, s"Branch $id containing at least one child branch!")) else Task.unit
      _ <- if (assignedUsers > 0) Task.fail(AppException(ErrorCodes.NON_EMPTY_SET, s"Branch $id containing at least one user!")) else Task.unit
      c <- col.findOneAndDelete(equal("_id", id)).toOptionTask.map (_.getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id isn't found")))
    } yield c
  }

  override def isAuthorized(branchId: String, user: User): Task[Boolean] = user.branch match {
    case Some(`branchId`) => // users branch match `branchId`
      Task.succeed(true)
    case Some(userBranch) =>
      branchId match {
        case Branch.ROOT => Task.succeed(false) // user with a branch is not allowed to access root
        case id: String => getOrFail(id) map { branch => branch.belongs(userBranch) }
      }

    case None => Task.succeed(true)
  }

  private def getOrFail(id: String): Task[Branch] = get(id).map(_.getOrElse {
    throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id is not found")
  })

  private def createSafe(branch: Branch, tries: Int): Task[Branch] = {
    col.insertOne(branch).toUnitTask.map(_ => branch).catchSome {
      case dbEx: Exception if tries > 0 => // TODO: check for exact exception
        // duplicate key, try another ID
        val id = Branch.nextId
        val updatedBranch = branch.copy(id = Branch.nextId, hierarchy = id :: branch.hierarchy.tail)
        createSafe(updatedBranch, tries - 1)
    }
  }
}