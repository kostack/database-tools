package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.Aggregate
import io.github.kostack.database_tools.query_mapper.PageRequestMapper
import io.github.kostack.database_tools.query_mapper.QueryMapper.MappedField
import io.github.kostack.database_tools.query_mapper.QueryOperator
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper
import io.github.kostack.database_tools.query_mapper.annotation.SelectAggregate
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.core.TypeInformation
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.R2dbcDialect
import org.springframework.data.relational.core.dialect.RenderContextFactory
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity
import org.springframework.data.relational.core.query.Criteria
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
import org.springframework.data.relational.core.sql.SQL
import org.springframework.data.relational.core.sql.Select
import org.springframework.data.relational.core.sql.SelectBuilder
import org.springframework.data.relational.core.sql.SimpleFunction
import org.springframework.data.relational.core.sql.SqlIdentifier
import org.springframework.data.relational.core.sql.Table
import org.springframework.data.relational.core.sql.TupleExpression
import org.springframework.data.relational.core.sql.render.SqlRenderer
import org.springframework.r2dbc.core.PreparedOperation
import org.springframework.r2dbc.core.binding.BindMarkers
import org.springframework.r2dbc.core.binding.BindTarget
import org.springframework.r2dbc.core.binding.Bindings
import org.springframework.r2dbc.core.binding.MutableBindings
import kotlin.reflect.KClass
import org.springframework.data.r2dbc.query.QueryMapper as R2dbcQueryMapper

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
  private val queryMapper by lazy { R2dbcQueryMapper(dialect, template.converter) }
  private val renderer by lazy { SqlRenderer.create(RenderContextFactory(dialect).createRenderContext()) }

  /**
   * Selects [resultType] rows. Limit and offset come from [query]; ordering is `query.sort` followed by [sort].
   * Sort properties are DTO property names or `alias.column`.
   *
   * @param required extra criteria keyed by table alias, placed first for that alias.
   * @param sort extra ordering; unlike `Query.sort` it may contain `ignoreCase()` orders (rendered as `LOWER(column)`).
   */
  fun <T : Any> select(
    spec: JoinSpec,
    resultType: KClass<T>,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap(),
    sort: Sort = Sort.unsorted()
  ): Flow<T> =
    template
      .query(prepare(spec, filter, query, required, resultType, sort), resultType.java)
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

  /**
   * Selects one page plus the total count.
   *
   * @param sortableSource class carrying `@Sortable` / `@SortableDefaults` whose rules [pageable] goes through
   *   ([PageRequestMapper]): unknown sort fields are rejected, API names are mapped to their `field`
   *   (a DTO property or `alias.column`), the default sort applies and the page size is capped.
   *   Defaults to the [filter]'s class when it is annotated; pass `null` to opt out, in which case any
   *   DTO property or `alias.column` may be sorted on.
   */
  suspend fun <T : Any> toPagination(
    spec: JoinSpec,
    resultType: KClass<T>,
    pageable: Pageable,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap(),
    sortableSource: KClass<*>? = filter?.let { it::class }?.takeIf(PageRequestMapper::isSortable)
  ): Pagination<T> =
    coroutineScope {
      val page = sortableSource?.let { PageRequestMapper.toPageRequest(pageable, it) } ?: pageable
      // Paging goes into the query; the sort is passed separately because Spring's Query rejects ignoreCase orders.
      val paged = query.with(PageRequest.of(page.pageNumber, page.pageSize))
      val items = async { select(spec, resultType, filter, paged, required, page.sort).toList() }
      val total = async { count(spec, filter, query, required) }

      Pagination(
        content = items.await(),
        limit = page.pageSize,
        offset = page.offset,
        total = total.await()
      )
    }

  /** Builds the statement; [resultType] `null` produces `SELECT COUNT(*)` without sort and paging. */
  internal fun prepare(
    spec: JoinSpec,
    filter: Any?,
    query: Query,
    required: Map<String, CriteriaDefinition>,
    resultType: KClass<*>?,
    sort: Sort = Sort.unsorted()
  ): PreparedOperation<Select> = Statement(spec, resultType).build(filter, query, required, query.sort.and(sort))

  private inner class Statement(
    private val spec: JoinSpec,
    private val resultType: KClass<*>?
  ) {
    private val entities: Map<String, RelationalPersistentEntity<*>> =
      (listOf(spec.rootAlias to spec.root) + spec.joins.map { it.alias to it.entity })
        .associate { (alias, type) -> alias to entityOf(type) }

    private val tables: Map<String, Table> =
      entities.mapValues { (alias, entity) -> Table.create(entity.qualifiedTableName).`as`(alias) }

    // Bind markers must be created in the same order they appear in the SQL text
    // (MySQL uses positional `?` markers): select list, then WHERE. JOIN ON, ORDER BY and LIMIT bind nothing.
    private val markers: BindMarkers = dialect.bindMarkersFactory.create()
    private var bindings = Bindings()
    private val identifiers = dialect.identifierProcessing

    /** Plain criteria per alias: `required AND (query criteria, root only) AND filter`. */
    private lateinit var criteriaByAlias: Map<String, Criteria>

    /** DTO property name -> expression to sort by. */
    private val sortable = HashMap<String, Expression>()

    fun build(
      filter: Any?,
      query: Query,
      required: Map<String, CriteriaDefinition>,
      sort: Sort
    ): PreparedOperation<Select> {
      val fields = filter?.let { SqlQueryMapper.activeFields(it) }.orEmpty()
      val aliasOf = { field: MappedField -> field.field.table.ifEmpty { spec.rootAlias } }
      val (aggregateFields, plainFields) = fields.partition { it.field.aggregate != Aggregate.NONE }

      (fields.map(aliasOf) + required.keys).forEach(::requireAlias)

      val plain = plainFields.groupBy(aliasOf, SqlQueryMapper::toCriteria)
      criteriaByAlias =
        spec
          .aliases()
          .mapNotNull { alias ->
            val root = alias == spec.rootAlias
            SqlQueryMapper
              .combine(
                listOfNotNull(required[alias], query.criteria.orElse(null)?.takeIf { root }) + plain[alias].orEmpty()
              )?.let { alias to it }
          }.toMap()

      val selectList: List<Expression> =
        if (resultType == null) listOf(Functions.count(Expressions.asterisk())) else selectList(resultType)

      // Text order: select list, JOIN ... ON, WHERE.
      val joins = spec.joins.filter { it.mode.joined }.map { it to onCondition(it) }
      val where = whereCondition(aggregateFields.map { aliasOf(it) to it })

      var joined: SelectBuilder.SelectJoin =
        Select.builder().select(selectList).from(tables.getValue(spec.rootAlias))

      joins.forEach { (join, on) ->
        val type = if (join.mode == JoinMode.LEFT) JoinType.LEFT_OUTER_JOIN else JoinType.JOIN
        joined = joined.join(tables.getValue(join.alias), type).on(on)
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
      val ordered = if (resultType != null && sort.isSorted) filtered.orderBy(orderBy(sort)) else filtered
      val select = ordered.build()

      return JoinOperation(select, renderer.render(select), bindings)
    }

    private fun whereCondition(aggregateFields: List<Pair<String, MappedField>>): Condition? {
      val conditions =
        spec.aliases().mapNotNull { alias ->
          val criteria = criteriaByAlias[alias] ?: return@mapNotNull null
          when (spec.modeOf(alias)) {
            // AGGREGATE criteria scope the aggregate subqueries instead
            JoinMode.AGGREGATE -> null

            // onCriteria joins the subquery only once the EXISTS filter is active
            JoinMode.EXISTS -> semiJoin(alias, map(alias, withOnCriteria(alias, criteria)!!))

            else -> map(alias, criteria)
          }
        } + aggregateFields.map { (alias, field) -> aggregateCondition(alias, field) }

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

    /**
     * `(SELECT FN(arg) FROM t a WHERE <correlation> AND (<criteria of a>))`.
     * Rendered to text because Spring's subquery expression type is not public; values stay bound.
     */
    private fun aggregateSql(
      alias: String,
      function: Aggregate,
      name: String,
      owner: String
    ): String {
      requireAlias(alias)
      require(spec.modeOf(alias) == JoinMode.AGGREGATE) {
        "$owner aggregates alias '$alias' which is ${spec.modeOf(
          alias
        ) ?: "the root"}; declare it with JoinMode.AGGREGATE"
      }
      require(name.isNotEmpty() || function == Aggregate.COUNT) { "$owner: $function needs a column name" }

      val join = spec.joins.single { it.alias == alias }
      val argument = if (name.isEmpty()) Expressions.asterisk() else column(alias, name)
      val call =
        when (function) {
          Aggregate.COUNT -> Functions.count(argument)
          Aggregate.NONE -> error("not an aggregate")
          else -> SimpleFunction.create(function.name, listOf(argument))
        }

      // Correlation to the outer row is appended as text: Spring's SELECT validator rejects
      // columns of tables that are not in the subquery's own FROM.
      val correlation =
        join.on.joinToString(" AND ") { (left, right) -> "${sql(column(alias, right))} = ${sql(reference(left))}" }
      val scope = withOnCriteria(alias, criteriaByAlias[alias])?.let { Conditions.nest(map(alias, it)) }

      val from = Select.builder().select(call).from(tables.getValue(alias))
      val subselect = renderer.render(if (scope == null) from.build() else from.where(scope).build())
      return "($subselect ${if (scope == null) "WHERE" else "AND"} $correlation)"
    }

    private fun aggregateCondition(
      alias: String,
      mapped: MappedField
    ): Condition {
      val field = mapped.field
      val owner = "QueryField ${mapped.propertyName}"
      val left = Expressions.just(aggregateSql(alias, field.aggregate, field.name, owner))

      return when (field.operator) {
        QueryOperator.EQ -> Conditions.isEqual(left, bind(mapped))
        QueryOperator.NE -> Conditions.isNotEqual(left, bind(mapped))
        QueryOperator.GT -> Conditions.isGreater(left, bind(mapped))
        QueryOperator.GTE -> Conditions.isGreaterOrEqualTo(left, bind(mapped))
        QueryOperator.LT -> Conditions.isLess(left, bind(mapped))
        QueryOperator.LTE -> Conditions.isLessOrEqualTo(left, bind(mapped))
        QueryOperator.IN -> Conditions.`in`(left, bindAll(mapped))
        QueryOperator.NOT_IN -> Conditions.notIn(left, bindAll(mapped))
        QueryOperator.IS_NULL, QueryOperator.NOT_EXISTS -> Conditions.isNull(left)
        QueryOperator.IS_NOT_NULL, QueryOperator.EXISTS -> Conditions.isNull(left).not()
        else -> throw UnsupportedOperationException("$owner: ${field.operator} is not supported on aggregates")
      }
    }

    private fun bind(mapped: MappedField): Expression {
      val value =
        requireNotNull(mapped.value) { "QueryField ${mapped.propertyName}: ${mapped.field.operator} needs a value" }
      return bindValue(value)
    }

    private fun bindAll(mapped: MappedField): List<Expression> {
      val values =
        mapped.value as? Collection<*> ?: throw IllegalArgumentException(
          "QueryField ${mapped.propertyName}: ${mapped.field.operator} requires a Collection value"
        )
      return values.filterNotNull().map(::bindValue)
    }

    private fun bindValue(value: Any): Expression {
      val converted = template.converter.writeValue(value, TypeInformation.of(value.javaClass)) ?: value
      val bound = MutableBindings(markers)
      val marker = bound.bind(converted)
      bindings = Bindings.merge(bindings, bound)
      return SQL.bindMarker(marker.placeholder)
    }

    private fun onCondition(join: JoinSpec.Join): Condition {
      val columns =
        join.on
          .map { (left, right) -> Conditions.isEqual(reference(left), column(join.alias, right)) as Condition }
          .reduce { acc, next -> acc.and(next) }
      val extra = SqlQueryMapper.combine(listOfNotNull(join.onCriteria)) ?: return columns
      return columns.and(Conditions.nest(map(join.alias, extra)))
    }

    /** `onCriteria AND criteria` for a subquery alias; `null` when both are absent. */
    private fun withOnCriteria(
      alias: String,
      criteria: CriteriaDefinition?
    ): Criteria? = SqlQueryMapper.combine(listOfNotNull(spec.onCriteriaOf(alias), criteria))

    /**
     * DTO property or `alias.column`. `ignoreCase` sorts by `LOWER(column)`; it is ignored for aggregate
     * properties, which are sorted by their select alias (not allowed inside a function on every database).
     */
    private fun orderBy(sort: Sort): List<OrderByField> =
      sort
        .map { order ->
          val expression = sortable[order.property] ?: reference(order.property)
          val sortKey = if (order.isIgnoreCase && expression is Column) Functions.lower(expression) else expression
          OrderByField.from(sortKey, order.direction)
        }.toList()

    private fun selectList(type: KClass<*>): List<Expression> {
      val dto = entityOf(type)
      val result = ArrayList<Expression>()

      dto.forEach { property ->
        val owner = "${type.simpleName}.${property.name}"
        require(!property.isEmbedded) { "$owner is @Embedded, which join queries do not support" }
        val selectColumn = property.findAnnotation(SelectColumn::class.java)
        val selectAggregate = property.findAnnotation(SelectAggregate::class.java)
        require(
          selectColumn == null || selectAggregate == null
        ) { "$owner has both @SelectColumn and @SelectAggregate" }

        if (selectAggregate != null) {
          val label = property.columnName.toSql(identifiers)
          result +=
            Expressions.just(
              "${aggregateSql(selectAggregate.table, selectAggregate.function, selectAggregate.name, owner)} AS $label"
            )
          sortable[property.name] = Expressions.just(label)
          return@forEach
        }

        val alias = selectColumn?.table?.ifEmpty { null } ?: spec.rootAlias
        requireAlias(alias)
        require(spec.modeOf(alias)?.joined != false) {
          "$owner selects from ${spec.modeOf(alias)} alias '$alias'; use INNER or LEFT"
        }

        val name = selectColumn?.name?.ifEmpty { null }
        val column =
          if (name ==
            null
          ) {
            column(alias, property.name, fallback = property.columnName)
          } else {
            column(alias, name)
          }

        result += column.`as`(property.columnName)
        sortable[property.name] = column
      }

      return result
    }

    private fun requireAlias(alias: String) =
      require(alias in entities) { "Unknown table alias '$alias'; declared: ${spec.aliases()}" }

    /** Renders a column reference the same way the SQL renderer does. */
    private fun sql(column: Column): String =
      "${column.table!!.referenceName.toSql(identifiers)}.${column.name.toSql(identifiers)}"

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
