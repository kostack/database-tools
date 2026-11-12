package io.github.kostack.database_tools.r2dbc.converter.list

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.WritingConverter
import tools.jackson.databind.ObjectMapper

@WritingConverter
class ListToStringConverter(
  private val objectMapper: ObjectMapper
) : Converter<List<String>, String> {
  override fun convert(source: List<String>): String = objectMapper.writeValueAsString(source)
}
