package io.github.kostack.database_tools.query_mapper.extension

import io.github.kostack.database_tools.query_mapper.MongoQueryMapper
import io.github.kostack.database_tools.query_mapper.SqlQueryMapper

typealias MongoQuery = org.springframework.data.mongodb.core.query.Query
typealias SqlQuery = org.springframework.data.relational.core.query.Query

fun MongoQuery.toMappedQuery(source: Any): MongoQuery = MongoQueryMapper.toQuery(source)

fun SqlQuery.toMappedQuery(source: Any): SqlQuery = SqlQueryMapper.toQuery(source)
