package io.github.kostack.database_tools.r2dbc.grid

import io.github.kostack.database_tools.r2dbc.grid.dsl.EntityTable
import io.github.kostack.database_tools.r2dbc.grid.engine.JoinQueryEngine
import io.github.kostack.database_tools.r2dbc.grid.engine.JoinSpec
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * Entry point for grid queries: filtered, sorted and paged rows over one root table and its joined tables.
 * Auto-configured when an [R2dbcEntityTemplate] bean exists.
 *
 * ```kotlin
 * val customer = table<Customer>("c")
 * val order = table<Order>("o")
 *
 * val page = queryBuilder
 *   .from(customer)
 *   .leftJoin(order)
 *   .on(customer[Customer::id] eq order[Order::customerId])
 *   .fetchPage<CustomerGridDto>(filter = filter, pageable = pageable)
 * ```
 */
class GridQueryBuilder(
  template: R2dbcEntityTemplate
) {
  private val joinQuery = JoinQueryEngine(template)

  fun from(table: EntityTable<*>): GridQuery = GridQuery(joinQuery, JoinSpec.from(table), emptyList())
}
