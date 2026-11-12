package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.query_mapper.Aggregate
import io.github.kostack.database_tools.query_mapper.annotation.SelectAggregate
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
import io.r2dbc.spi.ConnectionFactories
import org.junit.jupiter.api.Test
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.binding.BindTarget
import kotlin.test.assertEquals

/** Renders with the MySQL dialect (positional `?` markers) without a database. */
class R2dbcJoinQuerySqlTest {
  private val joinQuery =
    R2dbcJoinQuery(
      R2dbcEntityTemplate(
        DatabaseClient.create(ConnectionFactories.get("r2dbc:h2:mem:///unused")),
        MySqlDialect.INSTANCE
      )
    )

  private val spec =
    JoinSpec
      .from(JoinUser::class, "u")
      .join(JoinOrder::class, "o", "u.id" to "userId")
      .join(JoinItem::class, "i", "o.id" to "order_id", mode = JoinMode.EXISTS)

  private class RecordingTarget : BindTarget {
    val values = sortedMapOf<Int, Any?>()

    override fun bind(
      identifier: String,
      value: Any
    ) = error("named binding not expected for MySQL")

    override fun bind(
      index: Int,
      value: Any
    ) {
      values[index] = value
    }

    override fun bindNull(
      identifier: String,
      type: Class<*>
    ) = error("unexpected")

    override fun bindNull(
      index: Int,
      type: Class<*>
    ) {
      values[index] = null
    }
  }

  @Test
  fun `renders joins, semi-join and positional binds in text order`() {
    val operation =
      joinQuery.prepare(
        spec,
        UserOrderFilter(name = "ali", status = "PAID", sku = "A"),
        Query
          .empty()
          .sort(
            org.springframework.data.domain.Sort
              .by("orderId")
          ).limit(5)
          .offset(10),
        mapOf("u" to Criteria.where("id").greaterThan(0), "o" to Criteria.where("total").greaterThan(1)),
        UserOrderRow::class
      )

    assertEquals(
      "SELECT u.`id` AS `id`, u.`name` AS `name`, " +
        "o.`id` AS `order_id`, o.`status` AS `status`, o.`total` AS `order_total` " +
        "FROM `join_user` u JOIN `join_order` o ON u.`id` = o.`user_id` " +
        "WHERE (u.`id` > ? AND (u.`name` LIKE ?)) AND (o.`total` > ? AND (o.`status` = ?)) " +
        "AND (o.`id` IN (SELECT i.order_id FROM `join_item` i WHERE i.`sku` = ?)) " +
        "ORDER BY o.`id` ASC LIMIT 10, 5",
      operation.toQuery()
    )

    val target = RecordingTarget()
    operation.bindTo(target)
    // values are converted to the entity property type (id: Long, total: Int)
    assertEquals(listOf<Any?>(0L, "%ali%", 1, "PAID", "A"), target.values.values.toList())
  }

  @Test
  fun `count has no select list, sort or paging`() {
    val operation =
      joinQuery.prepare(
        spec,
        UserOrderFilter(status = "PAID"),
        Query
          .empty()
          .sort(
            org.springframework.data.domain.Sort
              .by("orderId")
          ).limit(5),
        emptyMap(),
        resultType = null
      )

    assertEquals(
      "SELECT COUNT(*) FROM `join_user` u JOIN `join_order` o ON u.`id` = o.`user_id` WHERE o.`status` = ?",
      operation.toQuery()
    )
  }

  @Test
  fun `aggregate binds follow text order across select list and where`() {
    data class Row(
      val id: Long,
      @SelectAggregate(table = "s", function = Aggregate.COUNT) val orderCount: Long,
      @SelectAggregate(table = "s", function = Aggregate.AVG, name = "total") val avgTotal: Double?
    )

    val statsSpec =
      JoinSpec.from(JoinUser::class, "u").join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)

    val operation =
      joinQuery.prepare(
        statsSpec,
        UserStatsFilter(status = "PAID", minOrders = 2),
        Query.empty().sort(
          org.springframework.data.domain.Sort
            .by("avgTotal")
        ),
        mapOf("u" to Criteria.where("id").greaterThan(0)),
        Row::class
      )

    val count = "SELECT COUNT(*) FROM `join_order` s WHERE (s.`status` = ?) AND s.`user_id` = u.`id`"
    assertEquals(
      "SELECT u.`id` AS `id`, " +
        "($count) AS `order_count`, " +
        "(SELECT AVG(s.`total`) FROM `join_order` s WHERE (s.`status` = ?) AND s.`user_id` = u.`id`) AS `avg_total` " +
        "FROM `join_user` u " +
        "WHERE (u.`id` > ?) AND (($count) >= ?) " +
        "ORDER BY `avg_total` ASC",
      operation.toQuery()
    )

    val target = RecordingTarget()
    operation.bindTo(target)
    assertEquals(listOf<Any?>("PAID", "PAID", 0L, "PAID", 2L), target.values.values.toList())
  }

  @Test
  fun `on criteria binds sit between select list and where`() {
    data class Row(
      val id: Long,
      @SelectColumn(table = "o") val status: String?,
      @SelectAggregate(table = "s", function = Aggregate.COUNT) val orderCount: Long
    )

    val onSpec =
      JoinSpec
        .from(JoinUser::class, "u")
        .join(
          JoinOrder::class,
          "o",
          "u.id" to "userId",
          mode = JoinMode.LEFT,
          onCriteria = Criteria.where("total").greaterThan(60)
        ).join(
          JoinOrder::class,
          "s",
          "u.id" to "userId",
          mode = JoinMode.AGGREGATE,
          onCriteria = Criteria.where("status").`is`("PAID")
        )

    val operation =
      joinQuery.prepare(onSpec, null, Query.empty(), mapOf("u" to Criteria.where("id").greaterThan(0)), Row::class)

    assertEquals(
      "SELECT u.`id` AS `id`, o.`status` AS `status`, " +
        "(SELECT COUNT(*) FROM `join_order` s WHERE (s.`status` = ?) AND s.`user_id` = u.`id`) AS `order_count` " +
        "FROM `join_user` u LEFT OUTER JOIN `join_order` o ON u.`id` = o.`user_id` AND (o.`total` > ?) " +
        "WHERE u.`id` > ?",
      operation.toQuery()
    )

    val target = RecordingTarget()
    operation.bindTo(target)
    assertEquals(listOf<Any?>("PAID", 60, 0L), target.values.values.toList())
  }

  @Test
  fun `ignoreCase renders LOWER for columns but not for aggregate aliases`() {
    data class Row(
      val id: Long,
      val name: String,
      @SelectAggregate(table = "s", function = Aggregate.MAX, name = "status") val lastStatus: String?
    )

    val statsSpec =
      JoinSpec.from(JoinUser::class, "u").join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)
    val sort =
      org.springframework.data.domain.Sort.by(
        org.springframework.data.domain.Sort.Order
          .asc("name")
          .ignoreCase(),
        org.springframework.data.domain.Sort.Order
          .desc("lastStatus")
          .ignoreCase()
      )

    val sql = joinQuery.prepare(statsSpec, null, Query.empty(), emptyMap(), Row::class, sort).toQuery()

    assertEquals("ORDER BY LOWER(u.`name`) ASC, `last_status` DESC", sql.substringAfter(" FROM `join_user` u "))
  }
}
