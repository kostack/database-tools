package io.github.kostack.database_tools.r2dbc.grid.dsl

/** The column equality of a join's `ON` clause: one key column on each side. */
class JoinCondition internal constructor(
  internal val left: EntityColumn<*, *>,
  internal val right: EntityColumn<*, *>
) {
  override fun toString(): String = "$left = $right"
}
