package io.github.kostack.database_tools.query_mapper.extension

import io.github.kostack.database_tools.query_mapper.MongoQueryMapper
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper
import org.springframework.data.relational.core.query.CriteriaDefinition
import org.springframework.data.relational.core.query.Query

typealias MongoQuery = org.springframework.data.mongodb.core.query.Query
typealias MongoCriteria = org.springframework.data.mongodb.core.query.Criteria
typealias SqlQuery = Query

/** Returns a copy of this query with the criteria mapped from [source] ANDed in. Sort, paging, etc. are kept. */
fun MongoQuery.toMappedQuery(
  source: Any,
  required: MongoCriteria? = null
): MongoQuery = MongoQueryMapper.toQuery(source, this, required)

/** Returns this query with the criteria mapped from [source] ANDed in. Sort, paging and columns are kept. */
fun SqlQuery.toMappedQuery(
  source: Any,
  required: CriteriaDefinition? = null
): SqlQuery = SqlQueryMapper.toQuery(source, this, required)

/** ANDs [criteria] into this query, keeping sort, paging and columns. */
fun SqlQuery.and(criteria: CriteriaDefinition): SqlQuery = SqlQueryMapper.and(this, criteria)
