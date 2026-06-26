package io.github.kostack.database_tools.paginator

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class MongoDbPaginator(
  private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
  suspend fun <T : Any> toPagination(
    query: Query,
    pageable: Pageable,
    targetClass: KClass<T>
  ): Pagination<T> =
    coroutineScope {
      val itemsQuery = Query.of(query).with(pageable)
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
        limit = pageable.pageSize,
        offset = pageable.offset,
        total = totalDeferred.await()
      )
    }
}
