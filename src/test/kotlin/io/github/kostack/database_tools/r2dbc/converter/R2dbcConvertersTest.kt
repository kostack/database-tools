package io.github.kostack.database_tools.r2dbc.converter

import io.github.kostack.database_tools.r2dbc.converter.list.ListToStringConverter
import io.github.kostack.database_tools.r2dbc.converter.list.StringToListConverter
import io.github.kostack.database_tools.r2dbc.converter.map.JsonToMapConverter
import io.github.kostack.database_tools.r2dbc.converter.map.MapToJsonConverter
import org.junit.jupiter.api.Test
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.H2Dialect
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class R2dbcConvertersTest {
  private val converters = R2dbcConverters(JsonMapper.builder().build()).toList()

  @Test
  fun `exports every converter once`() {
    assertEquals(
      setOf(
        ListToStringConverter::class,
        StringToListConverter::class,
        MapToJsonConverter::class,
        JsonToMapConverter::class
      ),
      converters.map { it::class }.toSet()
    )
    assertEquals(4, converters.size)
  }

  @Test
  fun `registers reading and writing conversions`() {
    val conversions = R2dbcCustomConversions.of(H2Dialect.INSTANCE, converters)

    assertTrue(conversions.hasCustomWriteTarget(List::class.java, String::class.java))
    assertTrue(conversions.hasCustomReadTarget(String::class.java, List::class.java))
    assertTrue(conversions.hasCustomWriteTarget(Map::class.java, String::class.java))
    assertTrue(conversions.hasCustomReadTarget(String::class.java, Map::class.java))
  }
}
