
package com.rockthejvm.bank.http
/*
trait Required[A] extends (A => Boolean)

case class Person(name: String, age: Int)

object Example extends App{

  val nonEmptyString: Required[String] = _.nonEmpty
  val positiveInt: Required[Int] = _ > 20

  def validate[A](value: A, required: Required[A]): Boolean = required(value)

  val person = Person("", 10)

  val nameIsValid = validate(person.name, nonEmptyString)
  val ageIsValid = validate(person.age, positiveInt)

  if(!nameIsValid)
    println("Name cannot be empty")

  if(!ageIsValid)
    println("Age cannot be less than 20")
}
*/

trait ValidationFailure1 {
  def errorMessage: String
}

case class NegativeValue1(fieldName: String) extends ValidationFailure1 {
  def errorMessage = s"$fieldName is negative"
}

case class BelowMinimumValue1(fieldName: String, min: Double) extends ValidationFailure1 {
  def errorMessage = s"$fieldName is below the minimum threshold $min"
}
