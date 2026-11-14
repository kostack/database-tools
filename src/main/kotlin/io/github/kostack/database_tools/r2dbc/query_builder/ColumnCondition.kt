package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.query_mapper.QueryOperator

/**
 * A value condition on the columns of one table, built from [EntityColumn]s:
 *
 * ```kotlin
 * order[Order::status] eq "PAID"
 * (user[User::name] eq "alice") or (user[User::name] eq "bob")
 * order[Order::deletedAt].isNull()
 * ```
 *
 * Used by `where(...)` and as the extra `ON` condition of a join. Values are bind parameters, converted to the
 * property type like Spring Data does (enums, custom converters).
 *
 * A condition targets a single table, because where it goes depends on the table: `WHERE` for the root and
 * `join` / `leftJoin` tables, the subquery for `existsJoin` / `aggregateJoin` tables.
 */
class ColumnCondition internal constructor(
  internal val table: EntityTable<*>,
  internal val node: Node
) {
  infix fun and(other: ColumnCondition): ColumnCondition =
    ColumnCondition(table, Node.All(listOf(node, sameTable(other))))

  infix fun or(other: ColumnCondition): ColumnCondition =
    ColumnCondition(table, Node.AnyOf(listOf(node, sameTable(other))))

  private fun sameTable(other: ColumnCondition): Node {
    require(other.table.alias == table.alias) {
      "Conditions on '${table.alias}' and '${other.table.alias}' cannot be combined; pass them to where() separately"
    }
    return other.node
  }

  internal sealed interface Node {
    class Compare(
      val column: EntityColumn<*, *>,
      val operator: QueryOperator,
      val value: Any?
    ) : Node

    class All(
      val parts: List<Node>
    ) : Node

    class AnyOf(
      val parts: List<Node>
    ) : Node
  }
}
