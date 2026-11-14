package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.paginator.Pagination
import kotlinx.coroutines.flow.Flow
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectConditionStep
import org.jooq.SelectFieldOrAsterisk
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query
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
  infix fun eq(other: EntityColumn<*, V>): JoinCondition = JoinCondition(listOf(this to other))

  override fun toString(): String = path
}

/** `this = other` where only [other] is nullable (e.g. `Customer.id` vs `Order.customerId: Long?`). */
@JvmName("eqNullableRight")
infix fun <V : Any> EntityColumn<*, V>.eq(other: EntityColumn<*, V?>): JoinCondition =
  JoinCondition(listOf(this to other))

/** `this = other` where only `this` is nullable. */
@JvmName("eqNullableLeft")
infix fun <V : Any> EntityColumn<*, V?>.eq(other: EntityColumn<*, V>): JoinCondition =
  JoinCondition(listOf(this to other))

/** Column equalities of a join's `ON` clause, combined with [and]. */
class JoinCondition internal constructor(
  internal val pairs: List<Pair<EntityColumn<*, *>, EntityColumn<*, *>>>
) {
  infix fun and(other: JoinCondition): JoinCondition = JoinCondition(pairs + other.pairs)
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
    JoinQueryBuilder(joinQuery, JoinSpec.from(table.type, table.alias), emptyMap())
}

/**
 * An immutable join query: every step returns a new builder. Filters, DTO mapping (`@SelectColumn`,
 * `@SelectAggregate`), sorting and paging are applied by the join query engine.
 */
class JoinQueryBuilder internal constructor(
  private val joinQuery: JoinQueryEngine,
  internal val spec: JoinSpec,
  private val required: Map<String, CriteriaDefinition>
) {
  /** `JOIN` — rows without a match are dropped. */
  fun join(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.INNER)

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s. */
  fun leftJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.LEFT)

  /** Filter-only semi-join; active only when a filter field or [where] targets [table]. */
  fun existsJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.EXISTS)

  /** Source for `@SelectAggregate` / aggregate filter fields, computed per root row. */
  fun aggregateJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.AGGREGATE)

  /** Mandatory criteria on [table], ANDed before filter fields. */
  fun where(
    table: EntityTable<*>,
    criteria: CriteriaDefinition
  ): JoinQueryBuilder {
    requireDeclared(table)
    if (criteria is Criteria && criteria.isEmpty) return this
    // Each part is its own group, so OR chains inside either one keep their precedence.
    val combined = required[table.alias]?.let { Criteria.empty().and(it).and(criteria) } ?: criteria
    return JoinQueryBuilder(joinQuery, spec, required + (table.alias to combined))
  }

  /**
   * Selects [T] rows. Limit and offset come from [query]; ordering is `query.sort` followed by [sort],
   * which may contain `ignoreCase()` orders. Sort properties are DTO property names or `alias.property`.
   */
  inline fun <reified T : Any> fetch(
    filter: Any? = null,
    query: Query = Query.empty(),
    sort: Sort = Sort.unsorted()
  ): Flow<T> = fetch(T::class, filter, query, sort)

  /** [fetch] with an explicit result type. */
  fun <T : Any> fetch(
    resultType: KClass<T>,
    filter: Any? = null,
    query: Query = Query.empty(),
    sort: Sort = Sort.unsorted()
  ): Flow<T> = joinQuery.select(spec, resultType, filter, query, required, sort)

  /** One page plus the total count; `ignoreCase()` orders in [pageable] are supported. */
  suspend inline fun <reified T : Any> fetchPage(
    filter: Any? = null,
    pageable: Pageable,
    query: Query = Query.empty()
  ): Pagination<T> = fetchPage(T::class, filter, pageable, query)

  /** [fetchPage] with an explicit result type. */
  suspend fun <T : Any> fetchPage(
    resultType: KClass<T>,
    filter: Any? = null,
    pageable: Pageable,
    query: Query = Query.empty()
  ): Pagination<T> = joinQuery.toPagination(spec, resultType, pageable, filter, query, required)

  /** Counts the rows [fetch] would return without paging. */
  suspend fun count(
    filter: Any? = null,
    query: Query = Query.empty()
  ): Long = joinQuery.count(spec, filter, query, required)

  /** The jOOQ field of [column], for queries built with [select]. */
  fun field(column: EntityColumn<*, *>): Field<Any?> {
    requireDeclared(column.table)
    return joinQuery.field(spec, column.table.alias, column.property.name)
  }

  /**
   * A plain jOOQ query over this builder's tables, for what [fetch] does not cover (e.g. `GROUP BY` reports):
   * `SELECT <fields> FROM root JOIN ...` with the `INNER` / `LEFT` joins, their `onCriteria` and the [where]
   * criteria applied. Add conditions with `.and(...)`. Filters, DTO mapping and paging are not applied, and
   * `EXISTS` / `AGGREGATE` joins are rejected.
   *
   * It is rendered for the template's database, so `sql` and `bindValues` can be run with `DatabaseClient`.
   */
  fun select(vararg fields: SelectFieldOrAsterisk): SelectConditionStep<Record> =
    joinQuery.selectFrom(spec, fields.toList(), required)

  /** A join waiting for its `ON` condition. */
  inner class PendingJoin internal constructor(
    private val table: EntityTable<*>,
    private val mode: JoinMode
  ) {
    /**
     * Each equality must compare a column of the joined table with a column of an already declared table,
     * in either order. [onCriteria] adds value conditions on the joined table:
     * in `ON` for `join` / `leftJoin`, in the subquery for `existsJoin` / `aggregateJoin`.
     */
    fun on(
      condition: JoinCondition,
      onCriteria: CriteriaDefinition? = null
    ): JoinQueryBuilder {
      val pairs =
        condition.pairs.map { (a, b) ->
          val (left, right) =
            when (table.alias) {
              b.table.alias -> a to b
              a.table.alias -> b to a
              else -> throw IllegalArgumentException("ON of '${table.alias}' must use one of its columns: $a = $b")
            }
          require(left.table.alias != table.alias) { "ON of '${table.alias}' compares two of its own columns: $a = $b" }
          requireDeclared(left.table)
          left.path to right.property.name
        }

      val joined = spec.join(table.type, table.alias, *pairs.toTypedArray(), mode = mode, onCriteria = onCriteria)
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
