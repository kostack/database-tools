package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.QueryMapper.MappedField
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.data.core.TypeInformation
import org.springframework.data.r2dbc.convert.R2dbcConverter
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.CriteriaDefinition.Combinator
import org.springframework.data.relational.core.query.CriteriaDefinition.Comparator
import org.springframework.data.util.Pair

/**
 * Maps `@QueryField` filters and Spring [CriteriaDefinition]s to jOOQ conditions.
 *
 * Column names are resolved through the target's entity (property name -> column) and values are
 * converted to the property type with the R2DBC [converter], as Spring Data's own `QueryMapper` does.
 * All values are bind parameters.
 */
class JooqConditionMapper(
  private val converter: R2dbcConverter
) {
  /** The table conditions refer to: its entity (column names, value types) and how to reference its columns. */
  class Target(
    val entity: RelationalPersistentEntity<*>,
    val column: (String) -> Field<Any?>
  )

  /** A plain (non-aggregate) `@QueryField` filter on a column of [target]. */
  fun map(
    mapped: MappedField,
    target: Target
  ): Condition {
    require(mapped.field.aggregate == Aggregate.NONE) {
      "QueryField ${mapped.propertyName}: aggregates are compared with compare(), not mapped to a column"
    }
    val type = target.entity.getPersistentProperty(mapped.fieldName)?.typeInformation
    return compare(target.column(mapped.fieldName), mapped, type)
  }

  /**
   * Applies the operator of [mapped] to [left] (a column or an aggregate subquery).
   * Values are converted to [type], or to their own type when it is `null`.
   */
  fun compare(
    left: Field<Any?>,
    mapped: MappedField,
    type: TypeInformation<*>? = null
  ): Condition {
    val operator = mapped.field.operator
    val owner = "QueryField ${mapped.propertyName}"

    fun value(): Field<Any?> = param(convert(requireNotNull(mapped.value) { "$owner: $operator needs a value" }, type))

    fun values(): List<Field<Any?>> {
      val values =
        mapped.value as? Collection<*>
          ?: throw IllegalArgumentException("$owner: $operator requires a Collection value")
      return values.filterNotNull().map { param(convert(it, type?.actualType)) }
    }

    fun like(
      pattern: (String) -> String,
      ignoreCase: Boolean = false
    ): Condition {
      val text = mapped.value as? String ?: throw IllegalArgumentException("$owner: $operator requires a String value")
      val bound = param(pattern(text)).text()
      return if (ignoreCase) DSL.upper(left.text()).like(DSL.upper(bound)) else left.like(bound)
    }

    return when (operator) {
      QueryOperator.EQ -> left.eq(value())

      QueryOperator.NE -> left.ne(value())

      QueryOperator.GT -> left.gt(value())

      QueryOperator.GTE -> left.ge(value())

      QueryOperator.LT -> left.lt(value())

      QueryOperator.LTE -> left.le(value())

      QueryOperator.CONTAINS -> like(::containsPattern)

      QueryOperator.CONTAINS_IGNORE_CASE -> like(::containsPattern, ignoreCase = true)

      QueryOperator.STARTS_WITH -> like(::startsWithPattern)

      QueryOperator.STARTS_WITH_IGNORE_CASE -> like(::startsWithPattern, ignoreCase = true)

      QueryOperator.ENDS_WITH -> like(::endsWithPattern)

      QueryOperator.ENDS_WITH_IGNORE_CASE -> like(::endsWithPattern, ignoreCase = true)

      QueryOperator.IN -> left.`in`(values())

      QueryOperator.NOT_IN -> left.notIn(values())

      QueryOperator.EXISTS, QueryOperator.IS_NOT_NULL -> left.isNotNull

      QueryOperator.NOT_EXISTS, QueryOperator.IS_NULL -> left.isNull

      QueryOperator.REGEX, QueryOperator.REGEX_IGNORE_CASE -> throw UnsupportedOperationException(
        "$operator is not supported by SQL queries. Use CONTAINS / STARTS_WITH / ENDS_WITH instead."
      )
    }
  }

  /**
   * Maps Spring criteria on [target], following Spring's R2DBC `QueryMapper` (chains, groups, `ignoreCase` as `UPPER`).
   * Returns `null` for empty criteria.
   */
  fun map(
    criteria: CriteriaDefinition,
    target: Target
  ): Condition? = unroll(criteria, target)

  /** A criteria chain links back to its predecessors: walk to the head, then combine forwards. */
  private fun unroll(
    criteria: CriteriaDefinition,
    target: Target
  ): Condition? =
    generateSequence(criteria) { if (it.hasPrevious()) it.previous else null }
      .toList()
      .asReversed()
      .fold(null as Condition?) { mapped, next -> combine(mapped, next, condition(next, target)) }

  private fun condition(
    criteria: CriteriaDefinition,
    target: Target
  ): Condition? =
    when {
      criteria.isEmpty -> {
        null
      }

      criteria.isGroup -> {
        criteria.group
          .filterNot { it.isEmpty }
          .fold(null as Condition?) { mapped, next -> combine(mapped, next, unroll(next, target)) }
      }

      else -> {
        predicate(criteria, target)
      }
    }

  private fun combine(
    mapped: Condition?,
    criteria: CriteriaDefinition,
    condition: Condition?
  ): Condition? =
    when {
      condition == null -> mapped
      mapped == null -> condition
      criteria.combinator == Combinator.OR -> mapped.or(condition)
      else -> mapped.and(condition)
    }

  private fun predicate(
    criteria: CriteriaDefinition,
    target: Target
  ): Condition {
    val name = requireNotNull(criteria.column) { "Criteria has no column" }.reference
    val type: TypeInformation<*> = target.entity.getPersistentProperty(name)?.typeInformation ?: TypeInformation.OBJECT
    val elementType: TypeInformation<*> = type.actualType ?: TypeInformation.OBJECT
    val column = target.column(name)
    val ignoreCase = criteria.isIgnoreCase
    val compared = if (ignoreCase) DSL.upper(column.text()).any() else column

    fun upper(field: Field<Any?>): Field<Any?> = if (ignoreCase) DSL.upper(field.text()).any() else field

    fun value(upper: Boolean = false): Field<Any?> =
      param(convert(criteria.value, type)).let { if (upper) upper(it) else it }

    fun values(): List<Field<Any?>> =
      when (val value = criteria.value) {
        is Iterable<*> -> value.map { param(convert(it, elementType)) }
        else -> listOf(param(convert(value, type)))
      }

    fun range(): kotlin.Pair<Field<Any?>, Field<Any?>> {
      @Suppress("UNCHECKED_CAST")
      val pair = requireNotNull(criteria.value as? Pair<Any, Any>) { "${criteria.comparator} on '$name' needs a Pair" }
      return upper(param(convert(pair.first, elementType))) to upper(param(convert(pair.second, elementType)))
    }

    return when (criteria.comparator) {
      Comparator.EQ -> compared.eq(value(upper = true))
      Comparator.NEQ -> compared.ne(value(upper = true))
      Comparator.LT -> column.lt(value())
      Comparator.LTE -> column.le(value())
      Comparator.GT -> column.gt(value())
      Comparator.GTE -> column.ge(value())
      Comparator.LIKE -> compared.like(value(upper = true).text())
      Comparator.NOT_LIKE -> compared.notLike(value(upper = true).text())
      Comparator.IN -> compared.`in`(values())
      Comparator.NOT_IN -> compared.notIn(values())
      Comparator.IS_NULL -> column.isNull
      Comparator.IS_NOT_NULL -> column.isNotNull
      Comparator.IS_TRUE, Comparator.IS_FALSE -> column.eq(value())
      Comparator.BETWEEN -> range().let { (low, high) -> compared.between(low, high) }
      Comparator.NOT_BETWEEN -> range().let { (low, high) -> compared.notBetween(low, high) }
      Comparator.INITIAL, null -> throw IllegalArgumentException("Criteria on '$name' has no comparator")
    }
  }

  /** Converts [value] for the database: to [type] when known, otherwise by its own type. */
  private fun convert(
    value: Any?,
    type: TypeInformation<*>?
  ): Any? = value?.let { converter.writeValue(it, type ?: TypeInformation.of(it.javaClass)) ?: it }

  private companion object {
    /** Escapes LIKE wildcards; backslash is the default escape character of MySQL, H2 and PostgreSQL. */
    fun escapeLike(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    fun containsPattern(value: String): String = "%${escapeLike(value)}%"

    fun startsWithPattern(value: String): String = "${escapeLike(value)}%"

    fun endsWithPattern(value: String): String = "%${escapeLike(value)}"

    fun param(value: Any?): Field<Any?> = DSL.value(value).any()

    @Suppress("UNCHECKED_CAST")
    fun Field<*>.any(): Field<Any?> = this as Field<Any?>

    @Suppress("UNCHECKED_CAST")
    fun Field<*>.text(): Field<String> = this as Field<String>
  }
}
