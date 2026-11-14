package io.github.kostack.database_tools.query_mapper.extension

import io.github.kostack.database_tools.query_mapper.MongoQueryMapper

typealias MongoQuery = org.springframework.data.mongodb.core.query.Query
typealias MongoCriteria = org.springframework.data.mongodb.core.query.Criteria

fun MongoQuery.toMappedQuery(
  source: Any,
  required: MongoCriteria? = null
): MongoQuery = MongoQueryMapper.toQuery(source, this, required)
