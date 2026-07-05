package io.github.kostack.database_tools.autoconfigure

import io.github.kostack.database_tools.paginator.MongoDbPaginator
import io.github.kostack.database_tools.paginator.R2dbcPaginator
import io.github.kostack.database_tools.purger.MongoDbPurger
import io.github.kostack.database_tools.purger.R2dbcDbPurger
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient

class DatabaseToolsAutoConfigurationTest {
  private val contextRunner =
    ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DatabaseToolsAutoConfiguration::class.java))

  @Test
  fun `configures mongo tools when mongo dependencies are available`() {
    contextRunner
      .withUserConfiguration(MongoDependenciesConfiguration::class.java)
      .run { context ->
        assertThat(context).hasSingleBean(MongoDbPaginator::class.java)
        assertThat(context).hasSingleBean(MongoDbPurger::class.java)
      }
  }

  @Test
  fun `configures r2dbc tools when r2dbc dependencies are available`() {
    contextRunner
      .withUserConfiguration(R2dbcDependenciesConfiguration::class.java)
      .run { context ->
        assertThat(context).hasSingleBean(R2dbcPaginator::class.java)
        assertThat(context).hasSingleBean(R2dbcDbPurger::class.java)
      }
  }

  @Test
  fun `backs off when users provide their own tools`() {
    contextRunner
      .withUserConfiguration(
        MongoDependenciesConfiguration::class.java,
        UserProvidedMongoToolsConfiguration::class.java
      ).run { context ->
        assertThat(context).hasSingleBean(MongoDbPaginator::class.java)
        assertThat(context).hasSingleBean(MongoDbPurger::class.java)
        assertThat(context).getBean(MongoDbPaginator::class.java).isSameAs(context.getBean("customMongoDbPaginator"))
        assertThat(context).getBean(MongoDbPurger::class.java).isSameAs(context.getBean("customMongoDbPurger"))
      }
  }

  @Test
  fun `does not configure mongo tools when mongo classes are missing`() {
    contextRunner
      .withClassLoader(FilteredClassLoader("org.springframework.data.mongodb"))
      .run { context ->
        assertThat(context).doesNotHaveBean(MongoDbPaginator::class.java)
        assertThat(context).doesNotHaveBean(MongoDbPurger::class.java)
      }
  }

  @Configuration(proxyBeanMethods = false)
  private class MongoDependenciesConfiguration {
    @Bean
    fun reactiveMongoTemplate(): ReactiveMongoTemplate = mockk(relaxed = true)

    @Bean
    fun reactiveMongoOperations(reactiveMongoTemplate: ReactiveMongoTemplate): ReactiveMongoOperations =
      reactiveMongoTemplate
  }

  @Configuration(proxyBeanMethods = false)
  private class R2dbcDependenciesConfiguration {
    @Bean
    fun r2dbcEntityTemplate(): R2dbcEntityTemplate = mockk(relaxed = true)

    @Bean
    fun databaseClient(): DatabaseClient = mockk(relaxed = true)
  }

  @Configuration(proxyBeanMethods = false)
  private class UserProvidedMongoToolsConfiguration {
    @Bean
    fun customMongoDbPaginator(): MongoDbPaginator = mockk()

    @Bean
    fun customMongoDbPurger(): MongoDbPurger = mockk()
  }
}
