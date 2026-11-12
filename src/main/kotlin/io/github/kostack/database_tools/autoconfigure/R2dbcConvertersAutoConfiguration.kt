package io.github.kostack.database_tools.autoconfigure

import io.github.kostack.database_tools.r2dbc.converter.R2dbcConverters
import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.DialectResolver
import tools.jackson.databind.ObjectMapper

@AutoConfiguration(
  afterName = [
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
    "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration"
  ],
  beforeName = ["org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration"]
)
@ConditionalOnClass(
  name = [
    "org.springframework.data.r2dbc.convert.R2dbcCustomConversions",
    "io.r2dbc.spi.ConnectionFactory",
    "tools.jackson.databind.ObjectMapper"
  ]
)
class R2dbcConvertersAutoConfiguration {
  @Bean
  @ConditionalOnBean(ConnectionFactory::class, ObjectMapper::class)
  @ConditionalOnMissingBean
  fun r2dbcCustomConversions(
    objectMapper: ObjectMapper,
    connectionFactory: ConnectionFactory
  ): R2dbcCustomConversions =
    R2dbcCustomConversions.of(DialectResolver.getDialect(connectionFactory), R2dbcConverters(objectMapper).toList())
}
