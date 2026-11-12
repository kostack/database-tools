package io.github.kostack.database_tools.query_mapper.annotation

import io.github.kostack.database_tools.query_mapper.Aggregate
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
  val ignoreEmpty: Boolean = true,
  /**
   * Alias of the joined table this column belongs to (see `JoinSpec`).
   * Empty = root table. Only supported by join queries; single-table mappers reject it.
   */
  val table: String = "",
  /**
   * Compare an aggregate of [name] in the [table] instead of the column itself
   * (e.g. `AVG(o.total) >= value`). [table] must be a `JoinMode.AGGREGATE` alias;
   * `COUNT` with an empty [name] counts rows. Only supported by join queries.
   */
  val aggregate: Aggregate = Aggregate.NONE
)
