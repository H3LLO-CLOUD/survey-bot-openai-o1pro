package ru.h3llo.surveybot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ProvenShape, Tag}

import spray.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import java.time.Instant
import java.util.UUID

/**
 * Entry point for the Survey microservice.
 */
object SurveyMicroservice {

  def main(args: Array[String]): Unit = {
    // Classic ActorSystem
    implicit val system: ActorSystem        = ActorSystem("survey-actor-system")
    // Single ExecutionContext to avoid ambiguity
    implicit val ec: ExecutionContext       = system.dispatcher
    implicit val mat: Materializer          = Materializer(system)

    // Initialize Slick Database from application.conf (see slick.dbs.default)
    val db = Database.forConfig("slick.dbs.default.db")

    // Create Repositories
    val repositories = new Repositories(db)(ec)

    // Initialize DB schema
    repositories.initSchema().onComplete {
      case Success(_) =>
        system.log.info("Database schema created or updated successfully.")
      case Failure(ex) =>
        system.log.error("Failed to create/update database schema", ex)
        system.terminate()
    }

    // Build routes
    val routes: Route = new SurveyRoutes(repositories).allRoutes

    // Start server
    Http()
      .newServerAt("0.0.0.0", 8080)
      .bind(routes)
      .onComplete {
        case Success(binding) =>
          system.log.info(
            s"Server started at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}"
          )
        case Failure(ex) =>
          system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
          system.terminate()
      }
  }
}

// --------------------------------------------------
// Domain Models
// --------------------------------------------------

case class User(
                 id:              Long,
                 firstName:       String,
                 lastName:        Option[String],
                 languageCode:    String,
                 profilePicture:  Option[String],
                 username:        Option[String],
                 allowsWriteToPm: Option[Boolean],
                 creationDate:    Instant
               )

case class Survey(
                   id:               String,
                   title:            String,
                   description:      String,
                   creationDate:     Instant,
                   isActive:         Boolean,
                   codeValidEndDate: Instant,
                 )

case class Question(
                     id:          String,
                     surveyId:    String,
                     title:       String,
                     description: String,
                     index:       Int
                   )

case class AnswerVariant(
                          id:         String,
                          questionId: String,
                          label:      String,
                          value:      String
                        )

case class Answer(
                   id:         String,
                   userId:     String,
                   questionId: String,
                   answerId:   String,
                   timestamp:  Instant
                 )

case class Code(
                 id:       String,
                 userId:   String,
                 surveyId: String,
                 isUsed:   Boolean
               )

// --------------------------------------------------
// JSON Support
// --------------------------------------------------

trait JsonSupport extends DefaultJsonProtocol {

  // For reading/writing Instant as ISO-8601 strings
  implicit val instantFormat: JsonFormat[Instant] = new JsonFormat[Instant] {
    def write(obj: Instant): JsValue = JsString(obj.toString)
    def read(json: JsValue): Instant = json match {
      case JsString(str) => Instant.parse(str)
      case _             => deserializationError("Expected ISO-8601 date-time string")
    }
  }

  // Format for domain objects
  implicit val userFormat: RootJsonFormat[User] = jsonFormat8(User.apply)
  implicit val surveyFormat: RootJsonFormat[Survey] = jsonFormat6(Survey.apply)
  implicit val questionFormat: RootJsonFormat[Question] = jsonFormat5(Question.apply)
  implicit val answerVariantFormat: RootJsonFormat[AnswerVariant] = jsonFormat4(AnswerVariant.apply)
  implicit val answerFormat: RootJsonFormat[Answer] = jsonFormat5(Answer.apply)
  implicit val codeFormat: RootJsonFormat[Code] = jsonFormat4(Code.apply)

  // For request bodies
  case class CreateUserRequest(
                                id:              Long,
                                firstName:       String,
                                lastName:        Option[String],
                                languageCode:    String,
                                profilePicture:  Option[String],
                                username:        Option[String],
                                allowsWriteToPm: Option[Boolean]
                              )
  implicit val createUserRequestFormat: RootJsonFormat[CreateUserRequest] =
    jsonFormat7(CreateUserRequest.apply)

  case class SubmitAnswerRequest(questionId: String, answerVariantId: String)
  implicit val submitAnswerRequestFormat: RootJsonFormat[SubmitAnswerRequest] =
    jsonFormat2(SubmitAnswerRequest.apply)

  case class ActivateCodeRequest(codeId: String)
  implicit val activateCodeRequestFormat: RootJsonFormat[ActivateCodeRequest] =
    jsonFormat1(ActivateCodeRequest.apply)

  // Response wrappers to avoid spray-json map marshalling ambiguity
  case class ErrorResponse(errorCode: String, message: String)
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] =
    jsonFormat2 { (ec: String, msg: String) =>
      ErrorResponse(ec, msg)
    }

  case class SuccessResponse(success: Boolean)
  implicit val successResponseFormat: RootJsonFormat[SuccessResponse] =
    jsonFormat1 { (s: Boolean) =>
      SuccessResponse(s)
    }

  case class CodeResponse(code: String)
  implicit val codeResponseFormat: RootJsonFormat[CodeResponse] =
    jsonFormat1 { (c: String) =>
      CodeResponse(c)
    }
}

// --------------------------------------------------
// Slick Repositories
// --------------------------------------------------

/** Repositories aggregator to init schema on startup. */
class Repositories(db: Database)(implicit ec: ExecutionContext) {
  val userRepo   = new UserRepository(db)
  val surveyRepo = new SurveyRepository(db)
  val codeRepo   = new CodeRepository(db)

  def initSchema(): Future[Unit] = {
    val action = DBIO.seq(
      userRepo.usersTable.schema.createIfNotExists,
      surveyRepo.surveysTable.schema.createIfNotExists,
      surveyRepo.questionsTable.schema.createIfNotExists,
      surveyRepo.answerVariantsTable.schema.createIfNotExists,
      surveyRepo.answersTable.schema.createIfNotExists,
      codeRepo.codesTable.schema.createIfNotExists
    )
    db.run(action)
  }
}

// --------------------------------------------------
// User Repository
// --------------------------------------------------
class UserRepository(db: Database)(implicit ec: ExecutionContext) {

  class UserTable(tag: Tag) extends Table[User](tag, "users") {
    def id              = column[Long]("id", O.PrimaryKey)
    def firstName       = column[String]("first_name")
    def lastName        = column[Option[String]]("last_name")
    def languageCode    = column[String]("language_code")
    def profilePicture  = column[Option[String]]("profile_picture")
    def username        = column[Option[String]]("username")
    def allowsWriteToPM = column[Option[Boolean]]("allows_write_to_pm")
    def creationDate    = column[Instant]("creation_date")

    def * : ProvenShape[User] =
      (id, firstName, lastName, languageCode, profilePicture, username, allowsWriteToPM, creationDate).mapTo[User]
  }

  val usersTable = TableQuery[UserTable]

  def createUser(user: User): Future[Int] =
    db.run(usersTable += user)

  def findById(userId: Long): Future[Option[User]] =
    db.run(usersTable.filter(_.id === userId).result.headOption)
}

// --------------------------------------------------
// Survey Repository
// --------------------------------------------------
class SurveyRepository(db: Database)(implicit ec: ExecutionContext) {

  // Surveys
  class SurveyTable(tag: Tag) extends Table[Survey](tag, "surveys") {
    def id               = column[String]("id", O.PrimaryKey)
    def title            = column[String]("title")
    def description      = column[String]("description")
    def creationDate     = column[Instant]("creation_date")
    def isActive         = column[Boolean]("is_active")
    def codeValidEndDate = column[Instant]("code_valid_end_date")

    def * : ProvenShape[Survey] =
      (id, title, description, creationDate, isActive, codeValidEndDate).mapTo[Survey]
  }
  val surveysTable = TableQuery[SurveyTable]

  // Questions
  class QuestionTable(tag: Tag) extends Table[Question](tag, "questions") {
    def id          = column[String]("id", O.PrimaryKey)
    def surveyId    = column[String]("survey_id")
    def title       = column[String]("title")
    def description = column[String]("description")
    def index       = column[Int]("question_index")

    def surveyFk = foreignKey("fk_survey", surveyId, surveysTable)(_.id)

    def * : ProvenShape[Question] =
      (id, surveyId, title, description, index).mapTo[Question]
  }
  val questionsTable = TableQuery[QuestionTable]

  // AnswerVariants
  class AnswerVariantTable(tag: Tag) extends Table[AnswerVariant](tag, "answer_variants") {
    def id         = column[String]("id", O.PrimaryKey)
    def questionId = column[String]("question_id")
    def label      = column[String]("label")
    def value      = column[String]("value")

    def questionFk = foreignKey("fk_question", questionId, questionsTable)(_.id)

    def * : ProvenShape[AnswerVariant] =
      (id, questionId, label, value).mapTo[AnswerVariant]
  }
  val answerVariantsTable = TableQuery[AnswerVariantTable]

  // Answers
  class AnswerTable(tag: Tag) extends Table[Answer](tag, "answers") {
    def id         = column[String]("id", O.PrimaryKey)
    def userId     = column[String]("user_id")
    def questionId = column[String]("question_id")
    def answerId   = column[String]("answer_variant_id")
    def timestamp  = column[Instant]("timestamp")

    def questionFk = foreignKey("fk_question_answer", questionId, questionsTable)(_.id)

    def * : ProvenShape[Answer] =
      (id, userId, questionId, answerId, timestamp).mapTo[Answer]
  }
  val answersTable = TableQuery[AnswerTable]

  // --------------------------------

  def findSurveysForUser(userId: String): Future[Seq[Survey]] = {
    // For demonstration, just return active surveys
    db.run(surveysTable.filter(_.isActive === true).result)
  }

  def findSurveyById(surveyId: String): Future[Option[Survey]] = {
    db.run(surveysTable.filter(_.id === surveyId).result.headOption)
  }

  /** Returns questions for a given survey plus the list of answer variants for each question. */
  def findQuestionsAndAnswers(
                               surveyId: String
                             ): Future[Seq[(Question, Seq[AnswerVariant])]] = {
    // fetch all questions for the survey
    val questionsQuery = questionsTable.filter(_.surveyId === surveyId)

    db.run(questionsQuery.result).flatMap { questions =>
      Future.traverse(questions) { q =>
        // fetch variants for each question
        db.run(answerVariantsTable.filter(_.questionId === q.id).result).map { variants =>
          (q, variants)
        }
      }
    }
  }

  def findUserAnswersForSurvey(
                                userId: String,
                                surveyId: String
                              ): Future[Seq[Answer]] = {
    // get all questions for the given survey, then filter answers by question
    val questionIds = questionsTable.filter(_.surveyId === surveyId).map(_.id)
    db.run(answersTable.filter(a => a.userId === userId && a.questionId.in(questionIds)).result)
  }

  def submitAnswer(answer: Answer): Future[Int] = {
    db.run(answersTable += answer)
  }
}

// --------------------------------------------------
// Code Repository
// --------------------------------------------------
class CodeRepository(db: Database)(implicit ec: ExecutionContext) {

  class CodeTable(tag: Tag) extends Table[Code](tag, "codes") {
    def id       = column[String]("id", O.PrimaryKey)
    def userId   = column[String]("user_id")
    def surveyId = column[String]("survey_id")
    def isUsed   = column[Boolean]("is_used")

    def * : ProvenShape[Code] =
      (id, userId, surveyId, isUsed).mapTo[Code]
  }

  val codesTable = TableQuery[CodeTable]

  def findCodeForUserAndSurvey(userId: String, surveyId: String): Future[Option[Code]] = {
    db.run(codesTable.filter(c => c.userId === userId && c.surveyId === surveyId).result.headOption)
  }

  def createOrUpdateCode(code: Code): Future[Int] = {
    // A naive "upsert" approach
    db.run(codesTable.insertOrUpdate(code))
  }

  def findById(codeId: String): Future[Option[Code]] = {
    db.run(codesTable.filter(_.id === codeId).result.headOption)
  }

  def markCodeUsed(codeId: String): Future[Int] = {
    db.run(codesTable.filter(_.id === codeId).map(_.isUsed).update(true))
  }
}

// --------------------------------------------------
// Survey Routes
// --------------------------------------------------
class SurveyRoutes(repos: Repositories)(implicit ec: ExecutionContext)
  extends JsonSupport {

  private val userRepo   = repos.userRepo
  private val surveyRepo = repos.surveyRepo
  private val codeRepo   = repos.codeRepo

  val allRoutes: Route = concat(
    userRoutes,
    surveyRoutes,
    codeRoutes
  )

  // ------------------------------
  //  User routes
  // ------------------------------
  private def userRoutes: Route = pathPrefix("user") {
    pathEnd {
      post {
        entity(as[CreateUserRequest]) { request =>
          onSuccess(userRepo.findById(request.id)) {
            case Some(_) =>
              complete(
                StatusCodes.Conflict,
                ErrorResponse(
                  "USER_ALREADY_EXISTS",
                  s"User with ID ${request.id} already exists"
                )
              )
            case None =>
              val newUser = User(
                id              = request.id,
                firstName       = request.firstName,
                lastName        = request.lastName,
                languageCode    = request.languageCode,
                profilePicture  = request.profilePicture,
                username        = request.username,
                allowsWriteToPm = request.allowsWriteToPm,
                creationDate    = Instant.now()
              )
              onSuccess(userRepo.createUser(newUser)) { _ =>
                complete(StatusCodes.Created, newUser) // user as response
              }
          }
        }
      }
    }
  }

  // ------------------------------
  //  Survey routes
  // ------------------------------
  private def surveyRoutes: Route = pathPrefix("survey") {
    concat(
      // GET /survey : list active surveys for user
      pathEnd {
        get {
          headerValueByName("userId") { userId =>
            onSuccess(surveyRepo.findSurveysForUser(userId)) { surveys =>
              val futWithProgress = Future.traverse(surveys) { s =>
                for {
                  questionsAndVariants <- surveyRepo.findQuestionsAndAnswers(s.id)
                  userAnswers          <- surveyRepo.findUserAnswersForSurvey(userId, s.id)
                } yield {
                  val totalQuestions = questionsAndVariants.length
                  val answeredCount  = userAnswers.map(_.questionId).distinct.length
                  val progress       = if (totalQuestions > 0) (answeredCount * 100) / totalQuestions else 0

                  JsObject(
                    "id"          -> JsString(s.id),
                    "title"       -> JsString(s.title),
                    "description" -> JsString(s.description),
                    "progress"    -> JsNumber(progress)
                  )
                }
              }

              onSuccess(futWithProgress) { results =>
                complete(StatusCodes.OK, JsArray(results.toVector))
              }
            }
          }
        }
      },
      // GET /survey/{surveyId} or /survey/{surveyId}/
      path(Segment) { surveyId =>
        pathEndOrSingleSlash {
          get {
            headerValueByName("userId") { userId =>
              println(s"Looking for survey with ID: $surveyId for user $userId")
              onSuccess(surveyRepo.findSurveyById(surveyId)) {
                case None =>
                  complete(
                    StatusCodes.NotFound,
                    ErrorResponse(
                      "SURVEY_NOT_FOUND",
                      s"Survey with ID $surveyId not found"
                    )
                  )
                case Some(survey) =>
                  onSuccess(surveyRepo.findQuestionsAndAnswers(surveyId)) { qAndV =>
                    // get existing user answers
                    val userAnswersF = surveyRepo.findUserAnswersForSurvey(userId, surveyId)
                    onSuccess(userAnswersF) { userAnswers =>
                      val ansMap = userAnswers.map(a => a.questionId -> a.answerId).toMap

                      val jsonQuestions = qAndV.map { case (question, variants) =>
                        JsObject(
                          "id" -> JsString(question.id),
                          "title" -> JsString(question.title),
                          "description" -> JsString(question.description),
                          "index" -> JsNumber(question.index),
                          "answerVariants" -> JsArray(variants.map { av =>
                            JsObject(
                              "id" -> JsString(av.id),
                              "label" -> JsString(av.label),
                              "value" -> JsString(av.value)
                            )
                          }.toVector),
                          "userAnswer" -> ansMap
                            .get(question.id)
                            .map(JsString(_))
                            .getOrElse(JsNull)
                        )
                      }

                      val result = JsObject(
                        "id"          -> JsString(survey.id),
                        "title"       -> JsString(survey.title),
                        "description" -> JsString(survey.description),
                        "questions"   -> JsArray(jsonQuestions.toVector)
                      )

                      complete(StatusCodes.OK, result)
                    }
                  }
              }
            }
          }
        }
      },
      // POST /survey/{surveyId}/answer
      path(Segment / "answer") { surveyId =>
        post {
          headerValueByName("userId") { userId =>
            entity(as[SubmitAnswerRequest]) { body =>
              if (body.questionId.isEmpty || body.answerVariantId.isEmpty) {
                complete(
                  StatusCodes.BadRequest,
                  ErrorResponse(
                    "INVALID_REQUEST",
                    "Question ID and answer variant ID are required"
                  )
                )
              } else {
                // check if survey exists
                onSuccess(surveyRepo.findSurveyById(surveyId)) {
                  case None =>
                    complete(
                      StatusCodes.NotFound,
                      ErrorResponse(
                        "SURVEY_NOT_FOUND",
                        s"Survey with ID $surveyId not found"
                      )
                    )
                  case Some(_) =>
                    val ans = Answer(
                      id         = UUID.randomUUID().toString,
                      userId     = userId,
                      questionId = body.questionId,
                      answerId   = body.answerVariantId,
                      timestamp  = Instant.now()
                    )
                    onSuccess(surveyRepo.submitAnswer(ans)) { _ =>
                      complete(StatusCodes.OK, SuccessResponse(success = true))
                    }
                }
              }
            }
          }
        }
      }
    )
  }

  // ------------------------------
  //  Code routes
  // ------------------------------
  private def codeRoutes: Route = pathPrefix("code") {
    concat(
      // GET /code/{surveyId}
      path(Segment) { surveyId =>
        get {
          headerValueByName("userId") { userId =>
            onSuccess(codeRepo.findCodeForUserAndSurvey(userId, surveyId)) {
              case Some(code) =>
                complete(StatusCodes.OK, CodeResponse(code.id))
              case None =>
                complete(
                  StatusCodes.NotFound,
                  ErrorResponse(
                    "CODE_NOT_FOUND",
                    s"Code for survey with ID $surveyId not found"
                  )
                )
            }
          }
        }
      },
      // POST /code/activate
      path("activate") {
        post {
          entity(as[ActivateCodeRequest]) { request =>
            onSuccess(codeRepo.findById(request.codeId)) {
              case None =>
                complete(
                  StatusCodes.BadRequest,
                  ErrorResponse(
                    "INVALID_PROMO_CODE",
                    "Promo code is invalid or already used"
                  )
                )
              case Some(code) =>
                if (code.isUsed) {
                  complete(
                    StatusCodes.BadRequest,
                    ErrorResponse(
                      "INVALID_PROMO_CODE",
                      "Promo code is invalid or already used"
                    )
                  )
                } else {
                  onSuccess(codeRepo.markCodeUsed(code.id)) { _ =>
                    complete(StatusCodes.OK, SuccessResponse(success = true))
                  }
                }
            }
          }
        }
      }
    )
  }
}
