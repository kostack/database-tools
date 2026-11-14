package io.github.kostack.database_tools.r2dbc.grid.engine

/** How a table is joined; set by the builder's `join` / `leftJoin` / `existsJoin` / `aggregateJoin`. */
internal enum class JoinMode {
  /** `JOIN` — rows without a match are dropped; columns can be selected. */
  INNER,

  /** `LEFT OUTER JOIN` — rows without a match are kept with `NULL`s; columns can be selected. */
  LEFT,

  /**
   * Filter only, rendered as a semi-join `left IN (SELECT right FROM … WHERE <conditions>)`.
   * Never multiplies root rows and its columns cannot be selected.
   * Skipped entirely when no filter field or condition targets this alias.
   */
  EXISTS,

  /**
   * Source for per-root-row aggregates (`@SelectAggregate`, `@QueryField(aggregate = …)`), rendered as
   * correlated scalar subqueries `(SELECT AVG(o.total) FROM orders o WHERE o.user_id = u.id AND <conditions>)`.
   * Never joined as rows; plain conditions on this alias restrict the rows being aggregated.
   */
  AGGREGATE;

  /** Whether the table is part of the FROM clause (its columns can be selected and referenced). */
  val joined: Boolean get() = this == INNER || this == LEFT

  /** The [io.github.kostack.database_tools.r2dbc.grid.GridQuery] function declaring this mode, for error messages. */
  val builderName: String
    get() =
      when (this) {
        INNER -> "join"
        LEFT -> "leftJoin"
        EXISTS -> "existsJoin"
        AGGREGATE -> "aggregateJoin"
      }
}
