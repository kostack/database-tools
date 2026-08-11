package io.github.kostack.database_tools.autoconfigure

import io.github.kostack.database_tools.paginator.MongoDbPaginator
import io.github.kostack.database_tools.paginator.R2dbcPaginator
import io.github.kostack.database_tools.purger.MongoDbPurger
import io.github.kostack.database_tools.purger.R2dbcDbPurger
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient

@AutoConfiguration(
  afterName = [
    "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration",
    "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration"
  ]
)
class DatabaseToolsAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = ["org.springframework.data.mongodb.core.ReactiveMongoTemplate"])
  class MongoConfiguration {
    @Bean
    @ConditionalOnBean(ReactiveMongoTemplate::class)
    @ConditionalOnMissingBean
    fun mongoDbPaginator(reactiveMongoTemplate: ReactiveMongoTemplate): MongoDbPaginator =
      MongoDbPaginator(reactiveMongoTemplate)
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = ["org.springframework.data.mongodb.core.ReactiveMongoOperations"])
  class MongoPurgerConfiguration {
    @Bean
    @ConditionalOnBean(ReactiveMongoOperations::class)
    @ConditionalOnMissingBean
    fun mongoDbPurger(reactiveMongoOperations: ReactiveMongoOperations): MongoDbPurger =
      MongoDbPurger(reactiveMongoOperations)
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = ["org.springframework.data.r2dbc.core.R2dbcEntityTemplate"])
  class R2dbcConfiguration {
    @Bean
    @ConditionalOnBean(R2dbcEntityTemplate::class)
    @ConditionalOnMissingBean
    fun r2dbcPaginator(entityTemplate: R2dbcEntityTemplate): R2dbcPaginator = R2dbcPaginator(entityTemplate)
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = ["org.springframework.r2dbc.core.DatabaseClient"])
  class R2dbcPurgerConfiguration {
    @Bean
    @ConditionalOnBean(DatabaseClient::class)
    @ConditionalOnMissingBean
    fun r2dbcDbPurger(databaseClient: DatabaseClient): R2dbcDbPurger = R2dbcDbPurger(databaseClient)
  }
}
