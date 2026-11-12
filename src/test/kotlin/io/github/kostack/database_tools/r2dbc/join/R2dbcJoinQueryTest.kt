package io.github.kostack.database_tools.r2dbc.join

import io.github.kostack.database_tools.query_mapper.QueryOperator
import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import io.github.kostack.database_tools.query_mapper.annotation.SelectColumn
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
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.r2dbc.core.await
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
