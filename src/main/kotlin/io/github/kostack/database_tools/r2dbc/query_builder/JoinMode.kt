package io.github.kostack.database_tools.r2dbc.query_builder

enum class JoinMode {
  /** `JOIN` — rows without a match are dropped; columns can be selected. */
  INNER,

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s; columns can be selected. */
  LEFT,

  /**
   * Filter only, rendered as a semi-join `left IN (SELECT right FROM … WHERE <criteria>)`.
   * Never multiplies root rows and its columns cannot be selected.
   * Skipped entirely when no criteria target this alias.
   */
  EXISTS,

  /**
   * Source for per-root-row aggregates (`@SelectAggregate`, `@QueryField(aggregate = …)`), rendered as
   * correlated scalar subqueries `(SELECT AVG(o.total) FROM orders o WHERE o.user_id = u.id AND <criteria>)`.
   * Never joined as rows; plain criteria on this alias restrict the rows being aggregated.
   */
  AGGREGATE;

  /** Whether the table is part of the FROM clause (its columns can be selected and referenced). */
  val joined: Boolean get() = this == INNER || this == LEFT
}
