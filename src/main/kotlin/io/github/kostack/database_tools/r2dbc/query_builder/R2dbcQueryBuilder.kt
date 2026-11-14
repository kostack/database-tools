package io.github.kostack.database_tools.r2dbc.query_builder

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * Entry point of the typed join query API; auto-configured when an [R2dbcEntityTemplate] bean exists.
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
class R2dbcQueryBuilder(
  template: R2dbcEntityTemplate
) {
  private val joinQuery = JoinQueryEngine(template)

  fun from(table: EntityTable<*>): JoinQueryBuilder = JoinQueryBuilder(joinQuery, JoinSpec.from(table), emptyList())
}
