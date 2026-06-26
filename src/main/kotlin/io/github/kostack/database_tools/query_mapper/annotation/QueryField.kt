package io.github.kostack.database_tools.query_mapper.annotation

import io.github.kostack.database_tools.query_mapper.QueryOperator

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryField(
  /**
   * DB field name / SQL column name or alias.
   * If empty, the Kotlin property name is used.
   */
  val name: String = "",
  val operator: QueryOperator = QueryOperator.EQ,
  /**
   * Skip blank String and null values.
   */
  val ignoreEmpty: Boolean = true
)
