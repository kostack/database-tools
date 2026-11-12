package io.github.kostack.database_tools.query_mapper.extension

import io.github.kostack.database_tools.query_mapper.MongoQueryMapper
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query

typealias MongoQuery = org.springframework.data.mongodb.core.query.Query
typealias SqlQuery = Query

fun MongoQuery.toMappedQuery(source: Any): MongoQuery = MongoQueryMapper.toQuery(source)

fun SqlQuery.toMappedQuery(source: Any): SqlQuery = SqlQueryMapper.toQuery(source)

fun SqlQuery.andCriteria(requiredCriteria: Criteria): Criteria =
  criteria.orElse(null)?.let { requiredCriteria.and(it) } ?: requiredCriteria
