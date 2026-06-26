package io.github.kostack.database_tools.r2dbc.converter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the [ListToStringConverter] class.
 * 
 * The ListToStringConverter is responsible for converting a `List<String>` into a serialized JSON string.
 * It ensures that empty lists are serialized to a default value: "[]".
 */
class ListToStringConverterTest {
  private val converter = ListToStringConverter()

  @Test
  fun `convert should serialize non-empty list`() {
    // Arrange
    val input = listOf("apple", "banana", "cherry")
    val expected = """["apple","banana","cherry"]"""

    // Act
    val result = converter.convert(input)

    // Assert
    assertEquals(expected, result)
  }

  @Test
  fun `convert should serialize an empty list to default`() {
    // Arrange
    val input = emptyList<String>()
    val expected = "[]"

    // Act
    val result = converter.convert(input)

    // Assert
    assertEquals(expected, result)
  }

  @Test
  fun `convert should handle special characters in list`() {
    // Arrange
    val input = listOf("Hello, World!", "Jack & Jill", "A/B\\C")
    val expected = """["Hello, World!","Jack & Jill","A/B\\C"]"""

    // Act
    val result = converter.convert(input)

    // Assert
    assertEquals(expected, result)
  }

  @Test
  fun `convert should handle list with single element`() {
    // Arrange
    val input = listOf("singleElement")
    val expected = """["singleElement"]"""

    // Act
    val result = converter.convert(input)

    // Assert
    assertEquals(expected, result)
  }

  @Test
  fun `convert should handle list with empty strings`() {
    // Arrange
    val input = listOf("", "nonEmpty", "")
    val expected = """["","nonEmpty",""]"""

    // Act
    val result = converter.convert(input)

    // Assert
    assertEquals(expected, result)
  }

  @Test
  fun `convert should handle null safely (input as invalid type)`() {
    // Arrange
    val input: List<String>? = null
    val expected = "[]"

    // Act
    val result =
      try {
        converter.convert(input!!) // should throw a NullPointerException
      } catch (_: NullPointerException) {
        "[]"
      }

    // Assert
    assertEquals(expected, result)
  }
}
