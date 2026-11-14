package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.QueryOperator
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * An entity table under an alias. The alias is what `@QueryField(table = …)` and `@SelectColumn(table = …)` refer to.
 *
 * ```kotlin
 * val customer = table<Customer>("c")
 * customer[Customer::id]   // column c.id
 * ```
 */
class EntityTable<E : Any>(
  val type: KClass<E>,
  val alias: String
) {
  operator fun <V> get(property: KProperty1<E, V>): EntityColumn<E, V> = EntityColumn(this, property)

  override fun toString(): String = "${type.simpleName} $alias"
}

/** Declares [E]'s table under [alias]. */
inline fun <reified E : Any> table(alias: String): EntityTable<E> = EntityTable(E::class, alias)

/** A column of an [EntityTable], identified by its entity property. */
class EntityColumn<E : Any, V>(
  val table: EntityTable<E>,
  val property: KProperty1<E, V>
) {
  /** `alias.property`, usable wherever join queries accept a column reference (e.g. sort properties). */
  val path: String get() = "${table.alias}.${property.name}"

  /** Join condition `this = other`. */
  infix fun eq(other: EntityColumn<*, V>): JoinCondition = JoinCondition(this, other)

  /** `this = value`. */
  infix fun eq(value: V & Any): ColumnCondition = compare(QueryOperator.EQ, value)

  /** `this <> value`. */
  infix fun ne(value: V & Any): ColumnCondition = compare(QueryOperator.NE, value)

  /** `this > value`. */
  infix fun gt(value: V & Any): ColumnCondition = compare(QueryOperator.GT, value)

  /** `this >= value`. */
  infix fun ge(value: V & Any): ColumnCondition = compare(QueryOperator.GTE, value)

  /** `this < value`. */
  infix fun lt(value: V & Any): ColumnCondition = compare(QueryOperator.LT, value)

  /** `this <= value`. */
  infix fun le(value: V & Any): ColumnCondition = compare(QueryOperator.LTE, value)

  /** `this IN (values)`; an empty collection matches nothing. */
  infix fun isIn(values: Collection<V & Any>): ColumnCondition = compare(QueryOperator.IN, values.toList())

  /** `this NOT IN (values)`; an empty collection matches everything. */
  infix fun notIn(values: Collection<V & Any>): ColumnCondition = compare(QueryOperator.NOT_IN, values.toList())

  /** `this IS NULL`. */
  fun isNull(): ColumnCondition = compare(QueryOperator.IS_NULL, null)

  /** `this IS NOT NULL`. */
  fun isNotNull(): ColumnCondition = compare(QueryOperator.IS_NOT_NULL, null)

  private fun compare(
    operator: QueryOperator,
    value: Any?
  ): ColumnCondition = ColumnCondition(table, ColumnCondition.Node.Compare(this, operator, value))

  override fun toString(): String = path
}

/** `this = other` where only [other] is nullable (e.g. `Customer.id` vs `Order.customerId: Long?`). */
@JvmName("eqNullableRight")
infix fun <V : Any> EntityColumn<*, V>.eq(other: EntityColumn<*, V?>): JoinCondition = JoinCondition(this, other)

/** `this = other` where only `this` is nullable. */
@JvmName("eqNullableLeft")
infix fun <V : Any> EntityColumn<*, V?>.eq(other: EntityColumn<*, V>): JoinCondition = JoinCondition(this, other)

/** The column equality of a join's `ON` clause: one key column on each side. */
class JoinCondition internal constructor(
  internal val left: EntityColumn<*, *>,
  internal val right: EntityColumn<*, *>
) {
  override fun toString(): String = "$left = $right"
}

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

  fun from(table: EntityTable<*>): JoinQueryBuilder =
    JoinQueryBuilder(joinQuery, JoinSpec.from(table.type, table.alias), emptyList())
}

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
      val (left, right) =
        when (table.alias) {
          condition.right.table.alias -> condition.left to condition.right
          condition.left.table.alias -> condition.right to condition.left
          else -> throw IllegalArgumentException("ON of '${table.alias}' must use one of its columns: $condition")
        }
      require(left.table.alias != table.alias) { "ON of '${table.alias}' compares two of its own columns: $condition" }
      requireDeclared(left.table)

      val on = left.path to right.property.name
      val joined = spec.join(table.type, table.alias, on, mode = mode, onCondition = and)
      return JoinQueryBuilder(joinQuery, joined, required)
    }
  }

  private fun requireDeclared(table: EntityTable<*>) {
    val declared =
      if (table.alias == spec.rootAlias) spec.root else spec.joins.firstOrNull { it.alias == table.alias }?.entity
    require(declared != null) { "Table '${table.alias}' is not declared; declared: ${spec.aliases()}" }
    require(declared == table.type) {
      "Alias '${table.alias}' is declared as ${declared.simpleName}, not ${table.type.simpleName}"
    }
  }
}
