package io.github.kostack.database_tools.query_mapper.annotation

import org.springframework.data.domain.Sort

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SortableDefaults(
  val defaultSortField: String = "",
  val defaultSortDirection: Sort.Direction = Sort.Direction.DESC,
  val maxPageSize: Int = 25
)
