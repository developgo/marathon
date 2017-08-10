package mesosphere.marathon
package api.v2.validation

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ App, Apps, Constraint, ConstraintOperator, PodPlacementPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy }
import mesosphere.marathon.state.ResourceRole

import scala.util.Try

trait SchedulingValidation {
  import Validation._
  import SchedulingValidationMessages._

  val backoffStrategyValidator = validator[PodSchedulingBackoffStrategy] { bs =>
    bs.backoff should be >= 0.0
    bs.backoffFactor should be >= 0.0
    bs.maxLaunchDelay should be >= 0.0
  }

  def isSingleInstance(app: App): Boolean = app.labels.get(Apps.LabelSingleInstanceApp).contains("true")

  val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import mesosphere.marathon.raml.ConstraintOperator._
    override def apply(c: Constraint): Result = {
      def failure(constraintViolation: String, description: Option[String] = None) =
        Failure(Set(RuleViolation(c, constraintViolation, description)))
      if (c.fieldName.isEmpty) {
        failure(ConstraintRequiresField)
      } else {
        c.operator match {
          case Unique =>
            c.value.fold[Result](Success) { _ => failure(ConstraintUniqueDoesNotAcceptValue) }
          case Cluster =>
            // value is completely optional for CLUSTER
            Success
          case GroupBy =>
            if (c.value.fold(true)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              failure(ConstraintGroupByMustBeEmptyOrInt)
            }
          case MaxPer =>
            if (c.value.fold(false)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              failure(ConstraintMaxPerRequiresInt)
            }
          case Like | Unlike =>
            c.value.fold[Result] {
              failure(ConstraintLikeAnUnlikeRequireRegexp)
            } { p =>
              Try(Pattern.compile(p)) match {
                case util.Success(_) => Success
                case util.Failure(e) => failure(InvalidRegularExpression, Option(e.getMessage))
              }
            }
        }
      }
    }
  }

  val placementStrategyValidator = validator[PodPlacementPolicy] { ppp =>
    ppp.acceptedResourceRoles.toSet is empty or ResourceRole.validAcceptedResourceRoles(false) // TODO(jdef) assumes pods!! change this to properly support apps
    ppp.constraints is empty or every(complyWithConstraintRules)
  }

  val schedulingValidator = validator[PodSchedulingPolicy] { psp =>
    psp.backoff is optional(backoffStrategyValidator)
    psp.placement is optional(placementStrategyValidator)
  }

  val complyWithAppConstraintRules: Validator[Seq[String]] = new Validator[Seq[String]] {
    def failureIllegalOperator(c: Any) = Failure(Set(RuleViolation(c, ConstraintOperatorInvalid, None)))

    override def apply(c: Seq[String]): Result = {
      def badConstraint(reason: String, desc: Option[String] = None): Result =
        Failure(Set(RuleViolation(c, reason, desc)))
      if (c.length < 2 || c.length > 3) badConstraint("Each constraint must have either 2 or 3 fields")
      else (c.headOption, c.lift(1), c.lift(2)) match {
        case (None, None, _) =>
          badConstraint("Missing field and operator")
        case (Some(field), Some(op), value) if field.nonEmpty =>
          ConstraintOperator.fromString(op.toUpperCase) match {
            case Some(operator) =>
              // reuse the rules from pod constraint validation so that we're not maintaining redundant rule sets
              complyWithConstraintRules(Constraint(fieldName = field, operator = operator, value = value))
            case _ =>
              failureIllegalOperator(c)
          }
        case _ =>
          badConstraint(IllegalConstraintSpecification)
      }
    }
  }
}

object SchedulingValidation extends SchedulingValidation

object SchedulingValidationMessages {
  val ConstraintRequiresField = "missing field for constraint declaration"
  val InvalidRegularExpression = "is not a valid regular expression"
  val ConstraintLikeAnUnlikeRequireRegexp = "LIKE and UNLIKE require a non-empty, regular expression value"
  val ConstraintMaxPerRequiresInt = "MAX_PER requires an integer value"
  val ConstraintGroupByMustBeEmptyOrInt = "GROUP BY must define an integer value or else no value at all"
  val ConstraintUniqueDoesNotAcceptValue = "UNIQUE does not accept a value"
  val IllegalConstraintSpecification = "illegal constraint specification"
  val ConstraintOperatorInvalid = "operator must be one of the following UNIQUE, CLUSTER, GROUP_BY, LIKE, MAX_PER or UNLIKE"
}
