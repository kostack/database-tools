package io.github.kostack.database_tools.r2dbc.converter

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter

@ReadingConverter
class StringToListConverter<T>(
  private val json: Json,
  private val serializer: KSerializer<List<T>>
) : Converter<String, List<T>> {
  override fun convert(source: String): List<T> {
    if (source.isBlank()) return emptyList()

    return try {
      json.decodeFromString(serializer, source)
    } catch (e: Exception) {
      log.error("Failed to convert string to list: $source", e)
      emptyList()
    }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(StringToListConverter::class.java)
  }
}
