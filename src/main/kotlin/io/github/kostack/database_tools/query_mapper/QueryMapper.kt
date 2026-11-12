package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

abstract class QueryMapper<C> {
  /**
   * Maps the `@QueryField` properties of [source] for a single-table query.
   * Fails if any field targets a joined table via [QueryField.table].
   */
  fun toCriteriaList(source: Any): List<C> =
    toTableCriteriaList(source).map { (table, criteria) ->
      require(table.isEmpty()) {
        "QueryField.table ('$table') on ${source::class.simpleName} is only supported by join queries"
      }
      criteria
    }

  /** Maps the `@QueryField` properties of [source] as (table alias, criteria) pairs; root fields have an empty alias. */
  fun toTableCriteriaList(source: Any): List<Pair<String, C>> {
    return source::class.memberProperties.mapNotNull { property ->
      val field =
        property.javaField?.getAnnotation(QueryField::class.java)
          ?: return@mapNotNull null

      @Suppress("UNCHECKED_CAST")
      val typedProperty = property as KProperty1<Any, *>

      val value = typedProperty.get(source)

      if (shouldSkip(value, field)) {
        return@mapNotNull null
      }

      val fieldName = field.name.ifBlank { property.name }

      field.table to
        buildCriteria(
          fieldName = fieldName,
          propertyName = property.name,
          value = value,
          field = field
        )
    }
  }

  protected abstract fun buildCriteria(
    fieldName: String,
    propertyName: String,
    value: Any?,
    field: QueryField
  ): C

  protected fun shouldSkip(
    value: Any?,
    field: QueryField
  ): Boolean {
    if ((value == null || (value is String && value.isBlank())) && field.ignoreEmpty) {
      return true
    }

    if (
      value is Collection<*> &&
      value.isEmpty() &&
      field.operator in listOf(QueryOperator.IN, QueryOperator.NOT_IN)
    ) {
      return true
    }

    return false
  }

  protected fun requireString(
    value: Any?,
    propertyName: String,
    operator: QueryOperator
  ): String {
    require(value is String) {
      "$operator requires String value for property '$propertyName'"
    }

    return value
  }

  protected fun requireCollection(
    value: Any?,
    propertyName: String,
    operator: QueryOperator
  ): Collection<*> {
    require(value is Collection<*>) {
      "$operator requires Collection value for property '$propertyName'"
    }

    return value
  }
}
