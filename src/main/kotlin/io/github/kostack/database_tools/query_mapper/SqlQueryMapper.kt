package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query

object SqlQueryMapper : QueryMapper<Criteria>() {
  /**
   * Maps the `@QueryField` properties of [source] into a query.
   *
   * The result is [base] with its criteria ANDed as `required AND (base criteria) AND mapped...`.
   * Sort, limit, offset and columns of [base] are preserved. Each part is grouped,
   * so an `OR` inside [required] or the base criteria keeps its precedence.
   */
  fun toQuery(
    source: Any,
    base: Query = Query.empty(),
    required: CriteriaDefinition? = null
  ): Query = merge(base, required, toCriteriaList(source))

  /** Returns [base] with [criteria] ANDed in front of its existing criteria. Sort, limit, offset and columns are kept. */
  fun and(
    base: Query,
    criteria: CriteriaDefinition
  ): Query = merge(base, criteria, emptyList())

  private fun merge(
    base: Query,
    required: CriteriaDefinition?,
    mapped: List<CriteriaDefinition>
  ): Query {
    if (required == null && mapped.isEmpty()) {
      return base
    }

    val combined =
      combine(listOfNotNull(required, base.criteria.orElse(null)) + mapped)
        ?: return base

    return Query.query(combined).withModifiersOf(base)
  }

  /**
   * Maps the `@QueryField` properties of [source] grouped by [QueryField.table] (root = empty alias),
   * each group ANDed into one criteria. Field order is preserved.
   */
  fun toCriteriaByTable(source: Any): Map<String, Criteria> =
    toTableCriteriaList(source)
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, criteria) -> combine(criteria)!! }

  /** ANDs [parts] in order, grouping each so an inner `OR` keeps its precedence. Empty criteria are dropped. */
  internal fun combine(parts: List<CriteriaDefinition>): Criteria? =
    parts
      .filterNot { it is Criteria && it.isEmpty }
      .takeIf { it.isNotEmpty() }
      ?.fold(Criteria.empty()) { acc, next ->
        if (acc.isEmpty) group(next) else acc.and(next)
      }

  private fun group(definition: CriteriaDefinition): Criteria =
    if (definition is Criteria && !definition.hasPrevious()) {
      definition
    } else {
      Criteria.empty().and(definition)
    }

  private fun Query.withModifiersOf(base: Query): Query {
    var query = this
    if (base.isSorted) query = query.sort(base.sort)
    if (base.isLimited) query = query.limit(base.limit)
    if (base.offset >= 0) query = query.offset(base.offset)
    if (base.columns.isNotEmpty()) query = query.columns(*base.columns.toTypedArray())
    return query
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
