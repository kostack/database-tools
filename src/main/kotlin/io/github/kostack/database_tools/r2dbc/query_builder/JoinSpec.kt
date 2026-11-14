package io.github.kostack.database_tools.r2dbc.query_builder

import kotlin.reflect.KClass

/**
 * Immutable description of a root table and the tables joined to it.
 * Internal model behind [R2dbcQueryBuilder], which is the public way to declare joins.
 *
 * ```kotlin
 * val spec = JoinSpec.from(User::class, "u")
 *   .join(Order::class, "o", "u.id" to "user_id")
 *   .join(Item::class, "i", "o.id" to "order_id", mode = JoinMode.EXISTS)
 * ```
 *
 * In the `on` pair, the left side is `alias.column` of an already declared table
 * (a bare name means the root table) and the right side is a column of the joined table.
 * Names are resolved against the entities: property names map to their columns,
 * anything else is used as a raw column name.
 *
 * `onCondition` is a condition on the joined table's own columns, ANDed after the column pair:
 *  - `INNER` / `LEFT`: rendered in `ON` — for `LEFT`, unmatched root rows are kept (unlike conditions in `WHERE`);
 *  - `EXISTS`: added to the subquery; does not by itself activate the EXISTS filter;
 *  - `AGGREGATE`: always restricts the rows being aggregated.
 */
internal class JoinSpec private constructor(
  val root: KClass<*>,
  val rootAlias: String,
  val joins: List<Join>
) {
  data class Join(
    val entity: KClass<*>,
    val alias: String,
    val mode: JoinMode,
    val on: Pair<String, String>,
    val onCondition: ColumnCondition? = null
  )

  fun join(
    entity: KClass<*>,
    alias: String,
    on: Pair<String, String>,
    mode: JoinMode = JoinMode.INNER,
    onCondition: ColumnCondition? = null
  ): JoinSpec {
    requireAlias(alias)
    require(aliases().none { it == alias }) { "Duplicate join alias '$alias'" }
    val left = on.first
    val leftAlias = left.substringBefore('.', rootAlias)
    val leftJoin = joins.firstOrNull { it.alias == leftAlias }
    require(leftAlias == rootAlias || leftJoin != null) {
      "Join '$alias' references unknown alias '$leftAlias' in ON ('$left')"
    }
    require(leftJoin == null || leftJoin.mode.joined) {
      "Join '$alias' cannot reference ${leftJoin?.mode} alias '$leftAlias' in ON; only the root, INNER and LEFT aliases"
    }

    return JoinSpec(root, rootAlias, joins + Join(entity, alias, mode, on, onCondition))
  }

  fun aliases(): List<String> = listOf(rootAlias) + joins.map { it.alias }

  fun modeOf(alias: String): JoinMode? = joins.firstOrNull { it.alias == alias }?.mode

  fun onConditionOf(alias: String): ColumnCondition? = joins.firstOrNull { it.alias == alias }?.onCondition

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
