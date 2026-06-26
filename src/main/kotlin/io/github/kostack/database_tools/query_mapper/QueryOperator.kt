package io.github.kostack.database_tools.query_mapper

enum class QueryOperator {
  EQ,
  NE,

  CONTAINS,
  CONTAINS_IGNORE_CASE,
  STARTS_WITH,
  STARTS_WITH_IGNORE_CASE,
  ENDS_WITH,
  ENDS_WITH_IGNORE_CASE,

  REGEX,
  REGEX_IGNORE_CASE,

  GT,
  GTE,
  LT,
  LTE,

  IN,
  NOT_IN,

  EXISTS,
  NOT_EXISTS,

  IS_NULL,
  IS_NOT_NULL
}
