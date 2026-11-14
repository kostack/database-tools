package io.github.kostack.database_tools.r2dbc.grid.dsl

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * An entity table under an alias. The alias is what `@QueryField(table = …)` and `@SelectColumn(table = …)` refer to.
 *
 * ```kotlin
 * val customer = table<Customer>("c")
 * customer[Customer::id]   // column c.id
 * ```
 */
class EntityTable<E : Any>(
  val type: KClass<E>,
  val alias: String
) {
  init {
    // Aliases are rendered unquoted in SQL, so only plain identifiers are allowed.
    require(ALIAS.matches(alias)) { "Invalid table alias '$alias'" }
  }

  operator fun <V> get(property: KProperty1<E, V>): EntityColumn<E, V & Any> = EntityColumn(this, property)

  override fun toString(): String = "${type.simpleName} $alias"

  private companion object {
    val ALIAS = Regex("[A-Za-z_][A-Za-z0-9_]*")
  }
}

/** Declares [E]'s table under [alias]. */
inline fun <reified E : Any> table(alias: String): EntityTable<E> = EntityTable(E::class, alias)
