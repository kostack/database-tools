package io.github.kostack.database_tools.paginator

import io.github.kostack.database_tools.query_mapper.PageRequestMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import kotlin.reflect.KClass

class MongoDbPaginator(
  private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
  /**
   * Selects one page of [targetClass] plus the total count.
   *
   * @param sortableSource class carrying `@Sortable` / `@SortableDefaults` (typically the filter class) to apply
   *   via [PageRequestMapper]: allowlist, default sort, page-size cap. `null` (default) leaves [pageable] as is.
   */
  suspend fun <T : Any> toPagination(
    query: Query,
    pageable: Pageable,
    targetClass: KClass<T>,
    sortableSource: KClass<*>? = null
  ): Pagination<T> =
    coroutineScope {
      val page = sortableSource?.let { PageRequestMapper.toPageRequest(pageable, it) } ?: pageable
      val itemsQuery = Query.of(query).with(page)
      val countQuery = Query.of(query)

      val itemsDeferred =
        async {
          reactiveMongoTemplate
            .find(itemsQuery, targetClass.java)
            .collectList()
            .awaitSingle()
        }

      val totalDeferred =
        async {
          reactiveMongoTemplate
            .count(countQuery, targetClass.java)
            .awaitSingle()
        }

      return@coroutineScope Pagination(
        content = itemsDeferred.await(),
        limit = page.pageSize,
        offset = page.offset,
        total = totalDeferred.await()
      )
    }
}
