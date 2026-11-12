package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.R2dbcDialect
import org.springframework.data.r2dbc.query.QueryMapper
import org.springframework.data.relational.core.dialect.RenderContextFactory
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.sql.Column
import org.springframework.data.relational.core.sql.Condition
import org.springframework.data.relational.core.sql.Conditions
import org.springframework.data.relational.core.sql.Expression
import org.springframework.data.relational.core.sql.Expressions
import org.springframework.data.relational.core.sql.Functions
import org.springframework.data.relational.core.sql.Join.JoinType
import org.springframework.data.relational.core.sql.OrderByField
import org.springframework.data.relational.core.sql.Select
import org.springframework.data.relational.core.sql.SelectBuilder
import org.springframework.data.relational.core.sql.SqlIdentifier
import org.springframework.data.relational.core.sql.Table
import org.springframework.data.relational.core.sql.TupleExpression
import org.springframework.data.relational.core.sql.render.SqlRenderer
import org.springframework.r2dbc.core.PreparedOperation
import org.springframework.r2dbc.core.binding.BindMarkers
import org.springframework.r2dbc.core.binding.BindTarget
import org.springframework.r2dbc.core.binding.Bindings
import kotlin.reflect.KClass

/**
 * Runs `@QueryField` filters against a root table plus joined tables (see [JoinSpec])
 * and maps each row into a DTO whose properties are placed with [SelectColumn].
 *
 * Criteria for each alias are combined as `required AND (query criteria, root only) AND mapped filter`.
 * All values are bound parameters; criteria column names are mapped through each table's entity.
 * With `INNER`/`LEFT` one-to-many joins every joined row is one result (and one count).
 */
class R2dbcJoinQuery(
  private val template: R2dbcEntityTemplate
) {
  private val dialect by lazy { template.dataAccessStrategy.dialect as R2dbcDialect }
  private val queryMapper by lazy { QueryMapper(dialect, template.converter) }
  private val renderer by lazy { SqlRenderer.create(RenderContextFactory(dialect).createRenderContext()) }

  /**
   * Selects [resultType] rows. Sort, limit and offset come from [query]; sort properties are
   * DTO property names or `alias.column`.
   *
   * @param required extra criteria keyed by table alias, placed first for that alias.
   */
  fun <T : Any> select(
    spec: JoinSpec,
    resultType: KClass<T>,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap()
  ): Flow<T> =
    template
      .query(prepare(spec, filter, query, required, resultType), resultType.java)
      .all()
      .asFlow()

  /** Counts the rows [select] would return without paging. */
  suspend fun count(
    spec: JoinSpec,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap()
  ): Long =
    template.databaseClient
      .sql(prepare(spec, filter, query, required, resultType = null))
      .map { row -> (row.get(0) as Number).toLong() }
      .one()
      .awaitSingle()

  suspend fun <T : Any> toPagination(
    spec: JoinSpec,
    resultType: KClass<T>,
    pageable: Pageable,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap()
  ): Pagination<T> =
    coroutineScope {
      val items = async { select(spec, resultType, filter, query.with(pageable), required).toList() }
      val total = async { count(spec, filter, query, required) }

      Pagination(
        content = items.await(),
        limit = pageable.pageSize,
        offset = pageable.offset,
        total = total.await()
      )
    }

  /** Builds the statement; [resultType] `null` produces `SELECT COUNT(*)` without sort and paging. */
  internal fun prepare(
    spec: JoinSpec,
    filter: Any?,
    query: Query,
    required: Map<String, CriteriaDefinition>,
    resultType: KClass<*>?
  ): PreparedOperation<Select> = Statement(spec, resultType).build(filter, query, required)

  private inner class Statement(
    private val spec: JoinSpec,
    private val resultType: KClass<*>?
  ) {
    private val entities: Map<String, RelationalPersistentEntity<*>> =
      (listOf(spec.rootAlias to spec.root) + spec.joins.map { it.alias to it.entity })
        .associate { (alias, type) -> alias to entityOf(type) }

    private val tables: Map<String, Table> =
      entities.mapValues { (alias, entity) -> Table.create(entity.qualifiedTableName).`as`(alias) }

    // Bind markers must be created in the same order they appear in the SQL text:
    // MySQL uses positional `?` markers.
    private val markers: BindMarkers = dialect.bindMarkersFactory.create()
    private var bindings = Bindings()

    /** DTO property name -> source column (unaliased); empty for count. */
    private val selected: Map<String, Pair<Column, SqlIdentifier>> = resultType?.let(::selectedColumns).orEmpty()

    fun build(
      filter: Any?,
      query: Query,
      required: Map<String, CriteriaDefinition>
    ): PreparedOperation<Select> {
      val where = whereConditions(filter, query, required)

      val selectList: List<Expression> =
        if (resultType == null) {
          listOf(Functions.count(Expressions.asterisk()))
        } else {
          selected.values.map { (column, alias) -> column.`as`(alias) }
        }

      var joined: SelectBuilder.SelectJoin =
        Select.builder().select(selectList).from(tables.getValue(spec.rootAlias))

      spec.joins.filter { it.mode != JoinMode.EXISTS }.forEach { join ->
        val type = if (join.mode == JoinMode.LEFT) JoinType.LEFT_OUTER_JOIN else JoinType.JOIN
        joined = joined.join(tables.getValue(join.alias), type).on(onCondition(join))
      }

      val paged: SelectBuilder.SelectWhere =
        if (resultType != null && query.isLimited) {
          val offset = query.offset.coerceAtLeast(0)
          when (val j = joined) {
            is SelectBuilder.SelectFromAndJoinCondition -> j.limitOffset(query.limit.toLong(), offset)
            is SelectBuilder.SelectFromAndJoin -> j.limitOffset(query.limit.toLong(), offset)
            else -> error("Unexpected select builder ${j::class}")
          }
        } else {
          joined as SelectBuilder.SelectWhere
        }

      val filtered: SelectBuilder.SelectOrdered = where?.let { paged.where(it) } ?: paged
      val ordered = if (resultType != null && query.isSorted) filtered.orderBy(orderBy(query)) else filtered
      val select = ordered.build()

      return JoinOperation(select, renderer.render(select), bindings)
    }

    private fun whereConditions(
      filter: Any?,
      query: Query,
      required: Map<String, CriteriaDefinition>
    ): Condition? {
      val aliases = spec.aliases()

      val mapped =
        filter
          ?.let { SqlQueryMapper.toTableCriteriaList(it) }
          .orEmpty()
          .groupBy({ (table, _) -> table.ifEmpty { spec.rootAlias } }, { it.second })

      (mapped.keys + required.keys).forEach { alias ->
        require(alias in aliases) { "Unknown table alias '$alias'; declared: $aliases" }
      }

      // Mapping order == condition order == SQL text order (see markers).
      val conditions =
        aliases.mapNotNull { alias ->
          val root = alias == spec.rootAlias
          val criteria =
            SqlQueryMapper.combine(
              listOfNotNull(required[alias], query.criteria.orElse(null)?.takeIf { root }) +
                mapped[alias].orEmpty()
            ) ?: return@mapNotNull null

          val condition = map(alias, criteria)

          if (spec.modeOf(alias) == JoinMode.EXISTS) semiJoin(alias, condition) else condition
        }

      return when (conditions.size) {
        0 -> null
        1 -> conditions.single()
        else -> conditions.map(Conditions::nest).reduce { acc, next -> acc.and(next) }
      }
    }

    private fun map(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition {
      val bound = queryMapper.getMappedObject(markers, criteria, tables.getValue(alias), entities.getValue(alias))
      bindings = Bindings.merge(bindings, bound.bindings)
      return bound.condition
    }

    private fun semiJoin(
      alias: String,
      condition: Condition
    ): Condition {
      val join = spec.joins.single { it.alias == alias }
      val left = join.on.map { (left, _) -> reference(left) }
      val right = join.on.map { (_, right) -> column(alias, right) }

      val subselect =
        Select
          .builder()
          .select(right)
          .from(tables.getValue(alias))
          .where(condition)
          .build()

      return Conditions.`in`(left.singleOrNull() ?: TupleExpression.create(left), subselect)
    }

    private fun onCondition(join: JoinSpec.Join): Condition =
      join.on
        .map { (left, right) -> Conditions.isEqual(reference(left), column(join.alias, right)) as Condition }
        .reduce { acc, next -> acc.and(next) }

    private fun orderBy(query: Query): List<OrderByField> =
      query.sort
        .map { order ->
          val column = selected[order.property]?.first ?: reference(order.property)
          OrderByField.from(column, order.direction)
        }.toList()

    private fun selectedColumns(type: KClass<*>): Map<String, Pair<Column, SqlIdentifier>> {
      val dto = entityOf(type)
      val result = LinkedHashMap<String, Pair<Column, SqlIdentifier>>()

      dto.forEach { property ->
        val annotation = property.findAnnotation(SelectColumn::class.java)
        val alias = annotation?.table?.ifEmpty { null } ?: spec.rootAlias
        require(alias in entities) {
          "${type.simpleName}.${property.name} selects from unknown alias '$alias'; declared: ${spec.aliases()}"
        }
        require(spec.modeOf(alias) != JoinMode.EXISTS) {
          "${type.simpleName}.${property.name} selects from EXISTS alias '$alias'; use INNER or LEFT"
        }

        val name = annotation?.name?.ifEmpty { null }
        val column =
          if (name == null) {
            column(alias, property.name, fallback = property.columnName)
          } else {
            column(alias, name)
          }

        result[property.name] = column to property.columnName
      }

      return result
    }

    /** `alias.name` or bare `name` (root table). */
    private fun reference(qualified: String): Column {
      val alias = qualified.substringBefore('.', spec.rootAlias)
      require(alias in entities) { "Unknown table alias '$alias' in '$qualified'; declared: ${spec.aliases()}" }
      return column(alias, qualified.substringAfter('.'))
    }

    /** Entity property name -> its column; otherwise [fallback] or [name] as a raw (validated) column. */
    private fun column(
      alias: String,
      name: String,
      fallback: SqlIdentifier? = null
    ): Column {
      val identifier =
        entities.getValue(alias).getPersistentProperty(name)?.columnName
          ?: fallback
          ?: SqlIdentifier.unquoted(name.also { requireIdentifier(it) })

      return Column.create(identifier, tables.getValue(alias))
    }
  }

  private fun entityOf(type: KClass<*>): RelationalPersistentEntity<*> =
    template.converter.mappingContext.getRequiredPersistentEntity(type.java)

  private class JoinOperation(
    private val select: Select,
    private val sql: String,
    private val bindings: Bindings
  ) : PreparedOperation<Select> {
    override fun getSource(): Select = select

    override fun toQuery(): String = sql

    override fun bindTo(target: BindTarget) = bindings.apply(target)
  }

  private companion object {
    // Raw names can come from client-supplied sort properties; never render anything but a plain identifier.
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun requireIdentifier(name: String) = require(IDENTIFIER.matches(name)) { "Invalid column name '$name'" }
  }
}
