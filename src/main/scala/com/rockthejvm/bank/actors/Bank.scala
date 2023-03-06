package com.rockthejvm.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.util.UUID
import scala.concurrent.ExecutionContext

object Bank {

  // commands = messages
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  import PersistentBankAccount.Command // trait

  // events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  // states
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateBankAccount(_, _, _, _) => // create a bank account
        val id = UUID.randomUUID().toString
        val newBankAccount = context.spawn(PersistentBankAccount(id), id) // create a new child
        Effect
          .persist(BankAccountCreated(id)) // persist event to cassandra
          .thenReply(newBankAccount)(_ => createCommand) // forward command to child actor

      case updateCMD @ UpdateBalance(id, _, _, replyTo) => // update bank account
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCMD) // forward message to bankAccount if exist
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None)) // failed account search
        }
      case getCMD @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCMD)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None)) // failed search
        }
    }

  // event handler, only update states here
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        val account = context.child(id). // exists after commandHandler
          getOrElse(context.spawn(PersistentBankAccount(id), id))// but DOES NOT exist in recovery mode, so needs to be created
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account)) //
    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup{ context =>

    EventSourcedBehavior[Command, Event, State](
    persistenceId = PersistenceId.ofUniqueId("bank"),
    emptyState = State(Map()),
    commandHandler = commandHandler(context),
    eventHandler = eventHandler(context)
    )
  }
}


object BankPlayground {
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  import PersistentBankAccount.Response

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup{ context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log
      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case BankAccountCreatedResponse(id) =>
          logger.info(s"successfully created bank account $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details: $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration.DurationInt
      //import scala.concurrent.ExecutionContext.Implicits.global

      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ex: ExecutionContext = context.executionContext

      //bank ! CreateBankAccount("obaid", "rupees", 800, responseHandler)
      // run this first the check the successfully created bank account ID in cassandra select * from akka.messages;
      // then comment this and uncomment below to run it to check on restart the same persistenceID & the details.

      //bank ! GetBankAccount("b3e50b4b-d955-4e24-b54e-5e648d27a52b", responseHandler)
      //bank ! GetBankAccount("31acf96c-6245-4701-9609-fd5397a2d37c", responseHandler)
      // data was fetched when the app was stopped, So cassandra serves as persistence store.

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "BankDemo")
  }
}
