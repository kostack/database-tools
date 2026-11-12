package io.github.kostack.database_tools.r2dbc.converter.map

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonToMapConverterTest {
  private val objectMapper = JsonMapper.builder().build()
  private val converter = JsonToMapConverter(objectMapper)

  @Test
  fun `converts flat JSON object to map`() {
    val result = converter.convert("""{"name":"alice","active":true}""")

    assertEquals(mapOf("name" to "alice", "active" to true), result)
  }

  @Test
  fun `converts empty JSON object to empty map`() {
    assertTrue(converter.convert("{}").isEmpty())
  }

  @Test
  fun `maps JSON numbers to the smallest fitting Java type`() {
    val result = converter.convert("""{"int":1,"long":10000000000,"double":1.5}""")

    assertEquals(1, result["int"])
    assertEquals(10_000_000_000L, result["long"])
    assertEquals(1.5, result["double"])
  }

  @Test
  fun `converts nested objects and arrays`() {
    val result = converter.convert("""{"address":{"city":"Sofia"},"tags":["a","b"]}""")

    assertEquals(mapOf("city" to "Sofia"), result["address"])
    assertEquals(listOf("a", "b"), result["tags"])
  }

  @Test
  fun `keeps JSON null values as null entries`() {
    val result = converter.convert("""{"name":null}""")

    assertTrue(result.containsKey("name"))
    assertEquals(null, result["name"])
  }

  @Test
  fun `preserves key order`() {
    val result = converter.convert("""{"b":1,"a":2,"c":3}""")

    assertEquals(listOf("b", "a", "c"), result.keys.toList())
  }

  @Test
  fun `round-trips with MapToJsonConverter`() {
    val original = mapOf("name" to "alice", "age" to 30, "tags" to listOf("x"), "meta" to mapOf("k" to "v"))
    val json = MapToJsonConverter(objectMapper).convert(original)

    assertEquals(original, converter.convert(json))
  }

  @Test
  fun `round-trips null values with MapToJsonConverter`() {
    val original = mapOf("name" to "alice", "nickname" to null)
    val json = MapToJsonConverter(objectMapper).convert(original)

    assertEquals("""{"name":"alice","nickname":null}""", json)
    assertEquals(original, converter.convert(json))
  }

  @Test
  fun `returns empty map for invalid JSON`() {
    assertTrue(converter.convert("not-json").isEmpty())
  }

  @Test
  fun `returns empty map for truncated JSON`() {
    assertTrue(converter.convert("""{"name":"ali""").isEmpty())
  }

  @Test
  fun `returns empty map for JSON array`() {
    assertTrue(converter.convert("""["a","b"]""").isEmpty())
  }

  @Test
  fun `returns empty map for JSON scalar`() {
    assertTrue(converter.convert("42").isEmpty())
  }

  @Test
  fun `returns empty map for empty and blank input`() {
    assertTrue(converter.convert("").isEmpty())
    assertTrue(converter.convert("   ").isEmpty())
  }

  @Test
  fun `returns empty map for JSON null literal`() {
    assertTrue(converter.convert("null").isEmpty())
  }
}
