package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.query_mapper.QueryOperator
import kotlin.reflect.KProperty1

/**
 * A column of an [EntityTable], identified by its entity property. [V] is the property's type without
 * nullability, so a nullable foreign key (`Order.customerId: Long?`) joins a non-null key (`Customer.id: Long`).
 */
class EntityColumn<E : Any, V : Any> internal constructor(
  val table: EntityTable<E>,
  val property: KProperty1<E, *>
) {
  /** `alias.property`, usable wherever join queries accept a column reference (e.g. sort properties). */
  val path: String get() = "${table.alias}.${property.name}"

  /** Join condition `this = other`. */
  infix fun eq(other: EntityColumn<*, V>): JoinCondition = JoinCondition(this, other)

  /** `this = value`. */
  infix fun eq(value: V): ColumnCondition = compare(QueryOperator.EQ, value)

  /** `this <> value`. */
  infix fun ne(value: V): ColumnCondition = compare(QueryOperator.NE, value)

  /** `this > value`. */
  infix fun gt(value: V): ColumnCondition = compare(QueryOperator.GT, value)

  /** `this >= value`. */
  infix fun ge(value: V): ColumnCondition = compare(QueryOperator.GTE, value)

  /** `this < value`. */
  infix fun lt(value: V): ColumnCondition = compare(QueryOperator.LT, value)

  /** `this <= value`. */
  infix fun le(value: V): ColumnCondition = compare(QueryOperator.LTE, value)

  /** `this IN (values)`; an empty collection matches nothing. */
  infix fun isIn(values: Collection<V>): ColumnCondition = compare(QueryOperator.IN, values.toList())

  /** `this NOT IN (values)`; an empty collection matches everything. */
  infix fun notIn(values: Collection<V>): ColumnCondition = compare(QueryOperator.NOT_IN, values.toList())

  /** `this IS NULL`. */
  fun isNull(): ColumnCondition = compare(QueryOperator.IS_NULL, null)

  /** `this IS NOT NULL`. */
  fun isNotNull(): ColumnCondition = compare(QueryOperator.IS_NOT_NULL, null)

  private fun compare(
    operator: QueryOperator,
    value: Any?
  ): ColumnCondition = ColumnCondition(table, ColumnCondition.Node.Compare(this, operator, value))

  override fun toString(): String = path
}
