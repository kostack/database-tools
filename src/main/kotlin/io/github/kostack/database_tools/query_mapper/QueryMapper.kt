package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

abstract class QueryMapper<C> {
  data class MappedField(
    val fieldName: String,
    val propertyName: String,
    val value: Any?,
    val field: QueryField
  )

  fun toCriteriaList(source: Any): List<C> =
    toTableCriteriaList(source).map { (table, criteria) ->
      require(table.isEmpty()) {
        "QueryField.table ('$table') on ${source::class.simpleName} is only supported by GridQueryBuilder"
      }
      criteria
    }

  fun toTableCriteriaList(source: Any): List<Pair<String, C>> =
    activeFields(source).map { mapped ->
      require(mapped.field.aggregate == Aggregate.NONE) {
        "QueryField.aggregate on ${source::class.simpleName}.${mapped.propertyName} is only supported by GridQueryBuilder"
      }
      mapped.field.table to toCriteria(mapped)
    }

  /** The `@QueryField` properties of [source] that are not skipped (see [QueryField.ignoreEmpty]). */
  fun activeFields(source: Any): List<MappedField> = Companion.activeFields(source)

  fun toCriteria(mapped: MappedField): C =
    buildCriteria(
      fieldName = mapped.fieldName,
      propertyName = mapped.propertyName,
      value = mapped.value,
      field = mapped.field
    )

  protected abstract fun buildCriteria(
    fieldName: String,
    propertyName: String,
    value: Any?,
    field: QueryField
  ): C

  protected fun shouldSkip(
    value: Any?,
    field: QueryField
  ): Boolean = Companion.shouldSkip(value, field)

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

  companion object {
    /**
     * The `@QueryField` properties of [source] that are not skipped (see [QueryField.ignoreEmpty]),
     * independent of the target query type (used by the Mongo mapper and by `GridQueryBuilder`).
     */
    fun activeFields(source: Any): List<MappedField> =
      source::class.memberProperties.mapNotNull { property ->
        val field =
          property.javaField?.getAnnotation(QueryField::class.java)
            ?: return@mapNotNull null

        @Suppress("UNCHECKED_CAST")
        val value = (property as KProperty1<Any, *>).get(source)

        if (shouldSkip(value, field)) {
          return@mapNotNull null
        }

        MappedField(
          fieldName = field.name.ifBlank { property.name },
          propertyName = property.name,
          value = value,
          field = field
        )
      }

    internal fun shouldSkip(
      value: Any?,
      field: QueryField
    ): Boolean {
      if ((value == null || (value is String && value.isBlank())) && field.ignoreEmpty) {
        return true
      }

      return value is Collection<*> &&
        value.isEmpty() &&
        field.operator in listOf(QueryOperator.IN, QueryOperator.NOT_IN)
    }
  }
}
