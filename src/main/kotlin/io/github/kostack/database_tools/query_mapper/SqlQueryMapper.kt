package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query

object SqlQueryMapper : QueryMapper<Criteria>() {
  fun toQuery(source: Any): Query {
    val criteria = toCriteriaList(source)

    if (criteria.isEmpty()) {
      return Query.empty()
    }

    return Query.query(
      criteria.reduce { acc, next -> acc.and(next) }
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
        Criteria.where(fieldName).`is`(value!!)
      }

      QueryOperator.NE -> {
        Criteria.where(fieldName).not(value!!)
      }

      QueryOperator.CONTAINS -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(containsPattern(text))
      }

      QueryOperator.CONTAINS_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(containsPattern(text)).ignoreCase(true)
      }

      QueryOperator.STARTS_WITH -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(startsWithPattern(text))
      }

      QueryOperator.STARTS_WITH_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(startsWithPattern(text)).ignoreCase(true)
      }

      QueryOperator.ENDS_WITH -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(endsWithPattern(text))
      }

      QueryOperator.ENDS_WITH_IGNORE_CASE -> {
        val text = requireString(value, propertyName, field.operator)
        Criteria.where(fieldName).like(endsWithPattern(text)).ignoreCase(true)
      }

      QueryOperator.REGEX,
      QueryOperator.REGEX_IGNORE_CASE -> {
        throw UnsupportedOperationException(
          "${field.operator} is not supported by SqlQueryMapper. Use CONTAINS / STARTS_WITH / ENDS_WITH instead."
        )
      }

      QueryOperator.GT -> {
        Criteria.where(fieldName).greaterThan(value!!)
      }

      QueryOperator.GTE -> {
        Criteria.where(fieldName).greaterThanOrEquals(value!!)
      }

      QueryOperator.LT -> {
        Criteria.where(fieldName).lessThan(value!!)
      }

      QueryOperator.LTE -> {
        Criteria.where(fieldName).lessThanOrEquals(value!!)
      }

      QueryOperator.IN -> {
        val values = requireCollection(value, propertyName, field.operator)
        Criteria.where(fieldName).`in`(values.filterNotNull())
      }

      QueryOperator.NOT_IN -> {
        val values = requireCollection(value, propertyName, field.operator)
        Criteria.where(fieldName).notIn(values.filterNotNull())
      }

      QueryOperator.EXISTS,
      QueryOperator.IS_NOT_NULL -> {
        Criteria.where(fieldName).isNotNull
      }

      QueryOperator.NOT_EXISTS,
      QueryOperator.IS_NULL -> {
        Criteria.where(fieldName).isNull
      }
    }

  private fun escapeLike(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_")

  private fun containsPattern(value: String): String = "%${escapeLike(value)}%"

  private fun startsWithPattern(value: String): String = "${escapeLike(value)}%"

  private fun endsWithPattern(value: String): String = "%${escapeLike(value)}"
}
