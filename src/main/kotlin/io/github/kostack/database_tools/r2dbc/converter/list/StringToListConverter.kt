package io.github.kostack.database_tools.r2dbc.converter.list

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@ReadingConverter
class StringToListConverter(
  private val objectMapper: ObjectMapper
) : Converter<String, List<String>> {
  override fun convert(source: String): List<String> {
    if (source.isBlank()) return emptyList()

    return try {
      objectMapper.readValue(source, LIST_TYPE)?.filterNotNull() ?: emptyList()
    } catch (e: JacksonException) {
      log.error("Failed to convert JSON column to list (length {})", source.length, e)
      emptyList()
    }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(StringToListConverter::class.java)

    private val LIST_TYPE = object : TypeReference<List<String?>>() {}
  }
}
