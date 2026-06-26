package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.Sortable
import io.github.kostack.database_tools.query_mapper.annotation.SortableDefaults
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.findAnnotations

object PageRequestMapper {
  private const val FALLBACK_MAX_PAGE_SIZE = 100

  fun toPageRequest(
    pageable: Pageable,
    sortableSource: KClass<*>
  ): PageRequest {
    val defaults = sortableSource.findAnnotation<SortableDefaults>()

    val defaultSortField =
      defaults
        ?.defaultSortField
        ?.takeIf { it.isNotBlank() }

    val defaultSortDirection =
      defaults
        ?.defaultSortDirection
        ?: Sort.Direction.DESC

    val maxPageSize =
      defaults
        ?.maxPageSize
        ?: FALLBACK_MAX_PAGE_SIZE

    require(maxPageSize > 0) {
      "maxPageSize must be greater than 0"
    }

    val sortableFields =
      sortableSource
        .findAnnotations<Sortable>()
        .associateBy { it.name }

    validateSortFields(
      sort = pageable.sort,
      allowedFields = sortableFields.keys
    )

    val mappedOrders =
      pageable.sort
        .filter { order -> order.property in sortableFields }
        .map { order ->
          val sortable = sortableFields.getValue(order.property)
          val mongoField = sortable.field.ifBlank { sortable.name }

          Sort
            .Order(
              order.direction,
              mongoField,
              order.nullHandling
            ).let {
              if (order.isIgnoreCase) it.ignoreCase() else it
            }
        }.toList()

    val sort =
      when {
        mappedOrders.isNotEmpty() -> {
          Sort.by(mappedOrders)
        }

        defaultSortField != null -> {
          val sortable =
            sortableFields[defaultSortField]
              ?: throw IllegalArgumentException(
                "Default sort field '$defaultSortField' is not allowed. Allowed fields: ${sortableFields.keys.sorted()}"
              )

          Sort.by(
            defaultSortDirection,
            sortable.field.ifBlank { sortable.name }
          )
        }

        else -> {
          Sort.unsorted()
        }
      }

    return PageRequest.of(
      pageable.pageNumber.coerceAtLeast(0),
      pageable.pageSize.coerceIn(1, maxPageSize),
      sort
    )
  }

  private fun validateSortFields(
    sort: Sort,
    allowedFields: Set<String>
  ) {
    val invalidFields =
      sort
        .map { it.property }
        .filterNot { it in allowedFields }
        .toList()

    require(invalidFields.isEmpty()) {
      "Unsupported sort fields: $invalidFields. Allowed fields: ${allowedFields.sorted()}"
    }
  }
}
