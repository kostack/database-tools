package io.github.kostack.database_tools.r2dbc.query_builder

/**
 * Immutable description of a root table and the tables joined to it, built by [JoinQueryBuilder].
 *
 * Each join links its own [Join.key] column to [Join.parentKey], a column of an already declared root,
 * `INNER` or `LEFT` table (validated by the builder). [Join.condition] is a condition on the joined table's
 * own columns, ANDed after the key equality:
 *  - `INNER` / `LEFT`: rendered in `ON` — for `LEFT`, unmatched root rows are kept (unlike conditions in `WHERE`);
 *  - `EXISTS`: added to the subquery; does not by itself activate the EXISTS filter;
 *  - `AGGREGATE`: always restricts the rows being aggregated.
 */
internal class JoinSpec private constructor(
  val root: EntityTable<*>,
  val joins: List<Join>
) {
  class Join(
    val table: EntityTable<*>,
    val mode: JoinMode,
    val parentKey: EntityColumn<*, *>,
    val key: EntityColumn<*, *>,
    val condition: ColumnCondition?
  )

  /** Every declared table by alias, root first, in declaration order. */
  val tables: Map<String, EntityTable<*>> = (listOf(root) + joins.map { it.table }).associateBy { it.alias }

  val rootAlias: String get() = root.alias

  fun aliases(): List<String> = tables.keys.toList()

  fun joinOf(alias: String): Join? = joins.firstOrNull { it.table.alias == alias }

  fun modeOf(alias: String): JoinMode? = joinOf(alias)?.mode

  fun join(join: Join): JoinSpec {
    require(join.table.alias !in tables) { "Duplicate table alias '${join.table.alias}'" }
    return JoinSpec(root, joins + join)
  }

  companion object {
    fun from(root: EntityTable<*>): JoinSpec = JoinSpec(root, emptyList())
  }
}
