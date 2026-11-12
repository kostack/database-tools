package io.github.kostack.database_tools.r2dbc.converter.map

import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapToJsonConverterTest {
  private val objectMapper = JsonMapper.builder().build()
  private val converter = MapToJsonConverter(objectMapper)

  @Test
  fun `converts empty map to empty JSON object`() {
    assertEquals("{}", converter.convert(emptyMap()))
  }

  @Test
  fun `converts flat map to JSON object`() {
    val result = converter.convert(mapOf("name" to "alice", "active" to true, "age" to 30))

    assertEquals("""{"name":"alice","active":true,"age":30}""", result)
  }

  @Test
  fun `writes null values as JSON null`() {
    assertEquals("""{"nickname":null}""", converter.convert(mapOf("nickname" to null)))
  }

  @Test
  fun `converts nested maps and lists`() {
    val result =
      converter.convert(
        mapOf(
          "address" to mapOf("city" to "Sofia"),
          "tags" to listOf("a", "b"),
          "matrix" to listOf(listOf(1, 2), emptyList<Int>())
        )
      )

    assertEquals("""{"address":{"city":"Sofia"},"tags":["a","b"],"matrix":[[1,2],[]]}""", result)
  }

  @Test
  fun `keeps map insertion order`() {
    val source = linkedMapOf<String, Any?>("b" to 1, "a" to 2, "c" to 3)

    assertEquals("""{"b":1,"a":2,"c":3}""", converter.convert(source))
  }

  @Test
  fun `writes numeric types without losing precision`() {
    val result =
      converter.convert(
        mapOf(
          "long" to 10_000_000_000L,
          "double" to 1.5,
          "decimal" to BigDecimal("12345678901234567890.123456789")
        )
      )

    assertEquals("""{"long":10000000000,"double":1.5,"decimal":12345678901234567890.123456789}""", result)
  }

  @Test
  fun `escapes special characters in keys and values`() {
    val result = converter.convert(mapOf("quote\"key" to "line\nbreak \\ tab\t", "unicode" to "Здравей 👋"))

    assertEquals("""{"quote\"key":"line\nbreak \\ tab\t","unicode":"Здравей 👋"}""", result)
  }

  @Test
  fun `writes UUID and java time values as strings`() {
    val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    val result = converter.convert(mapOf("id" to id, "at" to Instant.parse("2026-11-12T10:15:30Z")))

    assertEquals("""{"id":"123e4567-e89b-12d3-a456-426614174000","at":"2026-11-12T10:15:30Z"}""", result)
  }

  @Test
  fun `round-trips with JsonToMapConverter`() {
    val original =
      mapOf(
        "name" to "alice",
        "age" to 30,
        "nickname" to null,
        "tags" to listOf("x", "y"),
        "meta" to mapOf("nested" to mapOf("deep" to true))
      )

    assertEquals(original, JsonToMapConverter(objectMapper).convert(converter.convert(original)))
  }

  @Test
  fun `writes objects without properties as empty JSON objects`() {
    assertEquals("""{"value":{}}""", converter.convert(mapOf("value" to Any())))
  }

  @Test
  fun `throws when a value getter fails`() {
    assertFailsWith<JacksonException> { converter.convert(mapOf("value" to FailingBean())) }
  }

  @Suppress("unused")
  private class FailingBean {
    val broken: String get() = error("cannot read")
  }
}
