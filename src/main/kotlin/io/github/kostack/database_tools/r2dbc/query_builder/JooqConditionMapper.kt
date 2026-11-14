package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.query_mapper.Aggregate
import io.github.kostack.database_tools.query_mapper.QueryMapper.MappedField
import io.github.kostack.database_tools.query_mapper.QueryOperator
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.data.core.TypeInformation
import org.springframework.data.r2dbc.convert.R2dbcConverter
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity

/**
 * Maps `@QueryField` filters and typed column conditions to jOOQ conditions.
 *
 * Column names are resolved through the target's entity (property name -> column) and values are
 * converted to the property type with the R2DBC [converter], as Spring Data's own `QueryMapper` does.
 * All values are bind parameters.
 */
internal class JooqConditionMapper(
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

  /** Applies the operator of [mapped] to [left] (a column or an aggregate subquery); see the other overload. */
  fun compare(
    left: Field<Any?>,
    mapped: MappedField,
    type: TypeInformation<*>? = null
  ): Condition = compare(left, mapped.field.operator, mapped.value, type, "QueryField ${mapped.propertyName}")

  /**
   * `left <operator> value`. Values are converted to [type], or to their own type when it is `null`;
   * [owner] names the source in error messages.
   */
  fun compare(
    left: Field<Any?>,
    operator: QueryOperator,
    value: Any?,
    type: TypeInformation<*>?,
    owner: String
  ): Condition {
    fun bound(): Field<Any?> = param(convert(requireNotNull(value) { "$owner: $operator needs a value" }, type))

    fun boundAll(): List<Field<Any?>> {
      val values =
        value as? Collection<*>
          ?: throw IllegalArgumentException("$owner: $operator requires a Collection value")
      return values.filterNotNull().map { param(convert(it, type?.actualType)) }
    }

    fun like(
      pattern: (String) -> String,
      ignoreCase: Boolean = false
    ): Condition {
      val text = value as? String ?: throw IllegalArgumentException("$owner: $operator requires a String value")
      val boundPattern = param(pattern(text)).text()
      return if (ignoreCase) DSL.upper(left.text()).like(DSL.upper(boundPattern)) else left.like(boundPattern)
    }

    return when (operator) {
      QueryOperator.EQ -> left.eq(bound())

      QueryOperator.NE -> left.ne(bound())

      QueryOperator.GT -> left.gt(bound())

      QueryOperator.GTE -> left.ge(bound())

      QueryOperator.LT -> left.lt(bound())

      QueryOperator.LTE -> left.le(bound())

      QueryOperator.CONTAINS -> like(::containsPattern)

      QueryOperator.CONTAINS_IGNORE_CASE -> like(::containsPattern, ignoreCase = true)

      QueryOperator.STARTS_WITH -> like(::startsWithPattern)

      QueryOperator.STARTS_WITH_IGNORE_CASE -> like(::startsWithPattern, ignoreCase = true)

      QueryOperator.ENDS_WITH -> like(::endsWithPattern)

      QueryOperator.ENDS_WITH_IGNORE_CASE -> like(::endsWithPattern, ignoreCase = true)

      QueryOperator.IN -> left.`in`(boundAll())

      QueryOperator.NOT_IN -> left.notIn(boundAll())

      QueryOperator.EXISTS, QueryOperator.IS_NOT_NULL -> left.isNotNull

      QueryOperator.NOT_EXISTS, QueryOperator.IS_NULL -> left.isNull

      QueryOperator.REGEX, QueryOperator.REGEX_IGNORE_CASE -> throw UnsupportedOperationException(
        "$operator is not supported by SQL queries. Use CONTAINS / STARTS_WITH / ENDS_WITH instead."
      )
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
