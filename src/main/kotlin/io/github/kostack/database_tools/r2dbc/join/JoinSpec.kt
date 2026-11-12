package io.github.kostack.database_tools.r2dbc.join

import org.springframework.data.relational.core.query.CriteriaDefinition
import kotlin.reflect.KClass

enum class JoinMode {
  /** `JOIN` — rows without a match are dropped; columns can be selected. */
  INNER,

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s; columns can be selected. */
  LEFT,

  /**
   * Filter only, rendered as a semi-join `left IN (SELECT right FROM … WHERE <criteria>)`.
   * Never multiplies root rows and its columns cannot be selected.
   * Skipped entirely when no criteria target this alias.
   */
  EXISTS,

  /**
   * Source for per-root-row aggregates (`@SelectAggregate`, `@QueryField(aggregate = …)`), rendered as
   * correlated scalar subqueries `(SELECT AVG(o.total) FROM orders o WHERE o.user_id = u.id AND <criteria>)`.
   * Never joined as rows; plain criteria on this alias restrict the rows being aggregated.
   */
  AGGREGATE;

  /** Whether the table is part of the FROM clause (its columns can be selected and referenced). */
  val joined: Boolean get() = this == INNER || this == LEFT
}

/**
 * Immutable description of a root table and the tables joined to it.
 *
 * ```kotlin
 * val spec = JoinSpec.from(User::class, "u")
 *   .join(Order::class, "o", "u.id" to "user_id")
 *   .join(Item::class, "i", "o.id" to "order_id", mode = JoinMode.EXISTS)
 * ```
 *
 * In each `on` pair, the left side is `alias.column` of an already declared table
 * (a bare name means the root table) and the right side is a column of the joined table.
 * Names are resolved against the entities: property names map to their columns,
 * anything else is used as a raw column name.
 *
 * `onCriteria` adds value conditions on the joined table's own columns, ANDed after the column pairs:
 *  - `INNER` / `LEFT`: rendered in `ON` — for `LEFT`, unmatched root rows are kept (unlike criteria in `WHERE`);
 *  - `EXISTS`: added to the subquery; does not by itself activate the EXISTS filter;
 *  - `AGGREGATE`: always restricts the rows being aggregated.
 *
 * ```kotlin
 * .join(Order::class, "o", "u.id" to "userId", mode = JoinMode.LEFT, onCriteria = Criteria.where("status").`is`("PAID"))
 * // LEFT OUTER JOIN orders o ON u.id = o.user_id AND (o.status = ?)
 * ```
 */
class JoinSpec private constructor(
  val root: KClass<*>,
  val rootAlias: String,
  val joins: List<Join>
) {
  data class Join(
    val entity: KClass<*>,
    val alias: String,
    val mode: JoinMode,
    val on: List<Pair<String, String>>,
    val onCriteria: CriteriaDefinition? = null
  )

  fun join(
    entity: KClass<*>,
    alias: String,
    vararg on: Pair<String, String>,
    mode: JoinMode = JoinMode.INNER,
    onCriteria: CriteriaDefinition? = null
  ): JoinSpec {
    requireAlias(alias)
    require(aliases().none { it == alias }) { "Duplicate join alias '$alias'" }
    require(on.isNotEmpty()) { "Join '$alias' needs at least one ON column pair" }

    on.forEach { (left, _) ->
      val leftAlias = left.substringBefore('.', rootAlias)
      val leftJoin = joins.firstOrNull { it.alias == leftAlias }
      require(leftAlias == rootAlias || leftJoin != null) {
        "Join '$alias' references unknown alias '$leftAlias' in ON ('$left')"
      }
      require(leftJoin == null || leftJoin.mode.joined) {
        "Join '$alias' cannot reference ${leftJoin?.mode} alias '$leftAlias' in ON; only the root, INNER and LEFT aliases"
      }
    }

    return JoinSpec(root, rootAlias, joins + Join(entity, alias, mode, on.toList(), onCriteria))
  }

  fun aliases(): List<String> = listOf(rootAlias) + joins.map { it.alias }

  fun modeOf(alias: String): JoinMode? = joins.firstOrNull { it.alias == alias }?.mode

  fun onCriteriaOf(alias: String): CriteriaDefinition? = joins.firstOrNull { it.alias == alias }?.onCriteria

  companion object {
    fun from(
      entity: KClass<*>,
      alias: String
    ): JoinSpec {
      requireAlias(alias)
      return JoinSpec(entity, alias, emptyList())
    }

    private val ALIAS = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun requireAlias(alias: String) = require(ALIAS.matches(alias)) { "Invalid table alias '$alias'" }
  }
}
