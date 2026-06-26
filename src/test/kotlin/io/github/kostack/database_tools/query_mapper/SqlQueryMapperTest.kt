package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
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
}
