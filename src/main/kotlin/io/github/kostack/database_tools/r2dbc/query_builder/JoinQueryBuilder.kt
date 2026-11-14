package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.paginator.Pagination
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import kotlin.reflect.KClass

/**
 * An immutable join query: every step returns a new builder. Filters, DTO mapping (`@SelectColumn`,
 * `@SelectAggregate`), sorting and paging are applied by the join query engine.
 */
class JoinQueryBuilder internal constructor(
  private val joinQuery: JoinQueryEngine,
  internal val spec: JoinSpec,
  private val required: List<ColumnCondition>
) {
  /** `JOIN` — rows without a match are dropped. */
  fun join(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.INNER)

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s. */
  fun leftJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.LEFT)

  /** Filter-only semi-join; active only when a filter field or [where] targets [table]. */
  fun existsJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.EXISTS)

  /** Source for `@SelectAggregate` / aggregate filter fields, computed per root row. */
  fun aggregateJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.AGGREGATE)

  /**
   * Fixed conditions, ANDed with each other and placed before the filter fields of their table:
   * `.where(order[Order::status] eq "PAID", user[User::tenantId] eq tenantId)`.
   * On an `existsJoin` table they activate the semi-join; on an `aggregateJoin` table they restrict
   * the rows being aggregated.
   */
  fun where(vararg conditions: ColumnCondition): JoinQueryBuilder {
    conditions.forEach { requireDeclared(it.table) }
    return JoinQueryBuilder(joinQuery, spec, required + conditions)
  }

  /**
   * Selects all [T] rows, e.g. for an export. Sort properties are DTO property names or `alias.property`;
   * `ignoreCase()` orders are supported.
   */
  inline fun <reified T : Any> fetch(
    filter: Any? = null,
    sort: Sort = Sort.unsorted()
  ): Flow<T> = fetch(T::class, filter, sort)

  /** [fetch] with an explicit result type. */
  fun <T : Any> fetch(
    resultType: KClass<T>,
    filter: Any? = null,
    sort: Sort = Sort.unsorted()
  ): Flow<T> = joinQuery.select(spec, resultType, filter, required = required, sort = sort)

  /** One page plus the total count; `ignoreCase()` orders in [pageable] are supported. */
  suspend inline fun <reified T : Any> fetchPage(
    filter: Any? = null,
    pageable: Pageable
  ): Pagination<T> = fetchPage(T::class, filter, pageable)

  /** [fetchPage] with an explicit result type. */
  suspend fun <T : Any> fetchPage(
    resultType: KClass<T>,
    filter: Any? = null,
    pageable: Pageable
  ): Pagination<T> = joinQuery.toPagination(spec, resultType, pageable, filter, required = required)

  /** Counts the rows [fetch] would return. */
  suspend fun count(filter: Any? = null): Long = joinQuery.count(spec, filter, required = required)

  /** A join waiting for its `ON` condition. */
  inner class PendingJoin internal constructor(
    private val table: EntityTable<*>,
    private val mode: JoinMode
  ) {
    /**
     * [condition] must compare a column of the joined table with a column of an already declared table,
     * in either order. [and] adds a condition on the joined table's own columns: in `ON` for `join` / `leftJoin`
     * (for `leftJoin` it keeps root rows without a match, unlike [where]), in the subquery for `existsJoin`
     * (without activating it) and `aggregateJoin`.
     *
     * ```kotlin
     * .leftJoin(order).on(user[User::id] eq order[Order::userId], and = order[Order::status] eq "PAID")
     * ```
     */
    fun on(
      condition: JoinCondition,
      and: ColumnCondition? = null
    ): JoinQueryBuilder {
      require(and == null || and.table.alias == table.alias) {
        "The extra ON condition of '${table.alias}' must use its own columns, not '${and?.table?.alias}'"
      }
      val (parentKey, key) =
        when (table.alias) {
          condition.right.table.alias -> condition.left to condition.right
          condition.left.table.alias -> condition.right to condition.left
          else -> throw IllegalArgumentException("ON of '${table.alias}' must use one of its columns: $condition")
        }
      require(parentKey.table.alias != table.alias) {
        "ON of '${table.alias}' compares two of its own columns: $condition"
      }
      requireDeclared(parentKey.table)
      val parentMode = spec.modeOf(parentKey.table.alias)
      require(parentMode?.joined != false) {
        "ON of '${table.alias}' cannot use $parentMode table '${parentKey.table.alias}'; only the root, join and leftJoin tables"
      }

      val joined = spec.join(JoinSpec.Join(table, mode, parentKey, key, and))
      return JoinQueryBuilder(joinQuery, joined, required)
    }
  }

  private fun requireDeclared(table: EntityTable<*>) {
    val declared = spec.tables[table.alias]
    require(declared != null) { "Table '${table.alias}' is not declared; declared: ${spec.aliases()}" }
    require(declared.type == table.type) {
      "Alias '${table.alias}' is declared as ${declared.type.simpleName}, not ${table.type.simpleName}"
    }
  }
}
