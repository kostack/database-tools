package io.github.kostack.database_tools.r2dbc.converter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.WritingConverter

@WritingConverter
class ListToStringConverter : Converter<List<String>, String> {
  override fun convert(source: List<String>): String {
    if (source.isEmpty()) return "[]"

    return try {
      json.encodeToString(serializer, source)
    } catch (e: Exception) {
      log.warn("Failed to serialize List<String>: {}", source, e)
      "[]"
    }
  }

  companion object {
    val json =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }
    val serializer = ListSerializer(String.serializer())
    val log: Logger = LoggerFactory.getLogger(ListToStringConverter::class.java)
  }
}
