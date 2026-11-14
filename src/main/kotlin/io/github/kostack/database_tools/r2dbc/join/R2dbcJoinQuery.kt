package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.Aggregate
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
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.Select
import org.jooq.SelectField
import org.jooq.SortField
import org.jooq.Table
import org.jooq.conf.ParamType
import org.jooq.conf.RenderKeywordCase
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.springframework.data.core.TypeInformation
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
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.CriteriaDefinition.Combinator
import org.springframework.data.relational.core.query.CriteriaDefinition.Comparator
import org.springframework.data.relational.core.query.Query
import org.springframework.data.relational.core.sql.SqlIdentifier
import org.springframework.data.util.Pair
import org.springframework.r2dbc.core.PreparedOperation
import org.springframework.r2dbc.core.binding.BindTarget
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
  private val metadata = JooqMetadataResolver(template)
  private val dsl: DSLContext by lazy { DSL.using(jooqDialect(dialect), jooqSettings(dialect)) }

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

  /** Builds the statement; [resultType] `null` produces `SELECT COUNT(*)` without sort and paging. */
  internal fun prepare(
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

    /** Plain criteria per alias: `required AND (query criteria, root only) AND filter`. */
    private lateinit var criteriaByAlias: Map<String, Criteria>

    /** DTO property name -> expression to sort by, and whether it is a plain column (eligible for `LOWER`). */
    private val sortable = HashMap<String, kotlin.Pair<Field<Any?>, Boolean>>()

    fun build(
      filter: Any?,
      query: Query,
      required: Map<String, CriteriaDefinition>,
      sort: Sort
    ): PreparedOperation<Select<*>> {
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

      val selectList: List<SelectField<*>> = if (resultType == null) listOf(DSL.count()) else selectList(resultType)

      var from: Table<*> = tables.getValue(spec.rootAlias)
      spec.joins.filter { it.mode.joined }.forEach { join ->
        val table = tables.getValue(join.alias)
        from =
          if (join.mode == JoinMode.LEFT) {
            from.leftOuterJoin(table).on(onCondition(join))
          } else {
            from.join(table).on(onCondition(join))
          }
      }

      val filtered = dsl.select(selectList).from(from).where(whereCondition(aggregateFields.map { aliasOf(it) to it }))

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
          val criteria = criteriaByAlias[alias] ?: return@mapNotNull null
          when (spec.modeOf(alias)) {
            // AGGREGATE criteria scope the aggregate subqueries instead
            JoinMode.AGGREGATE -> null

            // onCriteria joins the subquery only once the EXISTS filter is active
            JoinMode.EXISTS -> semiJoin(alias, map(alias, withOnCriteria(alias, criteria)!!))

            else -> map(alias, criteria)
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

      val scope = withOnCriteria(alias, criteriaByAlias[alias])?.let { map(alias, it) }
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

      return when (field.operator) {
        QueryOperator.EQ -> left.eq(bind(mapped))
        QueryOperator.NE -> left.ne(bind(mapped))
        QueryOperator.GT -> left.gt(bind(mapped))
        QueryOperator.GTE -> left.ge(bind(mapped))
        QueryOperator.LT -> left.lt(bind(mapped))
        QueryOperator.LTE -> left.le(bind(mapped))
        QueryOperator.IN -> left.`in`(bindAll(mapped))
        QueryOperator.NOT_IN -> left.notIn(bindAll(mapped))
        QueryOperator.IS_NULL, QueryOperator.NOT_EXISTS -> left.isNull
        QueryOperator.IS_NOT_NULL, QueryOperator.EXISTS -> left.isNotNull
        else -> throw UnsupportedOperationException("$owner: ${field.operator} is not supported on aggregates")
      }
    }

    private fun bind(mapped: MappedField): Field<Any?> {
      val value =
        requireNotNull(mapped.value) { "QueryField ${mapped.propertyName}: ${mapped.field.operator} needs a value" }
      return param(template.converter.writeValue(value, TypeInformation.of(value.javaClass)) ?: value)
    }

    private fun bindAll(mapped: MappedField): List<Field<Any?>> {
      val values =
        mapped.value as? Collection<*> ?: throw IllegalArgumentException(
          "QueryField ${mapped.propertyName}: ${mapped.field.operator} requires a Collection value"
        )
      return values.filterNotNull().map {
        param(template.converter.writeValue(it, TypeInformation.of(it.javaClass)) ?: it)
      }
    }

    private fun onCondition(join: JoinSpec.Join): Condition {
      val columns = join.on.map { (left, right) -> reference(left).eq(column(join.alias, right)) }
      val extra = SqlQueryMapper.combine(listOfNotNull(join.onCriteria))?.let { map(join.alias, it) }
      return allOf(columns + listOfNotNull(extra))
    }

    /** `onCriteria AND criteria` for a subquery alias; `null` when both are absent. */
    private fun withOnCriteria(
      alias: String,
      criteria: CriteriaDefinition?
    ): Criteria? = SqlQueryMapper.combine(listOfNotNull(spec.onCriteriaOf(alias), criteria))

    /**
     * Maps Spring [CriteriaDefinition]s on [alias] to a jOOQ condition, following Spring's R2DBC `QueryMapper`:
     * property names map to their columns and values are converted to the property type.
     */
    private fun map(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition = unroll(alias, criteria) ?: DSL.noCondition()

    /** A criteria chain links back to its predecessors: walk to the head, then combine forwards. */
    private fun unroll(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition? =
      generateSequence(criteria) { if (it.hasPrevious()) it.previous else null }
        .toList()
        .asReversed()
        .fold(null as Condition?) { mapped, next -> combine(mapped, next, condition(alias, next)) }

    private fun condition(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition? =
      when {
        criteria.isEmpty -> {
          null
        }

        criteria.isGroup -> {
          criteria.group
            .filterNot { it.isEmpty }
            .fold(null as Condition?) { mapped, next -> combine(mapped, next, unroll(alias, next)) }
        }

        else -> {
          predicate(alias, criteria)
        }
      }

    private fun combine(
      mapped: Condition?,
      criteria: CriteriaDefinition,
      condition: Condition?
    ): Condition? =
      when {
        condition == null -> mapped
        mapped == null -> condition
        criteria.combinator == Combinator.OR -> mapped.or(condition)
        else -> mapped.and(condition)
      }

    private fun predicate(
      alias: String,
      criteria: CriteriaDefinition
    ): Condition {
      val name = requireNotNull(criteria.column) { "Criteria on alias '$alias' has no column" }.reference
      val property = entities.getValue(alias).getPersistentProperty(name)
      val type: TypeInformation<*> = property?.typeInformation ?: TypeInformation.OBJECT
      val elementType: TypeInformation<*> = type.actualType ?: TypeInformation.OBJECT
      val column = column(alias, name)
      val ignoreCase = criteria.isIgnoreCase
      val compared = if (ignoreCase) DSL.upper(column.text()).any() else column

      fun convert(
        value: Any?,
        type: TypeInformation<*>
      ): Any? = value?.let { template.converter.writeValue(it, type) ?: it }

      fun value(upper: Boolean = false): Field<Any?> =
        param(convert(criteria.value, type)).let { if (upper && ignoreCase) DSL.upper(it.text()).any() else it }

      fun values(): List<Field<Any?>> =
        when (val value = criteria.value) {
          is Iterable<*> -> value.map { param(convert(it, elementType)) }
          else -> listOf(param(convert(value, type)))
        }

      fun range(): kotlin.Pair<Field<Any?>, Field<Any?>> {
        @Suppress("UNCHECKED_CAST")
        val pair =
          requireNotNull(criteria.value as? Pair<Any, Any>) { "${criteria.comparator} on '$name' needs a Pair" }
        val wrap = { it: Any ->
          param(convert(it, elementType)).let { p -> if (ignoreCase) DSL.upper(p.text()).any() else p }
        }
        return wrap(pair.first) to wrap(pair.second)
      }

      return when (criteria.comparator) {
        Comparator.EQ -> compared.eq(value(upper = true))
        Comparator.NEQ -> compared.ne(value(upper = true))
        Comparator.LT -> column.lt(value())
        Comparator.LTE -> column.le(value())
        Comparator.GT -> column.gt(value())
        Comparator.GTE -> column.ge(value())
        Comparator.LIKE -> compared.like(value(upper = true).text())
        Comparator.NOT_LIKE -> compared.notLike(value(upper = true).text())
        Comparator.IN -> compared.`in`(values())
        Comparator.NOT_IN -> compared.notIn(values())
        Comparator.IS_NULL -> column.isNull
        Comparator.IS_NOT_NULL -> column.isNotNull
        Comparator.IS_TRUE, Comparator.IS_FALSE -> column.eq(value())
        Comparator.BETWEEN -> range().let { (low, high) -> compared.between(low, high) }
        Comparator.NOT_BETWEEN -> range().let { (low, high) -> compared.notBetween(low, high) }
        Comparator.INITIAL, null -> throw IllegalArgumentException("Criteria on '$name' has no comparator")
      }
    }

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
      return column(alias, qualified.substringAfter('.'))
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
    fun param(value: Any?): Field<Any?> = DSL.value(value).any()

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
