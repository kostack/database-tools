package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * An entity table under an alias, the typed counterpart of a [JoinSpec] alias.
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
 * Entry point of the typed join query API; auto-configured next to [R2dbcJoinQuery].
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
  private val joinQuery: R2dbcJoinQuery
) {
  fun from(table: EntityTable<*>): JoinQueryBuilder =
    JoinQueryBuilder(joinQuery, JoinSpec.from(table.type, table.alias), emptyMap())
}

/**
 * An immutable join query: every step returns a new builder. Filters, DTO mapping (`@SelectColumn`,
 * `@SelectAggregate`), sorting and paging behave exactly as in [R2dbcJoinQuery].
 */
class JoinQueryBuilder internal constructor(
  @PublishedApi internal val joinQuery: R2dbcJoinQuery,
  /** The underlying spec, e.g. to call [R2dbcJoinQuery] directly. */
  val spec: JoinSpec,
  @PublishedApi internal val required: Map<String, CriteriaDefinition>
) {
  /** `JOIN` — rows without a match are dropped. */
  fun join(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.INNER)

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s. */
  fun leftJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.LEFT)

  /** Filter-only semi-join; active only when a filter field or [where] targets [table]. */
  fun existsJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.EXISTS)

  /** Source for `@SelectAggregate` / aggregate filter fields, computed per root row. */
  fun aggregateJoin(table: EntityTable<*>): PendingJoin = PendingJoin(table, JoinMode.AGGREGATE)

  /** Mandatory criteria on [table], ANDed before filter fields (the `required` map of [R2dbcJoinQuery]). */
  fun where(
    table: EntityTable<*>,
    criteria: CriteriaDefinition
  ): JoinQueryBuilder {
    requireDeclared(table)
    val combined = SqlQueryMapper.combine(listOfNotNull(required[table.alias], criteria))
    return JoinQueryBuilder(joinQuery, spec, if (combined == null) required else required + (table.alias to combined))
  }

  /** Selects [T] rows; see [R2dbcJoinQuery.select]. */
  inline fun <reified T : Any> fetch(
    filter: Any? = null,
    query: Query = Query.empty(),
    sort: Sort = Sort.unsorted()
  ): Flow<T> = joinQuery.select(spec, T::class, filter, query, required, sort)

  /** One page plus the total count; see [R2dbcJoinQuery.toPagination]. */
  suspend inline fun <reified T : Any> fetchPage(
    filter: Any? = null,
    pageable: Pageable,
    query: Query = Query.empty()
  ): Pagination<T> = joinQuery.toPagination(spec, T::class, pageable, filter, query, required)

  /** Counts the rows [fetch] would return without paging. */
  suspend fun count(
    filter: Any? = null,
    query: Query = Query.empty()
  ): Long = joinQuery.count(spec, filter, query, required)

  /** A join waiting for its `ON` condition. */
  inner class PendingJoin internal constructor(
    private val table: EntityTable<*>,
    private val mode: JoinMode
  ) {
    /**
     * Each equality must compare a column of the joined table with a column of an already declared table,
     * in either order. [onCriteria] adds value conditions on the joined table (see [JoinSpec]).
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
