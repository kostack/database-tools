package io.github.kostack.database_tools.r2dbc.query_builder

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.Aggregate
import io.github.kostack.database_tools.query_mapper.JooqConditionMapper
import io.github.kostack.database_tools.query_mapper.QueryMapper
import io.github.kostack.database_tools.query_mapper.QueryMapper.MappedField
import io.github.kostack.database_tools.query_mapper.QueryOperator
import io.github.kostack.database_tools.query_mapper.annotation.SelectAggregate
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.Select
import org.jooq.SelectConditionStep
import org.jooq.SelectField
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SortField
import org.jooq.Table
import org.jooq.conf.ParamType
import org.jooq.conf.RenderKeywordCase
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.H2Dialect
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.data.r2dbc.dialect.R2dbcDialect
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.sql.SqlIdentifier
import org.springframework.r2dbc.core.PreparedOperation
import org.springframework.r2dbc.core.binding.BindTarget
import kotlin.reflect.KClass

/**
 * Internal engine behind [R2dbcQueryBuilder]: runs `@QueryField`
 * filters against a root table plus joined tables (see [JoinSpec]) and maps each row into a DTO whose
 * properties are placed with [SelectColumn].
 *
 * Criteria for each alias are combined as `required AND (query criteria, root only) AND mapped filter`.
 * All values are bound parameters; criteria column names are mapped through each table's entity.
 * With `INNER`/`LEFT` one-to-many joins every joined row is one result (and one count).
 */
internal class JoinQueryEngine(
  private val template: R2dbcEntityTemplate
) {
  private val dialect by lazy { template.dataAccessStrategy.dialect as R2dbcDialect }
  private val metadata = JooqMetadataResolver(template)
  private val dsl: DSLContext by lazy { DSL.using(jooqDialect(dialect), jooqSettings(dialect)) }
  private val joins by lazy { JooqJoinRenderer(dsl, metadata) }
  private val conditions = JooqConditionMapper(template.converter)

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
   * Selects one page plus the total count. `ignoreCase()` orders in [pageable] are supported.
   *
   * To allowlist sort fields, map the pageable first: `pageable.toPageRequest(Filter::class)`.
   */
  suspend fun <T : Any> toPagination(
    spec: JoinSpec,
    resultType: KClass<T>,
    pageable: Pageable,
    filter: Any? = null,
    query: Query = Query.empty(),
    required: Map<String, CriteriaDefinition> = emptyMap()
  ): Pagination<T> =
    coroutineScope {
      // Paging goes into the query; the sort is passed separately because Spring's Query rejects ignoreCase orders.
      val paged = query.with(PageRequest.of(pageable.pageNumber, pageable.pageSize))
      val items = async { select(spec, resultType, filter, paged, required, pageable.sort).toList() }
      val total = async { count(spec, filter, query, required) }

      Pagination(
        content = items.await(),
        limit = pageable.pageSize,
        offset = pageable.offset,
        total = total.await()
      )
    }

  /**
   * `SELECT <select> FROM root JOIN ...` with the `INNER` / `LEFT` joins of [spec] (including their `onCriteria`)
   * and [required] criteria of those tables in `WHERE`, rendered for this template's database.
   */
  fun selectFrom(
    spec: JoinSpec,
    select: Collection<SelectFieldOrAsterisk>,
    required: Map<String, CriteriaDefinition>
  ): SelectConditionStep<Record> {
    val subqueryAliases = spec.joins.filterNot { it.mode.joined }.map { it.alias }
    require(subqueryAliases.isEmpty()) {
      "select() renders INNER and LEFT joins only; EXISTS / AGGREGATE aliases $subqueryAliases need fetch() / fetchPage()"
    }
    val target = { alias: String ->
      val type = if (alias == spec.rootAlias) spec.root else spec.joins.single { it.alias == alias }.entity
      JooqConditionMapper.Target(metadata.entity(type)) { name -> joins.field(spec, alias, name) }
    }
    val where =
      required.map { (alias, criteria) ->
        require(alias in spec.aliases()) { "Unknown table alias '$alias'; declared: ${spec.aliases()}" }
        conditions.map(criteria, target(alias))
      }
    return joins
      .from(spec, select) { join -> join.onCriteria?.let { conditions.map(it, target(join.alias)) } }
      .where(where.filterNotNull())
  }

  /** Column [name] (entity property or raw column) of [alias] in [spec]. */
  fun field(
    spec: JoinSpec,
    alias: String,
    name: String
  ): Field<Any?> = joins.field(spec, alias, name)

  /** Builds the statement; [resultType] `null` produces `SELECT COUNT(*)` without sort and paging. */
  fun prepare(
    spec: JoinSpec,
    filter: Any?,
    query: Query,
    required: Map<String, CriteriaDefinition>,
    resultType: KClass<*>?,
    sort: Sort = Sort.unsorted()
  ): PreparedOperation<Select<*>> = Statement(spec, resultType).build(filter, query, required, query.sort.and(sort))

  /** Builds one jOOQ select; jOOQ renders the SQL and collects bind values in text order. */
  private inner class Statement(
    private val spec: JoinSpec,
    private val resultType: KClass<*>?
  ) {
    private val types: Map<String, KClass<*>> =
      (listOf(spec.rootAlias to spec.root) + spec.joins.map { it.alias to it.entity }).toMap()

    private val entities: Map<String, RelationalPersistentEntity<*>> =
      types.mapValues { (_, type) ->
        metadata.entity(
          type
        )
      }

    private val tables: Map<String, Table<*>> = types.mapValues { (alias, type) -> metadata.table(type, alias) }

    /** Plain conditions per alias: `required AND (query criteria, root only) AND filter`. */
    private lateinit var conditionsByAlias: Map<String, Condition>

    /** DTO property name -> expression to sort by, and whether it is a plain column (eligible for `LOWER`). */
    private val sortable = HashMap<String, kotlin.Pair<Field<Any?>, Boolean>>()

    fun build(
      filter: Any?,
      query: Query,
      required: Map<String, CriteriaDefinition>,
      sort: Sort
    ): PreparedOperation<Select<*>> {
      val fields = filter?.let { QueryMapper.activeFields(it) }.orEmpty()
      val aliasOf = { field: MappedField -> field.field.table.ifEmpty { spec.rootAlias } }
      val (aggregateFields, plainFields) = fields.partition { it.field.aggregate != Aggregate.NONE }

      (fields.map(aliasOf) + required.keys).forEach(::requireAlias)

      val plain = plainFields.groupBy(aliasOf).mapValues { (alias, fields) -> fields.map { filter(alias, it) } }
      conditionsByAlias =
        spec
          .aliases()
          .mapNotNull { alias ->
            val queryCriteria = query.criteria.orElse(null)?.takeIf { alias == spec.rootAlias }
            val parts = listOfNotNull(required[alias], queryCriteria).mapNotNull { criteria(alias, it) }
            (parts + plain[alias].orEmpty()).takeIf { it.isNotEmpty() }?.let { alias to allOf(it) }
          }.toMap()

      val selectList: List<SelectField<*>> = if (resultType == null) listOf(DSL.count()) else selectList(resultType)

      val filtered =
        joins
          .from(spec, selectList, ::onCriteria)
          .where(whereCondition(aggregateFields.map { aliasOf(it) to it }))

      val select: Select<*> =
        when {
          resultType == null -> {
            filtered
          }

          query.isLimited -> {
            filtered
              .orderBy(orderBy(sort))
              .limit(DSL.inline(query.limit))
              .offset(DSL.inline(query.offset.coerceAtLeast(0)))
          }

          else -> {
            filtered.orderBy(orderBy(sort))
          }
        }

      return JoinOperation(select, select.sql, select.bindValues)
    }

    private fun whereCondition(aggregateFields: List<kotlin.Pair<String, MappedField>>): Condition =
      allOf(
        spec.aliases().mapNotNull { alias ->
          val condition = conditionsByAlias[alias] ?: return@mapNotNull null
          when (spec.modeOf(alias)) {
            // AGGREGATE conditions scope the aggregate subqueries instead
            JoinMode.AGGREGATE -> null

            // onCriteria joins the subquery only once the EXISTS filter is active
            JoinMode.EXISTS -> semiJoin(alias, withOnCriteria(alias, condition)!!)

            else -> condition
          }
        } + aggregateFields.map { (alias, field) -> aggregateCondition(alias, field) }
      )

    private fun semiJoin(
      alias: String,
      condition: Condition
    ): Condition {
      val join = spec.joins.single { it.alias == alias }
      val left = join.on.map { (left, _) -> reference(left) }
      val right = join.on.map { (_, right) -> column(alias, right) }
      val table = tables.getValue(alias)

      return if (left.size == 1) {
        left.single().`in`(DSL.select(right.single()).from(table).where(condition))
      } else {
        DSL.row(left).`in`(DSL.select(right).from(table).where(condition))
      }
    }

    /** `(SELECT FN(arg) FROM t a WHERE (<criteria of a>) AND <correlation to the outer row>)`. */
    private fun aggregate(
      alias: String,
      function: Aggregate,
      name: String,
      owner: String
    ): Field<Any?> {
      requireAlias(alias)
      require(spec.modeOf(alias) == JoinMode.AGGREGATE) {
        "$owner aggregates alias '$alias' which is ${spec.modeOf(
          alias
        ) ?: "the root"}; declare it with JoinMode.AGGREGATE"
      }
      require(name.isNotEmpty() || function == Aggregate.COUNT) { "$owner: $function needs a column name" }

      val join = spec.joins.single { it.alias == alias }
      val argument = name.ifEmpty { null }?.let { column(alias, it) }
      val call: Field<*> =
        when (function) {
          Aggregate.COUNT -> if (argument == null) DSL.count() else DSL.count(argument)
          Aggregate.SUM -> DSL.sum(argument!!.numeric())
          Aggregate.AVG -> DSL.avg(argument!!.numeric())
          Aggregate.MIN -> DSL.min(argument!!)
          Aggregate.MAX -> DSL.max(argument!!)
          Aggregate.NONE -> error("not an aggregate")
        }

      val scope = withOnCriteria(alias, conditionsByAlias[alias])
      val correlation = join.on.map { (left, right) -> column(alias, right).eq(reference(left)) }

      return DSL.field(DSL.select(call.any()).from(tables.getValue(alias)).where(listOfNotNull(scope) + correlation))
    }

    private fun aggregateCondition(
      alias: String,
      mapped: MappedField
    ): Condition {
      val field = mapped.field
      val owner = "QueryField ${mapped.propertyName}"
      val left = aggregate(alias, field.aggregate, field.name, owner)

      if (field.operator !in AGGREGATE_OPERATORS) {
        throw UnsupportedOperationException("$owner: ${field.operator} is not supported on aggregates")
      }
      // Aggregate values are converted by their own type: the aggregate result has no entity property.
      return conditions.compare(left, mapped)
    }

    /** A join's `onCriteria` mapped through its entity; the column pairs are rendered by [JooqJoinRenderer]. */
    private fun onCriteria(join: JoinSpec.Join): Condition? = join.onCriteria?.let { criteria(join.alias, it) }

    /** `onCriteria AND condition` for a subquery alias; `null` when both are absent. */
    private fun withOnCriteria(
      alias: String,
      condition: Condition?
    ): Condition? =
      listOfNotNull(spec.joins.single { it.alias == alias }.let(::onCriteria), condition)
        .takeIf { it.isNotEmpty() }
        ?.let(::allOf)

    /** A `@QueryField` filter on a plain column of [alias]. */
    private fun filter(
      alias: String,
      mapped: MappedField
    ): Condition = conditions.map(mapped, target(alias))

    /** Spring criteria on [alias]; `null` when empty. */
    private fun criteria(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition? = conditions.map(criteria, target(alias))

    private fun target(alias: String) = JooqConditionMapper.Target(entities.getValue(alias)) { column(alias, it) }

    /**
     * DTO property or `alias.column`. `ignoreCase` sorts by `LOWER(column)`; it is ignored for aggregate
     * properties, which are sorted by their select alias (not allowed inside a function on every database).
     */
    private fun orderBy(sort: Sort): List<SortField<*>> =
      sort
        .map { order ->
          val (expression, isColumn) = sortable[order.property] ?: (reference(order.property) to true)
          val sortKey = if (order.isIgnoreCase && isColumn) DSL.lower(expression.text()) else expression
          if (order.isAscending) sortKey.asc() else sortKey.desc()
        }.toList()

    private fun selectList(type: KClass<*>): List<SelectField<*>> {
      val dto = metadata.entity(type)
      val result = ArrayList<SelectField<*>>()

      dto.forEach { property: RelationalPersistentProperty ->
        val owner = "${type.simpleName}.${property.name}"
        require(!property.isEmbedded) { "$owner is @Embedded, which join queries do not support" }
        val selectColumn = property.findAnnotation(SelectColumn::class.java)
        val selectAggregate = property.findAnnotation(SelectAggregate::class.java)
        require(
          selectColumn == null || selectAggregate == null
        ) { "$owner has both @SelectColumn and @SelectAggregate" }

        val label = metadata.name(property.columnName)

        if (selectAggregate != null) {
          result += aggregate(selectAggregate.table, selectAggregate.function, selectAggregate.name, owner).`as`(label)
          sortable[property.name] = DSL.field(label) to false
          return@forEach
        }

        val alias = selectColumn?.table?.ifEmpty { null } ?: spec.rootAlias
        requireAlias(alias)
        require(spec.modeOf(alias)?.joined != false) {
          "$owner selects from ${spec.modeOf(alias)} alias '$alias'; use INNER or LEFT"
        }

        val column =
          selectColumn?.name?.ifEmpty { null }?.let { column(alias, it) }
            ?: column(alias, property.name, fallback = property.columnName)

        result += column.`as`(label)
        sortable[property.name] = column to true
      }

      return result
    }

    private fun requireAlias(alias: String) =
      require(alias in entities) { "Unknown table alias '$alias'; declared: ${spec.aliases()}" }

    /** `alias.name` or bare `name` (root table). */
    private fun reference(qualified: String): Field<Any?> {
      val alias = qualified.substringBefore('.', spec.rootAlias)
      require(alias in entities) { "Unknown table alias '$alias' in '$qualified'; declared: ${spec.aliases()}" }
      return joins.reference(spec, qualified)
    }

    /** Entity property name -> its column; otherwise [fallback] or [name] as a raw (validated) column. */
    private fun column(
      alias: String,
      name: String,
      fallback: SqlIdentifier? = null
    ): Field<Any?> = metadata.field(types.getValue(alias), alias, name, fallback)
  }

  private class JoinOperation(
    private val select: Select<*>,
    private val sql: String,
    private val values: List<Any?>
  ) : PreparedOperation<Select<*>> {
    override fun getSource(): Select<*> = select

    override fun toQuery(): String = sql

    // Both `?` and `$n` markers are positional, so binding by index matches the rendered text.
    override fun bindTo(target: BindTarget) =
      values.forEachIndexed { index, value ->
        if (value == null) target.bindNull(index, Any::class.java) else target.bind(index, value)
      }
  }

  private companion object {
    /** Comparisons that make sense on an aggregate value (no text matching). */
    val AGGREGATE_OPERATORS =
      setOf(
        QueryOperator.EQ,
        QueryOperator.NE,
        QueryOperator.GT,
        QueryOperator.GTE,
        QueryOperator.LT,
        QueryOperator.LTE,
        QueryOperator.IN,
        QueryOperator.NOT_IN,
        QueryOperator.IS_NULL,
        QueryOperator.NOT_EXISTS,
        QueryOperator.IS_NOT_NULL,
        QueryOperator.EXISTS
      )

    /** `AND` of [conditions]; no conditions render nothing (`DSL.and()` of nothing would render `TRUE`). */
    fun allOf(conditions: List<Condition>): Condition =
      if (conditions.isEmpty()) DSL.noCondition() else DSL.and(conditions)

    @Suppress("UNCHECKED_CAST")
    fun Field<*>.any(): Field<Any?> = this as Field<Any?>

    @Suppress("UNCHECKED_CAST")
    fun Field<*>.text(): Field<String> = this as Field<String>

    @Suppress("UNCHECKED_CAST")
    fun Field<*>.numeric(): Field<Number> = this as Field<Number>

    fun jooqDialect(dialect: R2dbcDialect): SQLDialect =
      when (dialect) {
        is H2Dialect -> SQLDialect.H2
        is MySqlDialect -> SQLDialect.MYSQL
        is PostgresDialect -> SQLDialect.POSTGRES
        else -> SQLDialect.DEFAULT
      }

    /** Renders the driver's own bind markers: `?` (MySQL) or `$1, $2, ...` (Postgres, H2). */
    fun jooqSettings(dialect: R2dbcDialect): Settings {
      val settings = Settings().withRenderKeywordCase(RenderKeywordCase.UPPER)
      return when (
        val placeholder =
          dialect.bindMarkersFactory
            .create()
            .next()
            .placeholder
      ) {
        "?" -> settings.withParamType(ParamType.INDEXED)

        "$1" -> settings.withParamType(ParamType.NAMED).withRenderNamedParamPrefix("$")

        else -> throw UnsupportedOperationException(
          "${dialect::class.simpleName} bind markers ('$placeholder') are not supported by join queries"
        )
      }
    }
  }
}
