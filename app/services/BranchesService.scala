package services

import javax.inject.{Inject, Singleton}
import akka.http.scaladsl.util.FastFuture
import forms.BranchForm.CreateBranch
import models.{AppException, Branch, ErrorCodes, User}
import play.api.{Configuration, Logging}
import play.api.libs.json._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.api.{Cursor, ReadPreference}
import reactivemongo.play.json._
import reactivemongo.core.errors.DatabaseException
import reactivemongo.play.json.collection.JSONCollection
import ErrorCodes._
import scala.concurrent.{ExecutionContext, Future}

trait BranchesService {
  def create(create: CreateBranch, user: User): Future[Branch]
  def update(branchId: String, update: CreateBranch, user: User): Future[(Branch, Branch)]
  def isAuthorized(branchId: String, user: User): Future[Boolean]
  def get(branchId: String): Future[Option[Branch]]
  def list(parent: String): Future[List[Branch]]
  def remove(branchId: String): Future[Branch]
}

@Singleton
class MongoBranchesService @Inject()(conf: Configuration,
                                     mongoApi: ReactiveMongoApi)(implicit exec: ExecutionContext)
  extends BranchesService with Logging {

  private val MAX_TRIES = 5

  implicit val format = Branch.mongoFormat

  def create(create: CreateBranch, user: User): Future[Branch] = {
    val id = Branch.nextId
    val branchFuture = create.parentOrRoot match {
      case Branch.ROOT =>
        Future.successful(
          Branch(create.name, user.id, create.description, id :: Nil, id)
        )
      case parentId: String =>
        getOrFail(parentId) map { parent =>
          Branch(create.name, user.id, create.description, id :: parent.hierarchy, id)
        }
    }

    branchFuture flatMap { b =>
      createSafe(b, MAX_TRIES)
    }
  }

  def update(id: String, update: CreateBranch, user: User): Future[(Branch, Branch)] = {

    for {
      newParentHierarchy <- update.parentOrRoot match {
        case Branch.ROOT => Future.successful(Nil)
        case parentId: String => getOrFail(parentId) map (parent => parent.hierarchy)
      }
      uCollection <- users
      bCollection <- branches
      updateBranch = Branch(update.name, user.id, update.description, id :: newParentHierarchy, id)
      optionalOldBranch <- bCollection.findAndUpdate(byId(id), updateBranch, false).map(_.result[Branch])
    } yield {
      optionalOldBranch match {
        case Some(oldBranch) =>
          val oldParentHierarchy = oldBranch.hierarchy.drop(1)
          if (oldParentHierarchy != newParentHierarchy) {
            val selector = Json.obj("hierarchy" -> Json.obj("$all" -> oldBranch.hierarchy))
            val push = Json.obj("$push" -> Json.obj("hierarchy" -> Json.obj("$each" -> newParentHierarchy)))
            val pull = Json.obj("$pullAll" -> Json.obj("hierarchy" -> oldParentHierarchy))
            bCollection.update(selector, push, multi = true)
            bCollection.update(selector, pull, multi = true)
            uCollection.update(selector, push, multi = true)
            uCollection.update(selector, pull, multi = true)
          }
          oldBranch -> updateBranch
        case _ => throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id isn't found")
      }
    }
  }

  def get(id: String): Future[Option[Branch]] = branches.flatMap(_.find(byId(id)).one[Branch])

  override def list(parent: String): Future[List[Branch]] = parent match {
    case Branch.ROOT =>
      branches.flatMap(
        _.find(Json.obj("hierarchy" -> Json.obj("$size" -> 1)))
          .cursor[Branch](ReadPreference.secondaryPreferred)
          .collect[List](-1, Cursor.FailOnError[List[Branch]]())
      )
    case branch: String =>
      branches.flatMap(
        _.find(Json.obj("hierarchy.1" -> branch))
          .cursor[Branch](ReadPreference.secondaryPreferred)
          .collect[List](-1, Cursor.FailOnError[List[Branch]]())
      )
  }

  def remove(id: String): Future[Branch] = {

    val futureResult = for {
      uCollection <- users
      bCollection <- branches
      assignedUsers <- uCollection.count(Some(Json.obj("hierarchy.0" -> id)))
      childBranches <- bCollection.count(Some(Json.obj("hierarchy.1" -> id)))
    } yield {
      if (childBranches > 0) { throw AppException(ErrorCodes.NON_EMPTY_SET, s"Branch $id containing at least one child branch!") }
      else if (assignedUsers > 0) { throw AppException(ErrorCodes.NON_EMPTY_SET, s"Branch $id containing at least one user!") }
      else { bCollection.findAndRemove(byId(id)).map(_.result[Branch]).map (
        _.getOrElse(throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id isn't found"))
      ) }
    }
    futureResult.flatten
  }

  override def isAuthorized(branchId: String, user: User): Future[Boolean] = user.branch match {
    case Some(`branchId`) => // users branch match `branchId`
      FastFuture.successful(true)
    case Some(userBranch) =>
      branchId match {
        case Branch.ROOT => FastFuture.successful(false) // user with a branch is not allowed to access root
        case id: String => getOrFail(id) map { branch => branch.belongs(userBranch) }
      }

    case None => FastFuture.successful(true)
  }

  private def getOrFail(id: String): Future[Branch] = get(id).map(_.getOrElse {
    throw AppException(ErrorCodes.ENTITY_NOT_FOUND, s"Branch $id is not found")
  })

  private def createSafe(branch: Branch, tries: Int): Future[Branch] = {
    branches.flatMap(_.insert.one(branch).map(_ => branch)).recoverWith {
      case dbEx: DatabaseException if dbEx.code.exists(c => c == 11000 || c == 11001) && tries > 0 =>
        // duplicate key, try another ID
        val id = Branch.nextId
        val updatedBranch = branch.copy(id = Branch.nextId, hierarchy = id :: branch.hierarchy.tail)

        createSafe(updatedBranch, tries - 1)

      case other: Throwable =>
        throw other
    }
  }

  private def users = mongoApi.database.map(_.collection[JSONCollection]("users"))

  private def branches = mongoApi.database.map(_.collection[JSONCollection]("branches"))

  private def byId(id: String) = Json.obj("_id" -> id)
}