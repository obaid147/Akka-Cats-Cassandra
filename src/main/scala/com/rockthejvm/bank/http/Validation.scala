package com.rockthejvm.bank.http

import cats.data.ValidatedNel
import cats.implicits._


object Validation extends App{

  // validation failures
  trait ValidationFailure {
    def errorMessage: String
  }

  // based on cats.Validated
  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]


  // field must be present
  trait Required[A] extends (A => Boolean)

  // minimum value
  trait Minimum[A] extends ((A, Double) => Boolean) // for numerical fields
  trait MinimumAbs[A] extends ((A, Double) => Boolean) // for numerical fields

  // would be `given` instances in Scala 3
  // Type class instances
  implicit val requiredString: Required[String] = _.nonEmpty
  implicit val minimumInt: Minimum[Int] = _ >= _
  implicit val minimumDouble: Minimum[Double] = _ >= _
  implicit val minimumIntAbs: MinimumAbs[Int] = Math.abs(_) >= _
  implicit val minimumDoubleAbs: MinimumAbs[Double] = Math.abs(_) >= _


  case class EmptyField(fieldName: String) extends ValidationFailure {
    override def errorMessage = s"$fieldName is empty"
  }

  case class NegativeValue(fieldName: String) extends ValidationFailure {
    override def errorMessage = s"$fieldName is negative"
  }

  case class BelowMinimumValue(fieldName: String, min: Double) extends ValidationFailure {
    override def errorMessage = s"$fieldName is below the minimum threshold $min"
  }

  // "main" API

}
