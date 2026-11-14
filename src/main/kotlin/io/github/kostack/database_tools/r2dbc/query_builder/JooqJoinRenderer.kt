package io.github.kostack.database_tools.r2dbc.query_builder

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SelectJoinStep
import org.jooq.Table
import org.jooq.impl.DSL
import kotlin.reflect.KClass

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
   * @param onCriteria extra `ON` condition of a join (its mapped `onCriteria`), ANDed after the column pairs.
   */
  fun from(
    spec: JoinSpec,
    select: Collection<SelectFieldOrAsterisk> = emptyList(),
    onCriteria: (JoinSpec.Join) -> Condition? = { null }
  ): SelectJoinStep<Record> = dsl.select(select).from(joinTree(spec, onCriteria))

  /** The root table with every `INNER` / `LEFT` join applied, in declaration order. */
  fun joinTree(
    spec: JoinSpec,
    onCriteria: (JoinSpec.Join) -> Condition? = { null }
  ): Table<*> =
    spec.joins
      .filter { it.mode.joined }
      .fold(metadata.table(spec.root, spec.rootAlias)) { from, join ->
        val table = metadata.table(join.entity, join.alias)
        val columns = join.on.map { (left, right) -> reference(spec, left).eq(field(spec, join.alias, right)) }
        val on = DSL.and(columns + listOfNotNull(onCriteria(join)))
        if (join.mode == JoinMode.LEFT) from.leftOuterJoin(table).on(on) else from.join(table).on(on)
      }

  /** `alias.name`, or a bare `name` of the root table. */
  fun reference(
    spec: JoinSpec,
    qualified: String
  ): Field<Any?> {
    val alias = qualified.substringBefore('.', spec.rootAlias)
    return field(spec, alias, qualified.substringAfter('.'))
  }

  /** Column [name] (entity property or raw column) of the table declared as [alias]. */
  fun field(
    spec: JoinSpec,
    alias: String,
    name: String
  ): Field<Any?> = metadata.field(typeOf(spec, alias), alias, name)

  private fun typeOf(
    spec: JoinSpec,
    alias: String
  ): KClass<*> =
    if (alias == spec.rootAlias) {
      spec.root
    } else {
      requireNotNull(spec.joins.firstOrNull { it.alias == alias }?.entity) {
        "Unknown table alias '$alias'; declared: ${spec.aliases()}"
      }
    }
}
