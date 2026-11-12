package io.github.kostack.database_tools.r2dbc.converter.map

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@ReadingConverter
class JsonToMapConverter(
  private val objectMapper: ObjectMapper
) : Converter<String, Map<String, Any>> {
  override fun convert(source: String): Map<String, Any> =
    objectMapper.readValue(source, object : TypeReference<Map<String, Any>>() {})
}
