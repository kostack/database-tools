package io.github.kostack.database_tools.query_mapper.annotation

import io.github.kostack.database_tools.query_mapper.Aggregate

/**
 * Reads a join-query DTO property from an aggregate over a `JoinMode.AGGREGATE` table,
 * computed per root row, e.g. `(SELECT AVG(o.total) FROM orders o WHERE o.user_id = u.id)`.
 *
 * Plain criteria on that alias (filter fields or `required`) restrict the rows being aggregated.
 * `COUNT` is never `NULL`; `SUM`, `AVG`, `MIN` and `MAX` are `NULL` when there are no rows,
 * so declare those properties nullable.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SelectAggregate(
  /** A `JoinMode.AGGREGATE` alias declared in the `JoinSpec`. */
  val table: String,
  val function: Aggregate,
  /** Entity property or column to aggregate. Empty only for `COUNT` (counts rows). */
  val name: String = ""
)
