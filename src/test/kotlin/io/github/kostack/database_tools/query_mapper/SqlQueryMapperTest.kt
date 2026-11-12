package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import io.github.kostack.database_tools.query_mapper.extension.and
import io.github.kostack.database_tools.query_mapper.extension.toMappedQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SqlQueryMapperTest {
  data class EqStringFilter(
    @QueryField val name: String? = null
  )

  data class EqIntFilter(
    @QueryField val age: Int? = null
  )

  data class NeFilter(
    @QueryField(operator = QueryOperator.NE) val status: String? = null
  )

  data class ContainsFilter(
    @QueryField(operator = QueryOperator.CONTAINS) val title: String? = null
  )

  data class ContainsIgnoreCaseFilter(
    @QueryField(operator = QueryOperator.CONTAINS_IGNORE_CASE) val title: String? = null
  )

  data class StartsWithFilter(
    @QueryField(operator = QueryOperator.STARTS_WITH) val prefix: String? = null
  )

  data class StartsWithIgnoreCaseFilter(
    @QueryField(operator = QueryOperator.STARTS_WITH_IGNORE_CASE) val prefix: String? = null
  )

  data class EndsWithFilter(
    @QueryField(operator = QueryOperator.ENDS_WITH) val suffix: String? = null
  )

  data class EndsWithIgnoreCaseFilter(
    @QueryField(operator = QueryOperator.ENDS_WITH_IGNORE_CASE) val suffix: String? = null
  )

  data class RegexFilter(
    @QueryField(operator = QueryOperator.REGEX) val pattern: String? = null
  )

  data class RegexIgnoreCaseFilter(
    @QueryField(operator = QueryOperator.REGEX_IGNORE_CASE) val pattern: String? = null
  )

  data class GtFilter(
    @QueryField(operator = QueryOperator.GT) val score: Int? = null
  )

  data class GteFilter(
    @QueryField(operator = QueryOperator.GTE) val score: Int? = null
  )

  data class LtFilter(
    @QueryField(operator = QueryOperator.LT) val score: Int? = null
  )

  data class LteFilter(
    @QueryField(operator = QueryOperator.LTE) val score: Int? = null
  )

  data class InFilter(
    @QueryField(operator = QueryOperator.IN) val statuses: List<String>? = null
  )

  data class NotInFilter(
    @QueryField(operator = QueryOperator.NOT_IN) val statuses: List<String>? = null
  )

  data class ExistsFilter(
    @QueryField(operator = QueryOperator.EXISTS, ignoreEmpty = false) val tag: String? = null
  )

  data class NotExistsFilter(
    @QueryField(operator = QueryOperator.NOT_EXISTS, ignoreEmpty = false) val tag: String? = null
  )

  data class IsNullFilter(
    @QueryField(operator = QueryOperator.IS_NULL, ignoreEmpty = false) val deletedAt: String? = null
  )

  data class IsNotNullFilter(
    @QueryField(operator = QueryOperator.IS_NOT_NULL, ignoreEmpty = false) val deletedAt: String? = null
  )

  data class CustomNameFilter(
    @QueryField(name = "user_id") val userId: String? = null
  )

  data class IgnoreEmptyFilter(
    @QueryField val name: String? = null
  )

  data class MultiFilter(
    @QueryField val name: String? = null,
    @QueryField(operator = QueryOperator.GT) val age: Int? = null
  )

  // --- empty query ---

  @Test
  fun `toQuery returns empty query when all fields are null`() {
    val query = SqlQueryMapper.toQuery(IgnoreEmptyFilter(name = null))
    assertFalse(query.criteria.isPresent)
  }

  @Test
  fun `toQuery returns empty query when IN collection is empty`() {
    val query = SqlQueryMapper.toQuery(InFilter(statuses = emptyList()))
    assertFalse(query.criteria.isPresent)
  }

  @Test
  fun `toQuery returns empty query when NOT_IN collection is empty`() {
    val query = SqlQueryMapper.toQuery(NotInFilter(statuses = emptyList()))
    assertFalse(query.criteria.isPresent)
  }

  // --- EQ ---

  @Test
  fun `EQ includes field name in criteria`() {
    val query = SqlQueryMapper.toQuery(EqStringFilter(name = "John"))
    val criteria = query.criteria.orElseThrow()
    assertTrue(criteria.toString().contains("name"))
  }

  @Test
  fun `EQ with integer value produces criteria`() {
    val query = SqlQueryMapper.toQuery(EqIntFilter(age = 30))
    assertTrue(query.criteria.isPresent)
  }

  @Test
  fun `EQ with null integer skips field`() {
    val query = SqlQueryMapper.toQuery(EqIntFilter(age = null))
    assertFalse(query.criteria.isPresent)
  }

  // --- NE ---

  @Test
  fun `NE produces criteria for field`() {
    val query = SqlQueryMapper.toQuery(NeFilter(status = "inactive"))
    assertTrue(query.criteria.isPresent)
  }

  // --- CONTAINS ---

  @Test
  fun `CONTAINS wraps value with percent wildcards`() {
    val query = SqlQueryMapper.toQuery(ContainsFilter(title = "hello"))
    val criteria = query.criteria.orElseThrow()
    assertTrue(criteria.toString().contains("%hello%"))
  }

  @Test
  fun `CONTAINS escapes SQL percent sign`() {
    val query = SqlQueryMapper.toQuery(ContainsFilter(title = "100%"))
    val criteria = query.criteria.orElseThrow()
    assertTrue(criteria.toString().contains("100\\%"))
    assertFalse(criteria.toString().contains("100%%"))
  }

  @Test
  fun `CONTAINS escapes SQL underscore`() {
    val query = SqlQueryMapper.toQuery(ContainsFilter(title = "foo_bar"))
    val criteria = query.criteria.orElseThrow()
    assertTrue(criteria.toString().contains("foo\\_bar"))
    assertFalse(criteria.toString().contains("foo_bar%"))
  }

  @Test
  fun `CONTAINS escapes SQL backslash`() {
    val query = SqlQueryMapper.toQuery(ContainsFilter(title = "C:\\path"))
    val criteria = query.criteria.orElseThrow()
    assertTrue(criteria.toString().contains("C:\\\\path"))
  }

  @Test
  fun `CONTAINS uses LIKE operator`() {
    val query = SqlQueryMapper.toQuery(ContainsFilter(title = "hello"))
    assertTrue(
      query.criteria
        .orElseThrow()
        .toString()
        .contains("LIKE")
    )
  }

  // --- CONTAINS_IGNORE_CASE ---

  @Test
  fun `CONTAINS_IGNORE_CASE includes value in pattern`() {
    val query = SqlQueryMapper.toQuery(ContainsIgnoreCaseFilter(title = "hello"))
    assertTrue(
      query.criteria
        .orElseThrow()
        .toString()
        .contains("%hello%")
    )
  }

  // --- STARTS_WITH ---

  @Test
  fun `STARTS_WITH appends trailing percent but not leading`() {
    val query = SqlQueryMapper.toQuery(StartsWithFilter(prefix = "abc"))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("abc%"))
    assertFalse(criteria.contains("%abc%"))
  }

  // --- STARTS_WITH_IGNORE_CASE ---

  @Test
  fun `STARTS_WITH_IGNORE_CASE produces criteria`() {
    val query = SqlQueryMapper.toQuery(StartsWithIgnoreCaseFilter(prefix = "abc"))
    assertTrue(query.criteria.isPresent)
  }

  // --- ENDS_WITH ---

  @Test
  fun `ENDS_WITH prepends leading percent but not trailing`() {
    val query = SqlQueryMapper.toQuery(EndsWithFilter(suffix = "xyz"))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("%xyz"))
    assertFalse(criteria.contains("%xyz%"))
  }

  // --- ENDS_WITH_IGNORE_CASE ---

  @Test
  fun `ENDS_WITH_IGNORE_CASE produces criteria`() {
    val query = SqlQueryMapper.toQuery(EndsWithIgnoreCaseFilter(suffix = "xyz"))
    assertTrue(query.criteria.isPresent)
  }

  // --- REGEX ---

  @Test
  fun `REGEX throws UnsupportedOperationException`() {
    assertThrows<UnsupportedOperationException> {
      SqlQueryMapper.toQuery(RegexFilter(pattern = ".*"))
    }
  }

  @Test
  fun `REGEX_IGNORE_CASE throws UnsupportedOperationException`() {
    assertThrows<UnsupportedOperationException> {
      SqlQueryMapper.toQuery(RegexIgnoreCaseFilter(pattern = ".*"))
    }
  }

  // --- comparison operators ---

  @Test
  fun `GT produces criteria`() {
    val query = SqlQueryMapper.toQuery(GtFilter(score = 10))
    assertTrue(query.criteria.isPresent)
  }

  @Test
  fun `GTE produces criteria`() {
    val query = SqlQueryMapper.toQuery(GteFilter(score = 10))
    assertTrue(query.criteria.isPresent)
  }

  @Test
  fun `LT produces criteria`() {
    val query = SqlQueryMapper.toQuery(LtFilter(score = 10))
    assertTrue(query.criteria.isPresent)
  }

  @Test
  fun `LTE produces criteria`() {
    val query = SqlQueryMapper.toQuery(LteFilter(score = 10))
    assertTrue(query.criteria.isPresent)
  }

  // --- IN / NOT_IN ---

  @Test
  fun `IN produces criteria for non-empty collection`() {
    val query = SqlQueryMapper.toQuery(InFilter(statuses = listOf("active", "pending")))
    assertTrue(query.criteria.isPresent)
  }

  @Test
  fun `NOT_IN produces criteria for non-empty collection`() {
    val query = SqlQueryMapper.toQuery(NotInFilter(statuses = listOf("deleted")))
    assertTrue(query.criteria.isPresent)
  }

  // --- EXISTS / NOT_EXISTS / IS_NULL / IS_NOT_NULL ---

  @Test
  fun `EXISTS produces IS NOT NULL criteria`() {
    val query = SqlQueryMapper.toQuery(ExistsFilter(tag = "any"))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("IS NOT NULL"))
  }

  @Test
  fun `NOT_EXISTS produces IS NULL criteria`() {
    val query = SqlQueryMapper.toQuery(NotExistsFilter(tag = "any"))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("IS NULL"))
    assertFalse(criteria.contains("IS NOT NULL"))
  }

  @Test
  fun `IS_NULL produces IS NULL criteria`() {
    val query = SqlQueryMapper.toQuery(IsNullFilter())
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("IS NULL"))
    assertFalse(criteria.contains("IS NOT NULL"))
  }

  @Test
  fun `IS_NOT_NULL produces IS NOT NULL criteria`() {
    val query = SqlQueryMapper.toQuery(IsNotNullFilter())
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("IS NOT NULL"))
  }

  // --- custom field name ---

  @Test
  fun `QueryField name attribute maps property to DB column name`() {
    val query = SqlQueryMapper.toQuery(CustomNameFilter(userId = "abc-123"))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("user_id"))
    assertFalse(criteria.contains("userId"))
  }

  // --- ignoreEmpty ---

  @Test
  fun `ignoreEmpty true skips null field`() {
    val query = SqlQueryMapper.toQuery(IgnoreEmptyFilter(name = null))
    assertFalse(query.criteria.isPresent)
  }

  @Test
  fun `ignoreEmpty true skips blank string field`() {
    val query = SqlQueryMapper.toQuery(IgnoreEmptyFilter(name = ""))
    assertFalse(query.criteria.isPresent)
  }

  @Test
  fun `ignoreEmpty true includes non-blank string field`() {
    val query = SqlQueryMapper.toQuery(IgnoreEmptyFilter(name = "Alice"))
    assertTrue(query.criteria.isPresent)
  }

  // --- multiple fields ---

  @Test
  fun `multiple non-null fields produce criteria containing all field names`() {
    val query = SqlQueryMapper.toQuery(MultiFilter(name = "Alice", age = 25))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("name"))
    assertTrue(criteria.contains("age"))
  }

  @Test
  fun `null field is excluded from multi-field criteria`() {
    val query = SqlQueryMapper.toQuery(MultiFilter(name = "Alice", age = null))
    val criteria = query.criteria.orElseThrow().toString()
    assertTrue(criteria.contains("name"))
    assertFalse(criteria.contains("age"))
  }

  // --- merging into a base query ---

  private val tenant = Criteria.where("tenant_id").`is`("t1")

  @Test
  fun `toQuery keeps sort limit offset and columns of base query`() {
    val base =
      Query
        .empty()
        .sort(Sort.by("name"))
        .limit(10)
        .offset(20)
        .columns("id", "name")

    val query = SqlQueryMapper.toQuery(EqStringFilter(name = "John"), base)

    assertTrue(
      query.criteria
        .orElseThrow()
        .toString()
        .contains("name = 'John'")
    )
    assertEquals(Sort.by("name"), query.sort)
    assertEquals(10, query.limit)
    assertEquals(20, query.offset)
    assertEquals(listOf("id", "name"), query.columns.map { it.reference })
  }

  @Test
  fun `toQuery returns base unchanged when nothing is mapped and nothing is required`() {
    val base = Query.empty().limit(5)
    assertSame(base, SqlQueryMapper.toQuery(EqStringFilter(name = null), base))
  }

  @Test
  fun `toQuery places required criteria first and ANDs the mapped criteria`() {
    val query = SqlQueryMapper.toQuery(MultiFilter(name = "Alice", age = 25), required = tenant)
    val sql = query.criteria.orElseThrow().toString()

    assertTrue(sql.startsWith("tenant_id = 't1'"), sql)
    assertTrue(sql.contains("AND (name = 'Alice')"), sql)
    assertTrue(sql.contains("AND (age > 25)"), sql)
  }

  @Test
  fun `toQuery applies required criteria even when filter maps nothing`() {
    val query = SqlQueryMapper.toQuery(EqStringFilter(name = null), required = tenant)
    assertEquals("tenant_id = 't1'", query.criteria.orElseThrow().toString())
  }

  @Test
  fun `toQuery groups OR in base criteria so precedence is kept`() {
    val base =
      Query.query(
        Criteria
          .where("a")
          .`is`(1)
          .or("b")
          .`is`(2)
      )
    val sql =
      SqlQueryMapper
        .toQuery(EqStringFilter(name = "John"), base, tenant)
        .criteria
        .orElseThrow()
        .toString()

    assertEquals("tenant_id = 't1' AND (a = 1 OR b = 2) AND (name = 'John')", sql)
  }

  @Test
  fun `toQuery groups OR in required criteria so precedence is kept`() {
    val required =
      Criteria
        .where("a")
        .`is`(1)
        .or("b")
        .`is`(2)
    val sql =
      SqlQueryMapper
        .toQuery(EqStringFilter(name = "John"), required = required)
        .criteria
        .orElseThrow()
        .toString()

    assertEquals("(a = 1 OR b = 2) AND (name = 'John')", sql)
  }

  @Test
  fun `and ANDs criteria into base query and keeps paging`() {
    val base = Query.query(Criteria.where("name").`is`("John")).limit(3)
    val query = SqlQueryMapper.and(base, tenant)

    assertEquals("tenant_id = 't1' AND (name = 'John')", query.criteria.orElseThrow().toString())
    assertEquals(3, query.limit)
  }

  @Test
  fun `extensions delegate to the mapper`() {
    val query =
      Query
        .empty()
        .limit(7)
        .toMappedQuery(EqStringFilter(name = "John"))
        .and(tenant)

    assertEquals("tenant_id = 't1' AND (name = 'John')", query.criteria.orElseThrow().toString())
    assertEquals(7, query.limit)
  }

  // --- join tables ---

  data class JoinedFilter(
    @QueryField val name: String? = "a",
    @QueryField(table = "o") val status: String? = "PAID"
  )

  @Test
  fun `toQuery rejects fields targeting a joined table`() {
    assertThrows<IllegalArgumentException> { SqlQueryMapper.toQuery(JoinedFilter()) }
  }

  data class AggregateFilter(
    @QueryField(aggregate = Aggregate.COUNT, operator = QueryOperator.GTE) val minOrders: Long? = 2
  )

  @Test
  fun `toQuery rejects aggregate fields`() {
    assertThrows<IllegalArgumentException> { SqlQueryMapper.toQuery(AggregateFilter()) }
  }
}
