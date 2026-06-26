package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import org.bson.Document
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MongoQueryMapperTest {
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

  // Extracts the list of individual criteria Documents from the top-level $and wrapper.
  private fun andCriteria(filter: Any): List<Document> {
    val queryObject = MongoQueryMapper.toQuery(filter).queryObject
    @Suppress("UNCHECKED_CAST")
    return queryObject.getList("\$and", Document::class.java)
  }

  // Returns the single criteria Document when the filter has exactly one field.
  private fun singleCriteria(filter: Any): Document = andCriteria(filter).first()

  // Extracts the regex Pattern from a criteria field — handles both Pattern and {$regex/$options} Document storage.
  private fun getPattern(
    filter: Any,
    fieldName: String
  ): Pattern {
    val value = singleCriteria(filter)[fieldName]
    return when (value) {
      is Pattern -> {
        value
      }

      is Document -> {
        val flags = if (value.getString("\$options").contains("i")) Pattern.CASE_INSENSITIVE else 0
        Pattern.compile(value.getString("\$regex"), flags)
      }

      else -> {
        throw AssertionError("Unexpected regex field type: ${value?.javaClass}")
      }
    }
  }

  // --- empty query ---

  @Test
  fun `toQuery returns empty query when all fields are null`() {
    val query = MongoQueryMapper.toQuery(IgnoreEmptyFilter(name = null))
    assertTrue(query.queryObject.isEmpty())
  }

  @Test
  fun `toQuery returns empty query when IN collection is empty`() {
    val query = MongoQueryMapper.toQuery(InFilter(statuses = emptyList()))
    assertTrue(query.queryObject.isEmpty())
  }

  @Test
  fun `toQuery returns empty query when NOT_IN collection is empty`() {
    val query = MongoQueryMapper.toQuery(NotInFilter(statuses = emptyList()))
    assertTrue(query.queryObject.isEmpty())
  }

  // --- EQ ---

  @Test
  fun `EQ stores value directly on field key`() {
    val criteria = singleCriteria(EqStringFilter(name = "John"))
    assertEquals("John", criteria["name"])
  }

  @Test
  fun `EQ with integer value stores value on field key`() {
    val criteria = singleCriteria(EqIntFilter(age = 30))
    assertEquals(30, criteria["age"])
  }

  @Test
  fun `EQ with null integer skips field`() {
    val query = MongoQueryMapper.toQuery(EqIntFilter(age = null))
    assertTrue(query.queryObject.isEmpty())
  }

  // --- NE ---

  @Test
  fun `NE uses dollar-ne operator`() {
    val criteria = singleCriteria(NeFilter(status = "inactive"))
    val statusDoc = criteria.get("status", Document::class.java)
    assertNotNull(statusDoc["\$ne"])
    assertEquals("inactive", statusDoc["\$ne"])
  }

  // --- CONTAINS ---

  @Test
  fun `CONTAINS uses regex operator`() {
    val pattern = getPattern(ContainsFilter(title = "hello"), "title")
    assertTrue(pattern.pattern().contains("hello"))
  }

  @Test
  fun `CONTAINS includes search term in pattern`() {
    val pattern = getPattern(ContainsFilter(title = "hello"), "title")
    assertTrue(pattern.pattern().contains("hello"))
  }

  @Test
  fun `CONTAINS quotes regex special characters`() {
    val pattern = getPattern(ContainsFilter(title = "a.b*c"), "title").pattern()
    assertTrue(pattern.contains("\\Q"), "pattern should use Pattern.quote wrapper")
  }

  // --- CONTAINS_IGNORE_CASE ---

  @Test
  fun `CONTAINS_IGNORE_CASE pattern has CASE_INSENSITIVE flag`() {
    val sensitive = getPattern(ContainsFilter(title = "hello"), "title")
    val insensitive = getPattern(ContainsIgnoreCaseFilter(title = "hello"), "title")

    assertEquals(0, sensitive.flags() and Pattern.CASE_INSENSITIVE)
    assertFalse(insensitive.flags() and Pattern.CASE_INSENSITIVE == 0)
  }

  // --- STARTS_WITH ---

  @Test
  fun `STARTS_WITH pattern is anchored at start`() {
    val pattern = getPattern(StartsWithFilter(prefix = "abc"), "prefix")
    assertTrue(pattern.pattern().startsWith("^"))
    assertTrue(pattern.pattern().contains("abc"))
  }

  @Test
  fun `STARTS_WITH pattern has no end anchor`() {
    val pattern = getPattern(StartsWithFilter(prefix = "abc"), "prefix")
    assertFalse(pattern.pattern().trimEnd().endsWith("$"))
  }

  // --- STARTS_WITH_IGNORE_CASE ---

  @Test
  fun `STARTS_WITH_IGNORE_CASE pattern has CASE_INSENSITIVE flag`() {
    val sensitive = getPattern(StartsWithFilter(prefix = "abc"), "prefix")
    val insensitive = getPattern(StartsWithIgnoreCaseFilter(prefix = "abc"), "prefix")

    assertEquals(0, sensitive.flags() and Pattern.CASE_INSENSITIVE)
    assertNotEquals(insensitive.flags() and Pattern.CASE_INSENSITIVE, 0)
  }

  // --- ENDS_WITH ---

  @Test
  fun `ENDS_WITH pattern is anchored at end`() {
    val pattern = getPattern(EndsWithFilter(suffix = "xyz"), "suffix")
    assertTrue(pattern.pattern().contains("xyz"))
    assertTrue(pattern.pattern().trimEnd().endsWith("$"))
  }

  @Test
  fun `ENDS_WITH pattern has no start anchor`() {
    val pattern = getPattern(EndsWithFilter(suffix = "xyz"), "suffix")
    assertFalse(pattern.pattern().startsWith("^"))
  }

  // --- ENDS_WITH_IGNORE_CASE ---

  @Test
  fun `ENDS_WITH_IGNORE_CASE pattern has CASE_INSENSITIVE flag`() {
    val sensitive = getPattern(EndsWithFilter(suffix = "xyz"), "suffix")
    val insensitive = getPattern(EndsWithIgnoreCaseFilter(suffix = "xyz"), "suffix")

    assertEquals(0, sensitive.flags() and Pattern.CASE_INSENSITIVE)
    assertFalse(insensitive.flags() and Pattern.CASE_INSENSITIVE == 0)
  }

  // --- REGEX ---

  @Test
  fun `REGEX uses pattern as-is`() {
    val pattern = getPattern(RegexFilter(pattern = "^test.*end$"), "pattern")
    assertEquals("^test.*end$", pattern.pattern())
  }

  // --- REGEX_IGNORE_CASE ---

  @Test
  fun `REGEX_IGNORE_CASE has CASE_INSENSITIVE flag`() {
    val sensitive = getPattern(RegexFilter(pattern = "hello"), "pattern")
    val insensitive = getPattern(RegexIgnoreCaseFilter(pattern = "hello"), "pattern")

    assertEquals(0, sensitive.flags() and Pattern.CASE_INSENSITIVE)
    assertFalse(insensitive.flags() and Pattern.CASE_INSENSITIVE == 0)
  }

  // --- comparison operators ---

  @Test
  fun `GT uses dollar-gt operator`() {
    val criteria = singleCriteria(GtFilter(score = 10))
    val scoreDoc = criteria.get("score", Document::class.java)
    assertEquals(10, scoreDoc["\$gt"])
  }

  @Test
  fun `GTE uses dollar-gte operator`() {
    val criteria = singleCriteria(GteFilter(score = 10))
    val scoreDoc = criteria.get("score", Document::class.java)
    assertEquals(10, scoreDoc["\$gte"])
  }

  @Test
  fun `LT uses dollar-lt operator`() {
    val criteria = singleCriteria(LtFilter(score = 5))
    val scoreDoc = criteria.get("score", Document::class.java)
    assertEquals(5, scoreDoc["\$lt"])
  }

  @Test
  fun `LTE uses dollar-lte operator`() {
    val criteria = singleCriteria(LteFilter(score = 5))
    val scoreDoc = criteria.get("score", Document::class.java)
    assertEquals(5, scoreDoc["\$lte"])
  }

  // --- IN / NOT_IN ---

  @Test
  fun `IN uses dollar-in operator with values`() {
    val criteria = singleCriteria(InFilter(statuses = listOf("active", "pending")))
    val statusDoc = criteria.get("statuses", Document::class.java)
    val values = statusDoc.getList("\$in", String::class.java)
    assertEquals(listOf("active", "pending"), values)
  }

  @Test
  fun `NOT_IN uses dollar-nin operator with values`() {
    val criteria = singleCriteria(NotInFilter(statuses = listOf("deleted")))
    val statusDoc = criteria.get("statuses", Document::class.java)
    val values = statusDoc.getList("\$nin", String::class.java)
    assertEquals(listOf("deleted"), values)
  }

  // --- EXISTS / NOT_EXISTS / IS_NULL / IS_NOT_NULL ---

  @Test
  fun `EXISTS uses dollar-exists true`() {
    val criteria = singleCriteria(ExistsFilter(tag = "any"))
    val tagDoc = criteria.get("tag", Document::class.java)
    assertEquals(true, tagDoc["\$exists"])
  }

  @Test
  fun `NOT_EXISTS uses dollar-exists false`() {
    val criteria = singleCriteria(NotExistsFilter(tag = "any"))
    val tagDoc = criteria.get("tag", Document::class.java)
    assertEquals(false, tagDoc["\$exists"])
  }

  @Test
  fun `IS_NULL stores null value on field key`() {
    val criteria = singleCriteria(IsNullFilter())
    assertEquals(null, criteria["deletedAt"])
    assertTrue(criteria.containsKey("deletedAt"))
  }

  @Test
  fun `IS_NOT_NULL uses dollar-ne null`() {
    val criteria = singleCriteria(IsNotNullFilter())
    val deletedAtDoc = criteria.get("deletedAt", Document::class.java)
    assertTrue(deletedAtDoc.containsKey("\$ne"))
    assertEquals(null, deletedAtDoc["\$ne"])
  }

  // --- custom field name ---

  @Test
  fun `QueryField name attribute maps property to DB field name`() {
    val criteria = singleCriteria(CustomNameFilter(userId = "abc-123"))
    assertTrue(criteria.containsKey("user_id"))
    assertFalse(criteria.containsKey("userId"))
    assertEquals("abc-123", criteria["user_id"])
  }

  // --- ignoreEmpty ---

  @Test
  fun `ignoreEmpty true skips null field`() {
    val query = MongoQueryMapper.toQuery(IgnoreEmptyFilter(name = null))
    assertTrue(query.queryObject.isEmpty())
  }

  @Test
  fun `ignoreEmpty true skips blank string field`() {
    val query = MongoQueryMapper.toQuery(IgnoreEmptyFilter(name = ""))
    assertTrue(query.queryObject.isEmpty())
  }

  @Test
  fun `ignoreEmpty true includes non-blank string field`() {
    val query = MongoQueryMapper.toQuery(IgnoreEmptyFilter(name = "Alice"))
    assertFalse(query.queryObject.isEmpty())
  }

  // --- multiple fields ---

  @Test
  fun `multiple non-null fields each appear in and criteria`() {
    val criteria = andCriteria(MultiFilter(name = "Alice", age = 25))
    val keys = criteria.flatMap { it.keys }
    assertTrue(keys.contains("name"))
    assertTrue(keys.contains("age"))
  }

  @Test
  fun `null field is excluded from multi-field criteria`() {
    val criteria = andCriteria(MultiFilter(name = "Alice", age = null))
    val keys = criteria.flatMap { it.keys }
    assertTrue(keys.contains("name"))
    assertFalse(keys.contains("age"))
  }
}
