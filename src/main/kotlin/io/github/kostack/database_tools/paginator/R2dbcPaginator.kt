package io.github.kostack.database_tools.paginator

import io.github.kostack.database_tools.query_mapper.PageRequestMapper
import io.github.kostack.database_tools.r2dbc.join.JoinSpec
import io.github.kostack.database_tools.r2dbc.join.R2dbcJoinQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Query
import kotlin.reflect.KClass

class R2dbcPaginator(
  private val entityTemplate: R2dbcEntityTemplate
) {
  private val joinQuery by lazy { R2dbcJoinQuery(entityTemplate) }

  /**
   * Selects one page of [targetClass] plus the total count.
   *
   * Sorts with `ignoreCase()` orders are rendered as `LOWER(column)`. Spring's `Query` cannot express them,
   * so such requests run through [R2dbcJoinQuery] with a single-table spec; a column projection
   * (`Query.columns`) is not supported in that case.
   *
   * @param sortableSource class carrying `@Sortable` / `@SortableDefaults` (typically the filter class) to apply
   *   via [PageRequestMapper]: allowlist, default sort, page-size cap. `null` (default) leaves [pageable] as is.
   */
  suspend fun <T : Any> toPagination(
    query: Query,
    pageable: Pageable,
    targetClass: KClass<T>,
    sortableSource: KClass<*>? = null
  ): Pagination<T> {
    val page = sortableSource?.let { PageRequestMapper.toPageRequest(pageable, it) } ?: pageable

    if (page.sort.any { it.isIgnoreCase }) {
      require(query.columns.isEmpty()) {
        "Column projection is not supported together with ignoreCase sorting"
      }
      return joinQuery.toPagination(
        JoinSpec.from(targetClass, ROOT_ALIAS),
        targetClass,
        page,
        query = query,
        sortableSource = null
      )
    }

    return templatePagination(query, page, targetClass)
  }

  private suspend fun <T : Any> templatePagination(
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

  private companion object {
    const val ROOT_ALIAS = "t"
  }
}
