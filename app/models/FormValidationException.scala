package models

import play.api.data.Form

case class FormValidationException[T](form: Form[T]) extends Exception(form.errors.mkString)
