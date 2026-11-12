package io.github.kostack.database_tools.autoconfigure

import io.github.kostack.database_tools.r2dbc.converter.map.JsonToMapConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.convert.R2dbcConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.H2Dialect
import java.util.UUID

class R2dbcConvertersAutoConfigurationTest {
  private val contextRunner =
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(
          JacksonAutoConfiguration::class.java,
          R2dbcAutoConfiguration::class.java,
          DataR2dbcAutoConfiguration::class.java,
          R2dbcConvertersAutoConfiguration::class.java
        )
      ).withPropertyValues("spring.r2dbc.url=r2dbc:h2:mem:///converters-${UUID.randomUUID()}")

  @Test
  fun `registers library converters in place of Boot's default conversions`() {
    contextRunner.run { context ->
      assertThat(context).hasSingleBean(R2dbcCustomConversions::class.java)
      val conversions = context.getBean(R2dbcCustomConversions::class.java)

      assertThat(conversions.hasCustomReadTarget(String::class.java, List::class.java)).isTrue()
      assertThat(conversions.hasCustomWriteTarget(List::class.java, String::class.java)).isTrue()
      assertThat(conversions.hasCustomReadTarget(String::class.java, Map::class.java)).isTrue()
      assertThat(conversions.hasCustomWriteTarget(Map::class.java, String::class.java)).isTrue()
    }
  }

  @Test
  fun `R2dbcConverter uses the library converters`() {
    contextRunner.run { context ->
      val converter = context.getBean(R2dbcConverter::class.java)

      assertThat(converter.conversionService.convert("""{"a":1}""", Map::class.java)).isEqualTo(mapOf("a" to 1))
      assertThat(converter.conversionService.convert("""["x","y"]""", List::class.java)).isEqualTo(listOf("x", "y"))
    }
  }

  @Test
  fun `backs off when the user defines R2dbcCustomConversions`() {
    contextRunner
      .withUserConfiguration(UserConversionsConfiguration::class.java)
      .run { context ->
        assertThat(context).hasSingleBean(R2dbcCustomConversions::class.java)
        assertThat(context.getBean(R2dbcCustomConversions::class.java))
          .isSameAs(context.getBean("customConversions"))
      }
  }

  @Test
  fun `leaves Boot's default conversions when Jackson is missing`() {
    contextRunner
      .withClassLoader(FilteredClassLoader("tools.jackson"))
      .run { context ->
        assertThat(context).hasSingleBean(R2dbcCustomConversions::class.java)
        val conversions = context.getBean(R2dbcCustomConversions::class.java)
        assertThat(conversions.hasCustomReadTarget(String::class.java, Map::class.java)).isFalse()
      }
  }

  @Configuration(proxyBeanMethods = false)
  private class UserConversionsConfiguration {
    @Bean
    fun customConversions(): R2dbcCustomConversions =
      R2dbcCustomConversions.of(
        H2Dialect.INSTANCE,
        listOf(
          JsonToMapConverter(
            tools.jackson.databind.json.JsonMapper
              .builder()
              .build()
          )
        )
      )
  }
}
