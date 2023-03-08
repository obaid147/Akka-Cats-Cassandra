package com.rockthejvm.bank.http

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import com.rockthejvm.bank.actors.PersistentBankAccount.{Command, Response}
import com.rockthejvm.bank.actors.PersistentBankAccount.Command._
import com.rockthejvm.bank.actors.PersistentBankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import cats.data.Validated.{Invalid, Valid}
import com.rockthejvm.bank.http.Validation.{ValidationResult, Validator, validateEntity, validateMinimum, validateRequired}
import cats.implicits._

import scala.concurrent.duration.DurationInt
import scala.concurrent.Future

// This will act as an intermediate actor that will store response from Bank Actor.
case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance,  replyTo)
}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreationRequest] = new Validator[BankAccountCreationRequest] {
    override def validate(request: BankAccountCreationRequest): ValidationResult[BankAccountCreationRequest] = {
      val userValidation = validateRequired(request.user, "user")
      val currencyValidation = validateRequired(request.currency, "currency")
      val balanceValidation = validateMinimum(request.balance, 0, "balance")

      (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreationRequest.apply)
    }
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = (request: BankAccountUpdateRequest) => {
    val currencyValidation = validateRequired(request.currency, "currency")
    val balanceValidation = validateMinimum(request.amount, 0.01, "balance")

    (currencyValidation, balanceValidation).mapN(BankAccountUpdateRequest.apply)
  }
}

case class FailureResponse(reason: String)

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(2.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))


  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request:BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(err) =>
        complete(StatusCodes.BadRequest, FailureResponse(s"${err.toList.map(_.errorMessage).mkString(", ")}"))
    }
    /*
      * POST /bank/
      *   payload: bank account creation request as JSON
      *   Response:
      *     201 Created
      *     Location: /bank/uuid
      *
      * GET /bank/uuid
      *   Response:
      *     200 Ok
      *     JSON representation of bank details
      *     404 Not Found
      *
      * PUT /bank/uuid
      *   payload: newBankAccountDetails, currency and amount as json
      *   response:
      *     1. 200 OK
      *        payload: new bank details as json
      *     2. 404 Not Found
      *     3. 400 Bad Request, if currency cannot be found or something is wrong with amount
      *
      * */

  val routes: Route =
    pathPrefix("bank") {
      pathEndOrSingleSlash { // POST1
        post {
          // parse the payload
          entity(as[BankAccountCreationRequest]) { request =>

            //validation
            validateRequest(request) {
              /*
                - convert request into a Command for the bank actor
                - send command to bank
                - expect reply
              */
              onSuccess(createBankAccount(request)) {
                //- send back HTTP response
                case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
              }
            }
          }
        }
      } ~ path(Segment) { id =>
        get { // GET2
          /*send command to bank
          * expect reply */
          onSuccess(getBankAccount(id)) {
            //* send back http response*/
            case GetBankAccountResponse(Some(account)) =>
            complete(account) // 200 Ok
            case GetBankAccountResponse(None) =>
            complete(StatusCodes.NotFound, FailureResponse(s"BankAccount $id cannot be found"))
          }
        } ~
        put { // PUT3
          entity(as[BankAccountUpdateRequest]) { request =>

            //validation
            validateRequest(request) {
              /*
              - transform req to a Command, case class BankAccountUpdateRequest
              - send the command to bank
              - expect reply*/

              onSuccess(updateBankAccount(id, request)) {
                /*- send bank an HTTP response*/
                case BankAccountBalanceUpdatedResponse(Some(account)) =>
                complete(account)
                case BankAccountBalanceUpdatedResponse(None) =>
                complete(StatusCodes.BadRequest, FailureResponse(s"BankAccount $id cannot be found"))
              }
            }
          }

        }
      }
    }




}
