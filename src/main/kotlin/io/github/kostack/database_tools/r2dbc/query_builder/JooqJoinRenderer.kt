package io.github.kostack.database_tools.r2dbc.query_builder

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SelectJoinStep
import org.jooq.Table

/**
 * Renders the `FROM` clause of a [JoinSpec] with jOOQ: the root table plus its `INNER` / `LEFT` joins.
 * `EXISTS` and `AGGREGATE` aliases are never joined as rows; they become subqueries elsewhere.
 */
internal class JooqJoinRenderer(
  private val dsl: DSLContext,
  private val metadata: JooqMetadataResolver
) {
  /**
   * `SELECT <select> FROM root JOIN ... ON ...`; an empty [select] renders `SELECT *`.
   *
   * @param onCondition extra `ON` condition of a join (its mapped `onCondition`), ANDed after the column pairs.
   */
  fun from(
    spec: JoinSpec,
    select: Collection<SelectFieldOrAsterisk> = emptyList(),
    onCondition: (JoinSpec.Join) -> Condition? = { null }
  ): SelectJoinStep<Record> = dsl.select(select).from(joinTree(spec, onCondition))

  /** The root table with every `INNER` / `LEFT` join applied, in declaration order. */
  fun joinTree(
    spec: JoinSpec,
    onCondition: (JoinSpec.Join) -> Condition? = { null }
  ): Table<*> =
    spec.joins
      .filter { it.mode.joined }
      .fold(metadata.table(spec.root.type, spec.root.alias)) { from, join ->
        val table = metadata.table(join.table.type, join.table.alias)
        val keys = column(join.parentKey).eq(column(join.key))
        val on = onCondition(join)?.let { keys.and(it) } ?: keys
        if (join.mode == JoinMode.LEFT) from.leftOuterJoin(table).on(on) else from.join(table).on(on)
      }

  /** The jOOQ field of an entity column. */
  fun column(column: EntityColumn<*, *>): Field<Any?> =
    metadata.field(column.table.type, column.table.alias, column.property.name)
}
