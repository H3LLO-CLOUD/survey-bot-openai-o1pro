# How This Code Was Generated

There were three main prompts, plus some minor adjustment instructions. They align with the development flow:

1. **OpenAPI Specification Generation**
2. **Complete Scala Application Code Generation**
3. **Compilation Error Fixes**

---

## OpenAPI Specification

The initial step was to produce an OpenAPI specification that would serve as a blueprint for the application’s functionality, protocols, data structures, and overall architecture. This specification is then used to guide the main code generation prompt.

**Prompt Used:**

```prompt
Write an OpenAPI specification for REST API of Survey service. The purpose of this service is
provide back-end for Survey Telegram Mini App. In this app users can answer a question of
survey and get a reward — promo-code, which can be activated in service and take discounts.
Methods:
- POST /user - Create new user
- GET /survey - List Active Surveys, available for User, according to Display Survey Logic. Return
fields:id, title, description, progress. Progress is a calculated field for user.
- GET /survey/{surveyId}/ - View surveys fields, including questions and answer variants with
users answers (if provided).
- POST /survey/{surveyId}/answer - Accept users answer for Surveys question in JSON format
with two fields: questionId and answerVariantId.
- GET /code/{surveyId} - View promo-code, given to user as reward for completeion of survey.
- POST /code/activate - Method for external service to check promo-code validity and mark it as
used (if vlid) or return an error, if not valid.
Data Structures (Complete):
- User: id, firstName, lastName, languageCode, profilePicture, username, allowsWriteToPM,
creationDate
- Surevey: id, title, description, creationDate, isActive, codeValidEndDate
- Questions: id, surveyId, title, description, index
- AnswerVariants: id, questionId, lable, value
- Answers: id, userId, questionId, answerId, timestamp
- Codes: id, userId, surveyId, isUsed
Display Survey Logic:
There are many surveys in system. Some of the can be not Active (isActive: false). Application
show to user all active surveys, and only not active surveys satisfying following conditions:
- user completed this survey
- AND user’s code for this survey is not used.
For active Surveys Progress fields shows percents of answered questions as Int value: 0 for new
(not touched) survey, 100 for completed survey.
```

### Key Elements of the Prompt

1. **Endpoints** – A concise overview of all API methods and their use cases.
2. **Data Structures** – A detailed description of available objects and their fields.
3. **Logic** – A clear definition of the business logic for specific methods.

From this, ChatGPT generated a YAML-based OpenAPI specification (`openapi.yaml`).

### Correction Commands

The specification didn’t appear perfectly on the first try. The model initially produced _dummy_ examples, such as:

```json

{
"id": 12345,
"firstName": "string",
"lastName": "string",
"languageCode": "string",
"profilePicture": "string",
"username": "string",
"allowsWriteToPM": true,
"creationDate": "string"
}

```

So, we corrected it with instructions like:

```prompt
Write more concrete examples, not so dummy. Include examples for error responses.
```

Eventually, we arrived at more realistic examples suitable for testing:

```json
{
"id": "12345",
"firstName": "John",
"lastName": "Doe",
"languageCode": "en-EN",
"profilePicture": "https://cdn.example.com/users/john_doe.png",
"username": "johndoe",
"allowsWriteToPM": true,
"creationDate": "2025-01-01T10:00:00Z"
}
```

Further fine-tuning included adding error responses and restructuring certain parts:

```prompt
Include 409 (Conflict) response for request of creation user with existing id
```

and

```prompt
Move userId to header parameters
```

This wrapped up the OpenAPI Specification stage. The next move was to generate the actual application code.

---

## Code Generation

A fresh ChatGPT session was initiated with a prompt along these lines:

```prompt
Write complete code of Scala 3 webserver based on Pekko HTTP with Slick PostgreSQL, providing REST API for Telegram Survey Mini App and satisfying following OpenAPI specification: ...our OpenAPI spec here...
```


The prompt contained comprehensive requirements, specifying how the code should be structured and the acceptance criteria.

The model produced example files such as:
- A sample `build.sbt`
- A `Main.scala` file containing most of the logic
- Occasionally, an `application.conf` file

Naturally, the generated code did not compile on the first attempt—this is an expected part of the process.

---

## Compilation Error Fixes

Staying in the same chat thread, we presented the compiler errors with prompts like:

```prompt
I got 5 errors and 12 warnings. Fix it: ...full compilation log here...                                          
```

for example:

```prompt
I got 5 errors and 12 warnings. Fix it: sbt:survey-bot> compile
[info] compiling 1 Scala source to /Users/km/Development/llm-test/survey-bot-openai-o1pro/target/scala-3.6.3/classes ...
[error] -- [E172] Type Error: /Users/km/Development/llm-test/survey-bot-openai-o1pro/src/main/scala/ru/h3llo/surveybot/Main.scala:132:92 
[error] 132 |  implicit val userFormat: RootJsonFormat[User]                          = jsonFormat8(User)
[error]     |                                                                                            ^
[error]     | Cannot find JsonWriter or JsonFormat type class for java.time.Instant
[error] -- [E007] Type Mismatch Error: /Users/km/Development/llm-test/survey-bot-openai-o1pro/src/main/scala/ru/h3llo/surveybot/Main.scala:138:87 
[error] 138 |  implicit val surveyDetailFormat: RootJsonFormat[SurveyDetail]          = jsonFormat6(SurveyDetail.apply) /* then manually add 'questions' */
[error]     |                                                                                       ^^^^^^^^^^^^^^^^^^
[error]     |Found:    (String, String, String, java.time.Instant, Boolean, Option[java.time.Instant],
[error]     |  Seq[ru.h3llo.surveybot.QuestionDetail]) => ru.h3llo.surveybot.SurveyDetail
[error]     |Required: (Any, Any, Any, Any, Any, Any) => ru.h3llo.surveybot.SurveyDetail
[error]     |
[error]     |One of the following imports might make progress towards fixing the problem:
[error]     |
[error]     |  import org.apache.pekko.http.impl.util.JavaMapping.Implicits.convertToScala
[error]     |  import org.apache.pekko.http.javadsl.server.RoutingJavaMapping.Implicits.convertToScala
[error]     |
[error]     |
[error]     | longer explanation available when compiling with -explain
[error] -- [E172] Type Error: /Users/km/Development/llm-test/survey-bot-openai-o1pro/src/main/scala/ru/h3llo/surveybot/Main.scala:143:100 
[error] 143 |  override implicit val surveyDetailFormat: RootJsonFormat[SurveyDetail] = jsonFormat7(SurveyDetail)
[error]     |                                                                                                    ^
[error]     | Cannot find JsonWriter or JsonFormat type class for java.time.Instant
...
...
... and so on...
```


In response, ChatGPT rewrote `Main.scala`. Notably, the package name was updated from `com.example.survey` to `ru.h3llo.surveybot`, which matched our manual changes.

Recompiling gave us just one error, which we fed back to ChatGPT without any extra explanation:

```prompt
[info] compiling 1 Scala source to /Users/km/Development/llm-test/survey-bot-openai-o1pro/target/scala-3.6.3/classes ...
[error] -- [E007] Type Mismatch Error: /Users/km/Development/llm-test/survey-bot-openai-o1pro/src/main/scala/ru/h3llo/surveybot/Main.scala:496:31 
[error] 496 |              complete(surveys.toList)
[error]     |                       ^^^^^^^^^^^^^^
[error]     |Found:    List[ru.h3llo.surveybot.SurveyListItem]
[error]     |Required: org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
[error]     |Note that implicit conversions cannot be applied because they are ambiguous;
[error]     |both method listFormat in trait CollectionFormats and method seqFormat in trait CollectionFormats match type spray.json.RootJsonWriter[T]
[error]     |
[error]     | longer explanation available when compiling with -explain
[error] one error found
[error] (Compile / compileIncremental) Compilation failed
[error] Total time: 1 s, completed 30 янв. 2025 г., 13:54:45
```


This time, ChatGPT responded with three different suggestions on how to fix the issue. One suggestion was to use a type alias:

```scala
// 3) Use a Type Alias to Disambiguate
//   A slightly cleaner approach is:

// In your protocol or route file:
type SurveyList = List[SurveyListItem]  // or Seq[SurveyListItem], but choose one

implicit val surveyListFormat: RootJsonFormat[SurveyList] = listFormat[SurveyListItem]


// Now in your code:
case Right(surveys) => complete(surveys.toList: SurveyList)
        
// When you do surveys.toList: SurveyList, the compiler sees SurveyList specifically 
// and uses surveyListFormat—no more ambiguity.
```

That tweak resolved the final compilation error, and the application started working as intended. All routes behaved according to the OpenAPI specification we initially outlined.

## Additional Considerations

While the application is functional, there might be room for improvement:

* **Refactoring:** The code could be structured more elegantly, as suggested in the comments.
* **Adding Features:** For instance, a promotional code generation method can be triggered when a user completes a survey.

Despite these enhancements being possible, the main goal—having a functional API for a front-end developer to integrate with—was achieved. The result is a solid project foundation that was largely automated thanks to ChatGPT’s capability to handle repetitive or boilerplate tasks, much like having an intern developer on your team.