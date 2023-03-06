package com.rockthejvm.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}


// a single bank account
object PersistentBankAccount {

  /**
   * We used to store latest state of data in database but
   * In EventSourcing, We store events that's the bits of data which comprised the journey to the latest state of data
   * at this moment.
   * We obtain the latest state of application by replaying all these events one by one.
   *
   * ------ fault tolerance
   * If the system crashed or server crashed or if an actor fails or anything bad happens, We will have the entire
   * journey to the latest state of the application written in our database.
   * So, it's no biggie to simply replay all these events and get back to the state.
   *
   * ----- auditing
   * If we get a suspicious transaction that we didn't notice at some point in past, We can always revisit that and
   * figure out if something went wrong at that time and do something about it even though that transaction or the
   * deposit/withdrawal occurred earlier in time.
   *
   * Fault tolerance and Auditing are two reason why we use event sourcing.
   */

  // Persistent actors.

  // Commands = messages,
    sealed trait Command

    /** akka.actor.ActorRef does not take type parameters */
    object Command{
      case class CreateBankAccount(user: String,
                                   currency: String,
                                   initialBalance: Double,
                                   replyTo: ActorRef[Response]) extends Command

      case class UpdateBalance(id: String,
                               currency: String,
                               amount: Double /* can be < 0*/ ,
                               replyTo: ActorRef[Response]) extends Command

      case class GetBankAccount(id: String,
                                replyTo: ActorRef[Response]) extends Command

    }

  // Events = DS to persist to cassandra,
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double) extends Event

  // State = Internal state of bankAcc,
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // Responses = That we want to send back to who ever queries/wants to modify bank account
  sealed trait Response

  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }
  // A persistent actor has command/message handler => persist an event
  // Events after being persisted to persistent store(cassandra) will be subjected to this event handler => update state
  // Then that updated state will be used on next command/message that will be received by persistent actor.

  // Function from state and command/message, we will produce an Effect that take 2 type args[event, state]
  /** The main bank actor will instantiate this persistent actor by calling apply
   * Before this account receives any messages, it needs to be created by the bank.
   * Upon creation of this account, the bank will send it a CreateBankAccount message.
   * Upon reception of the command from the main bank actor, the account will store a BankAccountCreated event to Cassandra.
   * After storing the event, the event handler will be invoked to update the state with id, user, currency, initialBalance.
   * The account will then reply back to the bank actor with a BankAccountCreatedResponse.
   * The bank will then surface the response to the HTTP layer.
   */

  import Command._
  import Response._

  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, bank) =>
        val id = state.id
        Effect.persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance)))// persisted into cassandra
        .thenReply(bank)(_ => BankAccountCreatedResponse(id))
      case UpdateBalance(_, _, amount, bank) =>
        val newBalance = state.balance + amount
        if(newBalance < 0)
          Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(bank)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
      case GetBankAccount(_, bank) =>
        Effect.reply(bank)(GetBankAccountResponse(Some(state)))
    }
  /** This is a function from Command ie: the message that the actor has just received with current state(BankAccount)
   * of the actor at this time and this will produce an effect which has type an event and the same state*/

  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) =>
        bankAccount // returning a new bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }
  /** This is a function from state and event that has just been persisted and it produces new/updated state */

  def apply(id: String): Behavior[Command] =
    /** Behavior is message handler defined akka.typed library. This actor is defined in terms of Behavior
      * which receives these Commands/messages */
    EventSourcedBehavior[Command, Event, BankAccount] (
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // will be unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
