package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.util.regex.Pattern

object MongoQueryMapper : QueryMapper<Criteria>() {
  fun toQuery(source: Any): Query {
    val criteria = toCriteriaList(source)

    if (criteria.isEmpty()) {
      return Query()
    }

    return Query().addCriteria(
      Criteria().andOperator(*criteria.toTypedArray())
    )
  }

  override fun buildCriteria(
    fieldName: String,
    propertyName: String,
    value: Any?,
    field: QueryField
  ): Criteria =
    when (field.operator) {
      QueryOperator.EQ -> {
        Criteria.where(fieldName).`is`(value)
      }

      QueryOperator.NE -> {
        Criteria.where(fieldName).ne(value)
      }

      QueryOperator.CONTAINS -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(containsPattern(text, ignoreCase = false))
      }

      QueryOperator.CONTAINS_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(containsPattern(text, ignoreCase = true))
      }

      QueryOperator.STARTS_WITH -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(startsWithPattern(text, ignoreCase = false))
      }

      QueryOperator.STARTS_WITH_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(startsWithPattern(text, ignoreCase = true))
      }

      QueryOperator.ENDS_WITH -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(endsWithPattern(text, ignoreCase = false))
      }

      QueryOperator.ENDS_WITH_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(endsWithPattern(text, ignoreCase = true))
      }

      QueryOperator.REGEX -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(
          Pattern.compile(text)
        )
      }

      QueryOperator.REGEX_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).regex(
          Pattern.compile(text, Pattern.CASE_INSENSITIVE)
        )
      }

      QueryOperator.GT -> {
        Criteria.where(fieldName).gt(value!!)
      }

      QueryOperator.GTE -> {
        Criteria.where(fieldName).gte(value!!)
      }

      QueryOperator.LT -> {
        Criteria.where(fieldName).lt(value!!)
      }

      QueryOperator.LTE -> {
        Criteria.where(fieldName).lte(value!!)
      }

      QueryOperator.IN -> {
        val values = requireCollection(value, propertyName, field.operator)
        Criteria.where(fieldName).`in`(values.filterNotNull())
      }

      QueryOperator.NOT_IN -> {
        val values = requireCollection(value, propertyName, field.operator)
        Criteria.where(fieldName).nin(values.filterNotNull())
      }

      QueryOperator.EXISTS -> {
        Criteria.where(fieldName).exists(true)
      }

      QueryOperator.NOT_EXISTS -> {
        Criteria.where(fieldName).exists(false)
      }

      QueryOperator.IS_NULL -> {
        Criteria.where(fieldName).`is`(null)
      }

      QueryOperator.IS_NOT_NULL -> {
        Criteria.where(fieldName).ne(null)
      }
    }

  private fun containsPattern(
    value: String,
    ignoreCase: Boolean
  ): Pattern = compile(Pattern.quote(value), ignoreCase)

  private fun startsWithPattern(
    value: String,
    ignoreCase: Boolean
  ): Pattern = compile("^${Pattern.quote(value)}", ignoreCase)

  private fun endsWithPattern(
    value: String,
    ignoreCase: Boolean
  ): Pattern = compile("${Pattern.quote(value)}$", ignoreCase)

  private fun compile(
    regex: String,
    ignoreCase: Boolean
  ): Pattern =
    if (ignoreCase) {
      Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
    } else {
      Pattern.compile(regex)
    }
}
