package io.github.kostack.database_tools.r2dbc.converter.map

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.WritingConverter
import tools.jackson.databind.ObjectMapper

@WritingConverter
class MapToJsonConverter(
  private val objectMapper: ObjectMapper
) : Converter<Map<String, Any?>, String> {
  override fun convert(source: Map<String, Any?>): String = objectMapper.writeValueAsString(source)
}
