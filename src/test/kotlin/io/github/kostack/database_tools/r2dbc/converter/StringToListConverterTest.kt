package io.github.kostack.database_tools.r2dbc.converter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringToListConverterTest {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Tests the behavior of the `convert` method of `StringToListConverter` when the input string
   * is a valid JSON that maps to a list of integers.
   */
  @Test
  fun `convert should return a list of integers for valid JSON input`() {
    // Arrange
    val inputJson = "[1, 2, 3]"
    val serializer = ListSerializer(Int.serializer())
    val converter = StringToListConverter(json, serializer)

    // Act
    val result = converter.convert(inputJson)

    // Assert
    assertEquals(listOf(1, 2, 3), result)
  }

  /**
   * Tests the behavior of the `convert` method of `StringToListConverter` when the input string
   * is a valid JSON that maps to a list of strings.
   */
  @Test
  fun `convert should return a list of strings for valid JSON input`() {
    // Arrange
    val inputJson = """["apple", "banana", "cherry"]"""
    val serializer = ListSerializer(String.serializer())
    val converter = StringToListConverter(json, serializer)

    // Act
    val result = converter.convert(inputJson)

    // Assert
    assertEquals(listOf("apple", "banana", "cherry"), result)
  }

  /**
   * Tests the behavior of the `convert` method of `StringToListConverter` when the input string
   * is blank.
   */
  @Test
  fun `convert should return an empty list for blank input`() {
    // Arrange
    val inputJson = "   "
    val serializer = ListSerializer(Int.serializer())
    val converter = StringToListConverter(json, serializer)

    // Act
    val result = converter.convert(inputJson)

    // Assert
    assertTrue(result.isEmpty())
  }

  /**
   * Tests the behavior of the `convert` method of `StringToListConverter` when the input string
   * is invalid JSON.
   */
  @Test
  fun `convert should return an empty list for invalid JSON input`() {
    // Arrange
    val inputJson = "invalid-json"
    val serializer = ListSerializer(Int.serializer())
    val converter = StringToListConverter(json, serializer)

    // Act
    val result = converter.convert(inputJson)

    // Assert
    assertTrue(result.isEmpty())
  }

  /**
   * Tests the behavior of the `convert` method of `StringToListConverter` when
   * a JSON input contains mixed data types and fails deserialization.
   */
  @Test
  fun `convert should return an empty list for JSON input with mixed invalid types`() {
    // Arrange
    val inputJson = """[1, "two", 3]"""
    val serializer = ListSerializer(Int.serializer())
    val converter = StringToListConverter(json, serializer)

    // Act
    val result = converter.convert(inputJson)

    // Assert
    assertTrue(result.isEmpty())
  }
}
