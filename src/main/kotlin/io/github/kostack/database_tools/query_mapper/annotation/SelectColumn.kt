package io.github.kostack.database_tools.query_mapper.annotation

/**
 * Tells a join query where a result DTO property is read from.
 *
 * Without this annotation the property is read from the root table. The column is resolved
 * against the table's entity: an entity property name is mapped to its column, anything else
 * is used as a raw column name. The value is selected `AS` the DTO property's column name.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SelectColumn(
  /** Table alias declared in the `JoinSpec`. Empty = root table. */
  val table: String = "",
  /** Entity property or column name in that table. Empty = the DTO property name. */
  val name: String = ""
)
