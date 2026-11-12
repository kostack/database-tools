package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.paginator.R2dbcPaginator
import io.github.kostack.database_tools.query_mapper.Aggregate
import io.github.kostack.database_tools.query_mapper.QueryOperator
import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import io.github.kostack.database_tools.query_mapper.annotation.SelectAggregate
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
import io.github.kostack.database_tools.query_mapper.annotation.Sortable
import io.github.kostack.database_tools.query_mapper.annotation.SortableDefaults
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.annotation.Id
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.r2dbc.core.await
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

data class JoinUser(
  @Id val id: Long,
  val name: String
)

data class JoinOrder(
  @Id val id: Long,
  val userId: Long,
  val status: String,
  val total: Int
)

data class JoinItem(
  @Id val id: Long,
  val orderId: Long,
  val sku: String
)

data class UserOrderRow(
  val id: Long,
  val name: String,
  @SelectColumn(table = "o", name = "id") val orderId: Long,
  @SelectColumn(table = "o") val status: String,
  @SelectColumn(table = "o", name = "total") val orderTotal: Int
)

data class UserMaybeOrderRow(
  val id: Long,
  val name: String,
  @SelectColumn(table = "o") val status: String?
)

data class UserOrderFilter(
  @QueryField(operator = QueryOperator.CONTAINS) val name: String? = null,
  @QueryField(table = "o") val status: String? = null,
  @QueryField(table = "o", name = "total", operator = QueryOperator.GTE) val minTotal: Int? = null,
  @QueryField(table = "i") val sku: String? = null
)

data class UserStatsRow(
  val id: Long,
  val name: String,
  @SelectAggregate(table = "s", function = Aggregate.COUNT) val orderCount: Long,
  @SelectAggregate(table = "s", function = Aggregate.SUM, name = "total") val totalSum: Long?,
  @SelectAggregate(table = "s", function = Aggregate.AVG, name = "total") val avgTotal: Double?,
  @SelectAggregate(table = "s", function = Aggregate.MIN, name = "total") val minTotal: Int?,
  @SelectAggregate(table = "s", function = Aggregate.MAX, name = "total") val maxTotal: Int?
)

data class UserStatsFilter(
  /** Plain criteria on an AGGREGATE alias scope the aggregation. */
  @QueryField(table = "s") val status: String? = null,
  @QueryField(table = "s", aggregate = Aggregate.COUNT, operator = QueryOperator.GTE) val minOrders: Long? = null,
  @QueryField(
    table = "s",
    aggregate = Aggregate.COUNT,
    operator = QueryOperator.IN
  ) val orderCounts: List<Long>? = null,
  @QueryField(
    table = "s",
    aggregate = Aggregate.AVG,
    name = "total",
    operator = QueryOperator.GT
  ) val avgAbove: Int? = null
)

/** IS_NULL ignores the value and is always active (ignoreEmpty = false). */
data class UsersWithoutOrdersFilter(
  @QueryField(
    table = "s",
    aggregate = Aggregate.MAX,
    name = "total",
    operator = QueryOperator.IS_NULL,
    ignoreEmpty = false
  ) val maxTotal: Int? = null
)

@SortableDefaults(defaultSortField = "orderId", defaultSortDirection = Sort.Direction.ASC, maxPageSize = 2)
@Sortable(name = "orderId")
@Sortable(name = "name")
@Sortable(name = "total", field = "o.total")
data class SortableOrderRow(
  val id: Long,
  val name: String,
  @SelectColumn(table = "o", name = "id") val orderId: Long
)

abstract class R2dbcJoinQueryContract {
  abstract val connectionFactory: ConnectionFactory

  private val template by lazy { R2dbcEntityTemplate(connectionFactory) }
  private val joinQuery by lazy { R2dbcJoinQuery(template) }

  // ON uses an entity property (userId -> user_id) and a raw column (order_id).
  private val spec =
    JoinSpec
      .from(JoinUser::class, "u")
      .join(JoinOrder::class, "o", "u.id" to "userId")
      .join(JoinItem::class, "i", "o.id" to "order_id", mode = JoinMode.EXISTS)

  private val byOrder = Query.empty().sort(Sort.by("orderId"))

  @BeforeEach
  fun setUp() =
    runBlocking {
      val client = template.databaseClient
      listOf("join_item", "join_order", "join_user").forEach { client.sql("DROP TABLE IF EXISTS $it").await() }
      client.sql("CREATE TABLE join_user (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)").await()
      client
        .sql(
          "CREATE TABLE join_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL, total INT NOT NULL)"
        ).await()
      client
        .sql(
          "CREATE TABLE join_item (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, sku VARCHAR(16) NOT NULL)"
        ).await()
      client.sql("INSERT INTO join_user VALUES (1, 'alice'), (2, 'bob'), (3, 'carol')").await()
      client.sql("INSERT INTO join_order VALUES (10, 1, 'NEW', 100), (11, 1, 'PAID', 50), (12, 2, 'PAID', 200)").await()
      client.sql("INSERT INTO join_item VALUES (100, 10, 'A'), (101, 10, 'A'), (102, 11, 'B'), (103, 12, 'A')").await()
    }

  private fun select(
    filter: Any? = null,
    query: Query = byOrder,
    required: Map<String, Criteria> = emptyMap()
  ) = runBlocking { joinQuery.select(spec, UserOrderRow::class, filter, query, required).toList() }

  @Test
  fun `inner join fetches joined columns into the dto`() {
    assertEquals(
      listOf(
        UserOrderRow(1, "alice", 10, "NEW", 100),
        UserOrderRow(1, "alice", 11, "PAID", 50),
        UserOrderRow(2, "bob", 12, "PAID", 200)
      ),
      select()
    )
  }

  @Test
  fun `filters on root and joined tables are combined`() {
    val rows = select(UserOrderFilter(name = "ali", status = "PAID"))
    assertEquals(listOf(11L), rows.map { it.orderId })
  }

  @Test
  fun `joined criteria are mapped through the joined entity`() {
    val rows = select(UserOrderFilter(minTotal = 100))
    assertEquals(listOf(10L, 12L), rows.map { it.orderId })
  }

  @Test
  fun `exists join filters without duplicating rows`() {
    // order 10 has two 'A' items but must appear once
    val rows = select(UserOrderFilter(sku = "A"))
    assertEquals(listOf(10L, 12L), rows.map { it.orderId })
  }

  @Test
  fun `exists join without criteria is skipped`() {
    assertEquals(3, select(UserOrderFilter()).size)
  }

  @Test
  fun `left join keeps root rows without match`() {
    val leftSpec =
      JoinSpec
        .from(
          JoinUser::class,
          "u"
        ).join(JoinOrder::class, "o", "u.id" to "userId", mode = JoinMode.LEFT)
    val rows =
      runBlocking {
        joinQuery.select(leftSpec, UserMaybeOrderRow::class, query = Query.empty().sort(Sort.by("id", "o.id"))).toList()
      }

    assertEquals(4, rows.size)
    assertNull(rows.single { it.name == "carol" }.status)
  }

  @Test
  fun `required OR criteria keep precedence against the filter`() {
    val required =
      Criteria
        .where("name")
        .`is`("alice")
        .or("name")
        .`is`("bob")
    val rows = select(UserOrderFilter(status = "NEW"), required = mapOf("u" to required))
    assertEquals(listOf(10L), rows.map { it.orderId })
  }

  @Test
  fun `required criteria on a joined alias`() {
    val rows = select(required = mapOf("o" to Criteria.where("status").`is`("PAID")))
    assertEquals(listOf(11L, 12L), rows.map { it.orderId })
  }

  @Test
  fun `query criteria apply to the root table`() {
    val rows = select(query = Query.query(Criteria.where("name").`is`("bob")))
    assertEquals(listOf(12L), rows.map { it.orderId })
  }

  @Test
  fun `count matches unpaged select`() {
    val filter = UserOrderFilter(status = "PAID", sku = "B")
    assertEquals(1L, runBlocking { joinQuery.count(spec, filter) })
    assertEquals(3L, runBlocking { joinQuery.count(spec) })
  }

  @Test
  fun `pagination sorts by dto property and pages`() {
    val page =
      runBlocking {
        joinQuery.toPagination(
          spec,
          UserOrderRow::class,
          PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "orderTotal"))
        )
      }

    assertEquals(listOf(12L, 10L), page.content.map { it.orderId })
    assertEquals(3L, page.total)

    val second =
      runBlocking {
        joinQuery.toPagination(
          spec,
          UserOrderRow::class,
          PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "orderTotal"))
        )
      }
    assertEquals(listOf(11L), second.content.map { it.orderId })
  }

  @Test
  fun `sort by alias-qualified column`() {
    val rows = select(query = Query.empty().sort(Sort.by(Sort.Direction.DESC, "o.total")))
    assertEquals(listOf(12L, 10L, 11L), rows.map { it.orderId })
  }

  @Test
  fun `rejects unsafe sort property`() {
    assertThrows<IllegalArgumentException> {
      select(query = Query.empty().sort(Sort.by("name; DROP TABLE join_user")))
    }
  }

  @Test
  fun `rejects unknown alias in filter`() {
    data class BadFilter(
      @QueryField(table = "x") val name: String? = "a"
    )
    assertThrows<IllegalArgumentException> { select(BadFilter()) }
  }

  @Test
  fun `rejects selecting from an exists alias`() {
    data class BadRow(
      val id: Long,
      @SelectColumn(table = "i") val sku: String
    )
    assertThrows<IllegalArgumentException> {
      runBlocking { joinQuery.select(spec, BadRow::class).toList() }
    }
  }

  // --- aggregates ---

  private val statsSpec =
    JoinSpec.from(JoinUser::class, "u").join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)

  private fun stats(
    filter: Any? = null,
    query: Query = Query.empty().sort(Sort.by("id"))
  ) = runBlocking { joinQuery.select(statsSpec, UserStatsRow::class, filter, query).toList() }

  @Test
  fun `selects all five aggregates per root row`() {
    assertEquals(
      listOf(
        UserStatsRow(1, "alice", 2, 150, 75.0, 50, 100),
        UserStatsRow(2, "bob", 1, 200, 200.0, 200, 200),
        UserStatsRow(3, "carol", 0, null, null, null, null)
      ),
      stats()
    )
  }

  @Test
  fun `filters on an aggregate`() {
    assertEquals(listOf("alice"), stats(UserStatsFilter(minOrders = 2)).map { it.name })
    assertEquals(listOf("bob"), stats(UserStatsFilter(avgAbove = 100)).map { it.name })
    assertEquals(listOf("bob", "carol"), stats(UserStatsFilter(orderCounts = listOf(0, 1))).map { it.name })
    assertEquals(listOf("carol"), stats(UsersWithoutOrdersFilter()).map { it.name })
    assertEquals(1L, runBlocking { joinQuery.count(statsSpec, UserStatsFilter(minOrders = 2)) })
  }

  @Test
  fun `plain criteria on an aggregate alias scope the aggregation, not the root rows`() {
    assertEquals(
      listOf(
        UserStatsRow(1, "alice", 1, 50, 50.0, 50, 50),
        UserStatsRow(2, "bob", 1, 200, 200.0, 200, 200),
        UserStatsRow(3, "carol", 0, null, null, null, null)
      ),
      stats(UserStatsFilter(status = "PAID"))
    )
    // scope applies to the aggregate filter as well: alice has only one PAID order
    assertEquals(emptyList(), stats(UserStatsFilter(status = "PAID", minOrders = 2)))
  }

  @Test
  fun `sorts and pages by an aggregate`() {
    val page =
      runBlocking {
        joinQuery.toPagination(
          statsSpec,
          UserStatsRow::class,
          PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "totalSum"))
        )
      }
    assertEquals(listOf("bob", "alice"), page.content.map { it.name })
    assertEquals(3L, page.total)
  }

  @Test
  fun `aggregates combine with joins, exists and plain filters`() {
    data class OrderWithUserStats(
      @SelectColumn(table = "o", name = "id") val orderId: Long,
      val name: String,
      @SelectAggregate(table = "s", function = Aggregate.COUNT) val orderCount: Long
    )

    data class Filter(
      @QueryField(table = "o") val status: String? = null,
      @QueryField(table = "i") val sku: String? = null,
      @QueryField(table = "s", aggregate = Aggregate.COUNT, operator = QueryOperator.GTE) val minOrders: Long? = null
    )

    val mixed = spec.join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)
    val rows =
      runBlocking {
        joinQuery
          .select(mixed, OrderWithUserStats::class, Filter(status = "NEW", sku = "A", minOrders = 2), byOrder)
          .toList()
      }

    assertEquals(listOf(OrderWithUserStats(10, "alice", 2)), rows)
  }

  @Test
  fun `rejects invalid aggregate usage`() {
    data class OnJoinedAlias(
      val id: Long,
      @SelectAggregate(table = "o", function = Aggregate.COUNT) val n: Long
    )

    data class SumWithoutColumn(
      val id: Long,
      @SelectAggregate(table = "s", function = Aggregate.SUM) val n: Long?
    )

    data class ColumnFromAggregateAlias(
      val id: Long,
      @SelectColumn(table = "s") val status: String
    )

    data class LikeOnAggregate(
      @QueryField(table = "s", aggregate = Aggregate.MAX, name = "status", operator = QueryOperator.CONTAINS)
      val status: String? = "P"
    )

    assertThrows<IllegalArgumentException> { runBlocking { joinQuery.select(spec, OnJoinedAlias::class).toList() } }
    assertThrows<IllegalArgumentException> {
      runBlocking {
        joinQuery
          .select(
            statsSpec,
            SumWithoutColumn::class
          ).toList()
      }
    }
    assertThrows<IllegalArgumentException> {
      runBlocking { joinQuery.select(statsSpec, ColumnFromAggregateAlias::class).toList() }
    }
    assertThrows<UnsupportedOperationException> { stats(LikeOnAggregate()) }
    assertThrows<IllegalArgumentException> {
      JoinSpec
        .from(JoinUser::class, "u")
        .join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)
        .join(JoinItem::class, "i", "s.id" to "orderId")
    }
  }

  // --- extra ON conditions ---

  private val paid = Criteria.where("status").`is`("PAID")

  @Test
  fun `left join onCriteria keeps unmatched root rows, where criteria do not`() {
    val sorted = Query.empty().sort(Sort.by("id"))
    val onSpec =
      JoinSpec
        .from(
          JoinUser::class,
          "u"
        ).join(JoinOrder::class, "o", "u.id" to "userId", mode = JoinMode.LEFT, onCriteria = paid)
    val plainSpec =
      JoinSpec
        .from(
          JoinUser::class,
          "u"
        ).join(JoinOrder::class, "o", "u.id" to "userId", mode = JoinMode.LEFT)

    val viaOn = runBlocking { joinQuery.select(onSpec, UserMaybeOrderRow::class, query = sorted).toList() }
    val viaWhere =
      runBlocking {
        joinQuery.select(plainSpec, UserMaybeOrderRow::class, query = sorted, required = mapOf("o" to paid)).toList()
      }

    assertEquals(
      listOf(
        UserMaybeOrderRow(1, "alice", "PAID"),
        UserMaybeOrderRow(2, "bob", "PAID"),
        UserMaybeOrderRow(3, "carol", null)
      ),
      viaOn
    )
    assertEquals(listOf("alice", "bob"), viaWhere.map { it.name })
    assertEquals(3L, runBlocking { joinQuery.count(onSpec) })
  }

  @Test
  fun `inner join onCriteria combines with filters`() {
    val onSpec = JoinSpec.from(JoinUser::class, "u").join(JoinOrder::class, "o", "u.id" to "userId", onCriteria = paid)
    val rows =
      runBlocking { joinQuery.select(onSpec, UserOrderRow::class, UserOrderFilter(minTotal = 100), byOrder).toList() }
    assertEquals(listOf(12L), rows.map { it.orderId })
  }

  @Test
  fun `exists onCriteria narrows the subquery but does not activate it`() {
    val onSpec =
      JoinSpec
        .from(JoinUser::class, "u")
        .join(JoinOrder::class, "o", "u.id" to "userId")
        .join(
          JoinItem::class,
          "i",
          "o.id" to "orderId",
          mode = JoinMode.EXISTS,
          onCriteria = Criteria.where("id").greaterThanOrEquals(103)
        )

    val filtered =
      runBlocking { joinQuery.select(onSpec, UserOrderRow::class, UserOrderFilter(sku = "A"), byOrder).toList() }
    val unfiltered = runBlocking { joinQuery.select(onSpec, UserOrderRow::class, UserOrderFilter(), byOrder).toList() }

    assertEquals(listOf(12L), filtered.map { it.orderId })
    assertEquals(3, unfiltered.size)
  }

  @Test
  fun `aggregate onCriteria scopes the aggregation`() {
    val onSpec =
      JoinSpec
        .from(
          JoinUser::class,
          "u"
        ).join(JoinOrder::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE, onCriteria = paid)
    val rows =
      runBlocking { joinQuery.select(onSpec, UserStatsRow::class, query = Query.empty().sort(Sort.by("id"))).toList() }

    assertEquals(listOf(1L, 1L, 0L), rows.map { it.orderCount })
    assertEquals(listOf(50L, 200L, null), rows.map { it.totalSum })
  }

  // --- sorting: sortableSource and ignoreCase ---

  @Test
  fun `sortableSource applies default sort and caps page size`() {
    val page =
      runBlocking {
        joinQuery.toPagination(
          spec,
          SortableOrderRow::class,
          PageRequest.of(0, 10),
          sortableSource = SortableOrderRow::class
        )
      }

    assertEquals(listOf(10L, 11L), page.content.map { it.orderId })
    assertEquals(2, page.limit)
    assertEquals(3L, page.total)
  }

  @Test
  fun `sortableSource maps api names to alias columns`() {
    val page =
      runBlocking {
        joinQuery.toPagination(
          spec,
          SortableOrderRow::class,
          PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "total")),
          sortableSource = SortableOrderRow::class
        )
      }

    assertEquals(listOf(12L, 10L), page.content.map { it.orderId })
  }

  @Test
  fun `sortableSource rejects fields that are not allowlisted`() {
    // "status" is a valid column, but not @Sortable on the DTO
    assertThrows<IllegalArgumentException> {
      runBlocking {
        joinQuery.toPagination(
          spec,
          SortableOrderRow::class,
          PageRequest.of(0, 2, Sort.by("o.status")),
          sortableSource = SortableOrderRow::class
        )
      }
    }
  }

  @Test
  fun `ignoreCase sorts case-insensitively`() {
    runBlocking { template.databaseClient.sql("INSERT INTO join_user VALUES (4, 'Zed')").await() }
    val rootOnly = JoinSpec.from(JoinUser::class, "u")

    val rows =
      runBlocking {
        joinQuery
          .select(rootOnly, JoinUser::class, sort = Sort.by(Sort.Order.asc("name").ignoreCase()))
          .toList()
      }

    assertEquals(listOf("alice", "bob", "carol", "Zed"), rows.map { it.name })
  }

  @Test
  fun `ignoreCase passes through sortableSource`() {
    runBlocking { template.databaseClient.sql("INSERT INTO join_order VALUES (13, 1, 'NEW', 1)").await() }
    runBlocking { template.databaseClient.sql("INSERT INTO join_user VALUES (4, 'Zed')").await() }
    runBlocking { template.databaseClient.sql("INSERT INTO join_order VALUES (14, 4, 'NEW', 1)").await() }

    val page =
      runBlocking {
        joinQuery.toPagination(
          spec,
          SortableOrderRow::class,
          PageRequest.of(0, 2, Sort.by(Sort.Order.desc("name").ignoreCase())),
          sortableSource = SortableOrderRow::class
        )
      }

    assertEquals("Zed", page.content.first().name)
  }

  // --- R2dbcPaginator ignoreCase (runs through the join query) ---

  @Test
  fun `paginator sorts ignoreCase and keeps query criteria`() {
    runBlocking { template.databaseClient.sql("INSERT INTO join_user VALUES (4, 'Zed')").await() }
    val paginator = R2dbcPaginator(template)

    val page =
      runBlocking {
        paginator.toPagination(
          Query.query(Criteria.where("name").not("bob")),
          PageRequest.of(0, 2, Sort.by(Sort.Order.desc("name").ignoreCase())),
          JoinUser::class
        )
      }

    assertEquals(listOf("Zed", "carol"), page.content.map { it.name })
    assertEquals(3L, page.total)
    assertEquals(2, page.limit)
  }

  @Test
  fun `rejects embedded properties`() {
    data class Address(
      val city: String?
    )

    data class WithEmbedded(
      val id: Long,
      @Embedded.Nullable val address: Address?
    )

    val failure =
      assertThrows<IllegalArgumentException> {
        runBlocking { joinQuery.select(JoinSpec.from(JoinUser::class, "u"), WithEmbedded::class).toList() }
      }
    assertTrue(failure.message!!.contains("@Embedded"), failure.message)
  }
}

class R2dbcJoinQueryH2Test : R2dbcJoinQueryContract() {
  override val connectionFactory: ConnectionFactory =
    ConnectionFactories.get("r2dbc:h2:mem:///join-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
}

/** Real MySQL via Testcontainers; skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class R2dbcJoinQueryMySqlTest : R2dbcJoinQueryContract() {
  companion object {
    @Container
    @JvmStatic
    val mysql = MySQLContainer("mysql:8.4")
  }

  // Lazy: the container is started by the extension before the first test runs.
  override val connectionFactory: ConnectionFactory by lazy {
    ConnectionFactories.get(
      "r2dbc:mysql://${mysql.username}:${mysql.password}@${mysql.host}:${mysql.getMappedPort(
        3306
      )}/${mysql.databaseName}"
    )
  }
}
