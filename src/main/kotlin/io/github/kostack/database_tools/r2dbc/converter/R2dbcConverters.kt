package io.github.kostack.database_tools.r2dbc.converter

import io.github.kostack.database_tools.r2dbc.converter.list.ListToStringConverter
import io.github.kostack.database_tools.r2dbc.converter.list.StringToListConverter
import io.github.kostack.database_tools.r2dbc.converter.map.JsonToMapConverter
import io.github.kostack.database_tools.r2dbc.converter.map.MapToJsonConverter
import org.springframework.core.convert.converter.Converter
import tools.jackson.databind.ObjectMapper

class R2dbcConverters(
  private val objectMapper: ObjectMapper
) {
  fun toList(): List<Converter<*, *>> =
    listOf(
      ListToStringConverter(objectMapper),
      StringToListConverter(objectMapper),
      MapToJsonConverter(objectMapper),
      JsonToMapConverter(objectMapper)
    )
}
