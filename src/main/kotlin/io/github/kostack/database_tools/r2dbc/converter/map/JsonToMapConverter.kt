package io.github.kostack.database_tools.r2dbc.converter.map

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@ReadingConverter
class JsonToMapConverter(
  private val objectMapper: ObjectMapper
) : Converter<String, Map<String, Any?>> {
  override fun convert(source: String): Map<String, Any?> {
    if (source.isBlank()) return emptyMap()

    return try {
      objectMapper.readValue(source, MAP_TYPE) ?: emptyMap()
    } catch (e: JacksonException) {
      log.error("Failed to convert JSON column to map (length {})", source.length, e)
      emptyMap()
    }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(JsonToMapConverter::class.java)

    private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
  }
}
