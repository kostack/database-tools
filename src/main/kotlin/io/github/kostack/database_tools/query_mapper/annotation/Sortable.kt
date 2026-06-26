package io.github.kostack.database_tools.query_mapper.annotation

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Sortable(
  /**
   * Public API sort field.
   *
   * Example:
   * sort=createdAt,desc
   */
  val name: String,
  /**
   * DB field.
   *
   * Example:
   * createdAt
   */
  val field: String = ""
)
