package io.github.kostack.database_tools.paginator

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class R2dbcPaginator(
  private val entityTemplate: R2dbcEntityTemplate
) {
  suspend fun <T : Any> toPagination(
    query: Query,
    pageable: Pageable,
    targetClass: KClass<T>
  ): Pagination<T> =
    coroutineScope {
      val pagedQuery = query.with(pageable)

      val countQuery =
        query.criteria
          .map { criteria -> Query.query(criteria) }
          .orElseGet { Query.empty() }

      val itemsDeferred =
        async {
          entityTemplate
            .select(targetClass.java)
            .matching(pagedQuery)
            .all()
            .collectList()
            .awaitSingle()
        }

      val totalDeferred =
        async {
          entityTemplate
            .count(countQuery, targetClass.java)
            .awaitSingle()
        }

      return@coroutineScope Pagination(
        content = itemsDeferred.await(),
        limit = pageable.pageSize,
        offset = pageable.offset,
        total = totalDeferred.await()
      )
    }
}
