package io.github.kostack.database_tools.r2dbc.join

import org.jooq.Field
import org.jooq.Name
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity
import org.springframework.data.relational.core.sql.SqlIdentifier
import kotlin.reflect.KClass

/**
 * Resolves Spring Data entity metadata into jOOQ tables and fields.
 *
 * Names keep Spring's quoting and letter casing (e.g. backticks on MySQL, upper case on H2),
 * so the SQL refers to exactly the tables and columns Spring Data itself uses.
 * Aliases are rendered unquoted; [JoinSpec] only accepts plain identifiers.
 */
class JooqMetadataResolver(
  private val template: R2dbcEntityTemplate
) {
  private val identifiers by lazy { template.dataAccessStrategy.dialect.identifierProcessing }

  fun entity(type: KClass<*>): RelationalPersistentEntity<*> =
    template.converter.mappingContext
      .getRequiredPersistentEntity(type.java)

  fun table(
    type: KClass<*>,
    alias: String
  ): Table<*> =
    DSL
      .table(name(entity(type).qualifiedTableName))
      .`as`(DSL.unquotedName(alias))

  /**
   * `alias.column` for an entity [property]; when [type] has no such property,
   * [fallback] or [property] itself is used as a raw (validated) column name.
   */
  fun field(
    type: KClass<*>,
    alias: String,
    property: String,
    fallback: SqlIdentifier? = null
  ): Field<Any?> {
    val column =
      entity(type).getPersistentProperty(property)?.columnName
        ?: fallback
        ?: SqlIdentifier.unquoted(property.also(::requireIdentifier))

    return DSL.field(
      DSL.name(DSL.unquotedName(alias), name(column)),
      Any::class.java
    )
  }

  /** Converts a Spring identifier (possibly schema-qualified) to a jOOQ name, quoted the way Spring renders it. */
  fun name(identifier: SqlIdentifier): Name {
    val parts =
      (identifier as Iterable<SqlIdentifier>).map { part ->
        val sql = part.toSql(identifiers)
        if (sql == part.reference) DSL.unquotedName(sql) else DSL.quotedName(sql.substring(1, sql.length - 1))
      }
    return parts.singleOrNull() ?: DSL.name(*parts.toTypedArray())
  }

  private companion object {
    // Raw names can come from client-supplied sort properties; never render anything but a plain identifier.
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun requireIdentifier(name: String) = require(IDENTIFIER.matches(name)) { "Invalid column name '$name'" }
  }
}
