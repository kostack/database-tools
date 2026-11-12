package io.github.kostack.database_tools.r2dbc.converter.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class StringToListConverterTest {
  private val objectMapper = JsonMapper.builder().build()
  private val converter = StringToListConverter(objectMapper)

  @Test
  fun `convert should return a list of strings for valid JSON input`() {
    assertEquals(listOf("apple", "banana", "cherry"), converter.convert("""["apple", "banana", "cherry"]"""))
  }

  @Test
  fun `convert should return an empty list for empty JSON array`() {
    assertTrue(converter.convert("[]").isEmpty())
  }

  @Test
  fun `convert should drop JSON null elements`() {
    assertEquals(listOf("a", "b"), converter.convert("""[null, "a", null, "b"]"""))
  }

  @Test
  fun `convert should read JSON scalars as strings`() {
    assertEquals(listOf("1", "2.5", "true"), converter.convert("[1, 2.5, true]"))
  }

  @Test
  fun `convert should round-trip with ListToStringConverter`() {
    val original = listOf("Hello, World!", "quote\"", "line\nbreak", "Здравей 👋", "")

    assertEquals(original, converter.convert(ListToStringConverter(objectMapper).convert(original)))
  }

  @Test
  fun `convert should return an empty list for blank input`() {
    assertTrue(converter.convert("").isEmpty())
    assertTrue(converter.convert("   ").isEmpty())
  }

  @Test
  fun `convert should return an empty list for JSON null literal`() {
    assertTrue(converter.convert("null").isEmpty())
  }

  @Test
  fun `convert should return an empty list for invalid JSON input`() {
    assertTrue(converter.convert("invalid-json").isEmpty())
  }

  @Test
  fun `convert should return an empty list for JSON object input`() {
    assertTrue(converter.convert("""{"a":1}""").isEmpty())
  }

  @Test
  fun `convert should return an empty list for nested arrays or objects`() {
    assertTrue(converter.convert("""["a", ["b"]]""").isEmpty())
    assertTrue(converter.convert("""["a", {"b":1}]""").isEmpty())
  }
}
