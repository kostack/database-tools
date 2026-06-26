package io.github.kostack.database_tools.query_mapper.extension

import io.github.kostack.database_tools.paginator.Pagination
import io.github.kostack.database_tools.query_mapper.PageRequestMapper
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import kotlin.reflect.KClass

fun Pageable.toPageRequest(sortableSource: KClass<*>): PageRequest =
  PageRequestMapper.toPageRequest(
    pageable = this,
    sortableSource = sortableSource
  )

fun <T, R> Pagination<T>.map(transform: (T) -> R): Pagination<R> =
  Pagination(
    content = content.map(transform),
    limit = limit,
    offset = offset,
    total = total
  )
