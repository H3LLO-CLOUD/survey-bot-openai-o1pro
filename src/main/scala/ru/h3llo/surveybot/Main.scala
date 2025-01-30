package ru.h3llo.surveybot

import scala.concurrent.Future
import scala.util.{Failure, Success}
import java.time.Instant

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.RouteConcatenation

import spray.json._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import slick.jdbc.PostgresProfile.api._
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

// --------------------------------------------------------
// Domain models (example)
// --------------------------------------------------------
case class UserCreateRequest(
                              id: Long,
                              firstName: String,
                              lastName: Option[String],
                              languageCode: Option[String],
                              profilePicture: Option[String],
                              username: Option[String],
                              allowsWriteToPM: Option[Boolean]
                            )

case class User(
                 id: String,
                 firstName: String,
                 lastName: Option[String],
                 languageCode: Option[String],
                 profilePicture: Option[String],
                 username: Option[String],
                 allowsWriteToPM: Boolean,
                 creationDate: Instant
               )

case class ErrorResponse(errorCode: String, message: String)

case class AnswerVariant(id: String, questionId: String, label: String, value: String)

case class QuestionDetail(
                           id: String,
                           surveyId: String,
                           title: String,
                           description: Option[String],
                           index: Int,
                           answerVariants: Seq[AnswerVariant],
                           userAnswerVariantId: Option[String]
                         )

case class SurveyListItem(id: String, title: String, description: String, progress: Int)

case class SurveyDetail(
                         id: String,
                         title: String,
                         description: String,
                         creationDate: Instant,
                         isActive: Boolean,
                         codeValidEndDate: Option[Instant],
                         questions: Seq[QuestionDetail]
                       )

case class AnswerRequest(questionId: String, answerVariantId: String)

case class Answer(
                   id: String,
                   userId: String,
                   questionId: String,
                   answerId: String,
                   timestamp: Instant
                 )

case class Code(
                 id: String,
                 userId: String,
                 surveyId: String,
                 promoCode: String,
                 isUsed: Boolean
               )

case class CodeActivateRequest(promoCode: String)
case class CodeActivateResponse(success: Boolean, message: String)

// --------------------------------------------------------
// Spray JSON Protocols
// --------------------------------------------------------
trait InstantSupport extends DefaultJsonProtocol {
  /** Provide a custom format for java.time.Instant */
  implicit val instantFormat: JsonFormat[Instant] = new JsonFormat[Instant] {
    override def write(obj: Instant): JsValue = JsString(obj.toString)
    override def read(json: JsValue): Instant = json match {
      case JsString(str) => Instant.parse(str)
      case _             => deserializationError("Expected Instant as JsString")
    }
  }
}

/**
 * Combine your default protocol with `InstantSupport`.
 * Also, explicitly use `.apply` in `jsonFormatN(...)`
 * to avoid the "method apply is inserted" warnings.
 */
trait JsonProtocols extends InstantSupport {
  implicit val userCreateRequestFormat: RootJsonFormat[UserCreateRequest] =
    jsonFormat7(UserCreateRequest.apply)

  implicit val userFormat: RootJsonFormat[User] =
    jsonFormat8(User.apply)

  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] =
    jsonFormat2(ErrorResponse.apply)

  implicit val answerVariantFormat: RootJsonFormat[AnswerVariant] =
    jsonFormat4(AnswerVariant.apply)

  implicit val questionDetailFormat: RootJsonFormat[QuestionDetail] =
    jsonFormat7(QuestionDetail.apply)

  implicit val surveyListItemFormat: RootJsonFormat[SurveyListItem] =
    jsonFormat4(SurveyListItem.apply)

  /**
   * SurveyDetail has 7 parameters, so we use jsonFormat7.
   */
  implicit val surveyDetailFormat: RootJsonFormat[SurveyDetail] =
    jsonFormat7(SurveyDetail.apply)

  implicit val answerRequestFormat: RootJsonFormat[AnswerRequest] =
    jsonFormat2(AnswerRequest.apply)

  implicit val answerFormat: RootJsonFormat[Answer] =
    jsonFormat5(Answer.apply)

  implicit val codeFormat: RootJsonFormat[Code] =
    jsonFormat5(Code.apply)

  implicit val codeActivateRequestFormat: RootJsonFormat[CodeActivateRequest] =
    jsonFormat1(CodeActivateRequest.apply)

  implicit val codeActivateResponseFormat: RootJsonFormat[CodeActivateResponse] =
    jsonFormat2(CodeActivateResponse.apply)

  type SurveyList = List[SurveyListItem] // or Seq[SurveyListItem], but choose one

  implicit val surveyListFormat: RootJsonFormat[SurveyList] = listFormat[SurveyListItem]
}

// --------------------------------------------------------
// Slick table definitions (minimal structure)
// --------------------------------------------------------
class UsersTable(tag: Tag) extends Table[(Long, String, Option[String], Option[String], Option[String], Option[String], Boolean, Instant)](tag, "users") {
  // store 'id' as Long (from Telegram) as primary key
  def id = column[Long]("id", O.PrimaryKey)

  def firstName = column[String]("first_name")

  def lastName = column[Option[String]]("last_name")

  def languageCode = column[Option[String]]("language_code")

  def profilePicture = column[Option[String]]("profile_picture")

  def username = column[Option[String]]("username")

  def allowsWriteToPM = column[Boolean]("allows_write_to_pm")

  def creationDate = column[Instant]("creation_date")

  def * = (id, firstName, lastName, languageCode, profilePicture, username, allowsWriteToPM, creationDate)
}

class SurveysTable(tag: Tag) extends Table[(String, String, String, Instant, Boolean, Option[Instant])](tag, "surveys") {
  def id = column[String]("id", O.PrimaryKey)

  def title = column[String]("title")

  def description = column[String]("description")

  def creationDate = column[Instant]("creation_date")

  def isActive = column[Boolean]("is_active")

  def codeValidEndDate = column[Option[Instant]]("code_valid_end_date")

  def * = (id, title, description, creationDate, isActive, codeValidEndDate)
}

class QuestionsTable(tag: Tag) extends Table[(String, String, String, Option[String], Int)](tag, "questions") {
  def id = column[String]("id", O.PrimaryKey)

  def surveyId = column[String]("survey_id")

  def title = column[String]("title")

  def description = column[Option[String]]("description")

  def index = column[Int]("idx")

  def * = (id, surveyId, title, description, index)
}

class AnswerVariantsTable(tag: Tag) extends Table[(String, String, String, String)](tag, "answer_variants") {
  def id = column[String]("id", O.PrimaryKey)

  def questionId = column[String]("question_id")

  def label = column[String]("label")

  def value = column[String]("value")

  def * = (id, questionId, label, value)
}

class AnswersTable(tag: Tag) extends Table[(String, Long, String, String, Instant)](tag, "answers") {
  def id = column[String]("id", O.PrimaryKey)

  def userId = column[Long]("user_id")

  def questionId = column[String]("question_id")

  def answerId = column[String]("answer_id")

  def timestamp = column[Instant]("timestamp")

  def * = (id, userId, questionId, answerId, timestamp)
}

class CodesTable(tag: Tag) extends Table[(String, Long, String, String, Boolean)](tag, "codes") {
  def id = column[String]("id", O.PrimaryKey)

  def userId = column[Long]("user_id")

  def surveyId = column[String]("survey_id")

  def promoCode = column[String]("promo_code")

  def isUsed = column[Boolean]("is_used")

  def * = (id, userId, surveyId, promoCode, isUsed)
}

// --------------------------------------------------------
// Repository (data access)
// --------------------------------------------------------
class SurveyRepository(db: Database)(implicit ec: ExecutionContext) {

  val users = TableQuery[UsersTable]
  val surveys = TableQuery[SurveysTable]
  val questions = TableQuery[QuestionsTable]
  val variants = TableQuery[AnswerVariantsTable]
  val answers = TableQuery[AnswersTable]
  val codes = TableQuery[CodesTable]

  // -- Example user creation
  def createUser(req: UserCreateRequest): Future[Either[ErrorResponse, User]] = {
    // Check if user with ID already exists
    val checkExists = users.filter(_.id === req.id).result.headOption

    // Insert if does not exist
    val now = Instant.now
    val userRow = (
      req.id,
      req.firstName,
      req.lastName,
      req.languageCode,
      req.profilePicture,
      req.username,
      req.allowsWriteToPM.getOrElse(true),
      now
    )

    val insertAction = users += userRow

    val action = for {
      maybeExisting <- checkExists
      result <- maybeExisting match {
        case Some(_) =>
          DBIO.successful(Left(ErrorResponse("USER_ALREADY_EXISTS", s"A user with ID '${req.id}' already exists.")))
        case None =>
          insertAction.map { _ =>
            Right(
              User(
                id = req.id.toString,
                firstName = req.firstName,
                lastName = req.lastName,
                languageCode = req.languageCode,
                profilePicture = req.profilePicture,
                username = req.username,
                allowsWriteToPM = req.allowsWriteToPM.getOrElse(true),
                creationDate = now
              )
            )
          }
      }
    } yield result

    db.run(action.transactionally)
  }

  // -- Example: fetch surveys for a user (just returning some placeholder progress)
  def listSurveysForUser(userId: Long): Future[Either[ErrorResponse, Seq[SurveyListItem]]] = {
    // Check if the user exists
    val userCheck = users.filter(_.id === userId).result.headOption

    val querySurveys = surveys.result // you might do more logic here

    val action = for {
      maybeUser <- userCheck
      result <- maybeUser match {
        case None =>
          DBIO.successful(Left(ErrorResponse("INVALID_USER_ID", s"User ID $userId is not valid or does not exist")))
        case Some(_) =>
          querySurveys.map { surveyRows =>
            // For simplicity, let's pretend "progress" is random or 0
            val items = surveyRows.map { row =>
              SurveyListItem(
                id = row._1,
                title = row._2,
                description = row._3,
                progress = 0 // TODO: compute actual progress
              )
            }
            Right(items)
          }
      }
    } yield result

    db.run(action)
  }

  // -- Example: fetch a survey detail for user
  def getSurveyDetail(surveyId: String, userId: Long): Future[Either[ErrorResponse, SurveyDetail]] = {
    val action = for {
      maybeSurvey <- surveys.filter(_.id === surveyId).result.headOption
      qRows <- questions.filter(_.surveyId === surveyId).result
      avRows <- variants.filter(_.questionId.inSet(qRows.map(_._1))).result
    } yield {
      maybeSurvey match {
        case None =>
          Left(ErrorResponse("SURVEY_NOT_FOUND", s"The requested survey does not exist or is not accessible."))
        case Some(surveyRow) =>
          val (sId, sTitle, sDesc, sCreation, sActive, sCodeEndDate) = surveyRow

          // Build question + variant structures
          val questionDetails = qRows.map { q =>
            val (qId, qSurveyId, qTitle, qDesc, qIndex) = q
            val questionVariants = avRows.filter(_._2 == qId).map { av =>
              // av => (id, questionId, label, value)
              AnswerVariant(av._1, av._2, av._3, av._4)
            }
            // In real logic, you'd find user's existing answer, if any
            QuestionDetail(
              id = qId,
              surveyId = qSurveyId,
              title = qTitle,
              description = qDesc,
              index = qIndex,
              answerVariants = questionVariants,
              userAnswerVariantId = None // or some real answer ID
            )
          }.sortBy(_.index)

          Right(
            SurveyDetail(
              id = sId,
              title = sTitle,
              description = sDesc,
              creationDate = sCreation,
              isActive = sActive,
              codeValidEndDate = sCodeEndDate,
              questions = questionDetails
            )
          )
      }
    }

    db.run(action)
  }

  // -- Example: submit an answer
  def saveAnswer(surveyId: String, userId: Long, ansReq: AnswerRequest): Future[Either[ErrorResponse, Answer]] = {
    // For simplicity, we ignore validations like "question belongs to survey" or "variant belongs to question"
    val now = Instant.now
    val answerId = java.util.UUID.randomUUID().toString
    val row = (answerId, userId, ansReq.questionId, ansReq.answerVariantId, now)

    val action = answers += row

    // Normally you'd do checks to see if question/variant exist, etc.
    db.run(action.asTry).map {
      case Success(_) =>
        Right(
          Answer(
            id = answerId,
            userId = userId.toString,
            questionId = ansReq.questionId,
            answerId = ansReq.answerVariantId,
            timestamp = now
          )
        )
      case Failure(ex) =>
        // Simplified error handling
        Left(ErrorResponse("INVALID_ANSWER_DATA", ex.getMessage))
    }
  }

  // -- Example: view code
  def getCodeForSurvey(surveyId: String, userId: Long): Future[Either[ErrorResponse, Code]] = {
    val action = codes
      .filter(c => c.surveyId === surveyId && c.userId === userId)
      .result
      .headOption

    db.run(action).map {
      case None =>
        Left(ErrorResponse("CODE_NOT_AVAILABLE", "No code found or survey not completed."))
      case Some(row) =>
        val (id, uid, sid, promo, used) = row
        Right(
          Code(
            id = id,
            userId = uid.toString,
            surveyId = sid,
            promoCode = promo,
            isUsed = used
          )
        )
    }
  }

  // -- Example: activate code
  def activateCode(req: CodeActivateRequest): Future[CodeActivateResponse] = {
    // In real logic, you'd check if the code exists, is not used, etc.
    val q = codes.filter(_.promoCode === req.promoCode).result.headOption
    db.run(q).flatMap {
      case None =>
        Future.successful(CodeActivateResponse(false, "Promo code is invalid or already used."))
      case Some(row) =>
        val (id, uid, sid, promo, used) = row
        if (used) {
          Future.successful(CodeActivateResponse(false, "Promo code is invalid or already used."))
        } else {
          // Mark it used
          val updateQ = codes.filter(_.id === id).map(_.isUsed).update(true)
          db.run(updateQ).map { _ =>
            CodeActivateResponse(true, s"Promo code $promo has been activated.")
          }
        }
    }
  }
}

// --------------------------------------------------------
// Routes
// --------------------------------------------------------
class SurveyRoutes(repo: SurveyRepository)(implicit ec: ExecutionContext)
  extends JsonProtocols
    with RouteConcatenation {

  private def extractUserId: Directive1[Long] =
    headerValueByName("X-User-Id").map(_.toLong)

  val createUserRoute: Route =
    path("user") {
      post {
        entity(as[UserCreateRequest]) { req =>
          onSuccess(repo.createUser(req)) {
            case Right(createdUser) =>
              complete((StatusCodes.Created, createdUser))
            case Left(err) if err.errorCode == "USER_ALREADY_EXISTS" =>
              complete((StatusCodes.Conflict, err))
            case Left(err) =>
              complete((StatusCodes.BadRequest, err))
          }
        }
      }
    }

  val listSurveysRoute: Route =
    path("survey") {
      get {
        extractUserId { userId =>
          onSuccess(repo.listSurveysForUser(userId)) {
            case Right(surveys) =>
              // "complete(surveys)" might cause ambiguous implicits for Seq in some Scala versions
              // So let's do either:
              complete(surveys.toList: SurveyList)
            case Left(err) =>
              complete((StatusCodes.BadRequest, err))
          }
        }
      }
    }

  val getSurveyRoute: Route =
    path("survey" / Segment) { surveyId =>
      get {
        extractUserId { userId =>
          onSuccess(repo.getSurveyDetail(surveyId, userId)) {
            case Right(detail) => complete(detail)
            case Left(err)     => complete((StatusCodes.NotFound, err))
          }
        }
      }
    }

  val postAnswerRoute: Route =
    path("survey" / Segment / "answer") { surveyId =>
      post {
        extractUserId { userId =>
          entity(as[AnswerRequest]) { ansReq =>
            onSuccess(repo.saveAnswer(surveyId, userId, ansReq)) {
              case Right(savedAnswer) => complete(savedAnswer)
              case Left(err) =>
                err.errorCode match {
                  case "INVALID_ANSWER_DATA" => complete((StatusCodes.BadRequest, err))
                  case "QUESTION_NOT_FOUND"  => complete((StatusCodes.NotFound, err))
                  case _                     => complete((StatusCodes.BadRequest, err))
                }
            }
          }
        }
      }
    }

  val getCodeRoute: Route =
    path("code" / Segment) { surveyId =>
      get {
        extractUserId { userId =>
          onSuccess(repo.getCodeForSurvey(surveyId, userId)) {
            case Right(code) => complete(code)
            case Left(err)   => complete((StatusCodes.NotFound, err))
          }
        }
      }
    }

  val activateCodeRoute: Route =
    path("code" / "activate") {
      post {
        entity(as[CodeActivateRequest]) { req =>
          onSuccess(repo.activateCode(req)) { resp =>
            if (resp.success) complete((StatusCodes.OK, resp))
            else complete((StatusCodes.BadRequest, resp))
          }
        }
      }
    }

  val routes: Route =
    createUserRoute ~
      listSurveysRoute ~
      getSurveyRoute ~
      postAnswerRoute ~
      getCodeRoute ~
      activateCodeRoute
}

// --------------------------------------------------------
// Main App (skeleton)
// --------------------------------------------------------
object Main extends App {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "SurveyBotSystem")
  implicit val ec: ExecutionContext        = system.executionContext

  val db   = Database.forURL(
    "jdbc:postgresql://localhost:5432/survey_bot_db",
    user = "survey_bot_user",
    password = "L2eVC5aTUB1tzni3tYW0gZ06sGCrsqP5/45dUUE+JWw="
  )
  val repo = new SurveyRepository(db)

  val routes = new SurveyRoutes(repo).routes

  Http().newServerAt("0.0.0.0", 8080).bind(routes)
  println("Server running at http://localhost:8080")
}
