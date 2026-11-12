package io.github.kostack.database_tools.r2dbc.join

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
  EXISTS
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
    val on: List<Pair<String, String>>
  )

  fun join(
    entity: KClass<*>,
    alias: String,
    vararg on: Pair<String, String>,
    mode: JoinMode = JoinMode.INNER
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
      require(leftJoin?.mode != JoinMode.EXISTS) {
        "Join '$alias' cannot reference EXISTS alias '$leftAlias' in ON"
      }
    }

    return JoinSpec(root, rootAlias, joins + Join(entity, alias, mode, on.toList()))
  }

  fun aliases(): List<String> = listOf(rootAlias) + joins.map { it.alias }

  fun modeOf(alias: String): JoinMode? = joins.firstOrNull { it.alias == alias }?.mode

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
