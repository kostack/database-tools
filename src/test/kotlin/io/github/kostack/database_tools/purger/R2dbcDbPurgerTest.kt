package io.github.kostack.database_tools.purger

import io.mockk.every
import io.mockk.mockk
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.RowsFetchSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.function.BiFunction

class R2dbcDbPurgerTest {
  private val databaseClient = mockk<DatabaseClient>()

  private val disableFkSpec = mockk<DatabaseClient.GenericExecuteSpec>()
  private val selectSpec = mockk<DatabaseClient.GenericExecuteSpec>()
  private val truncateSpec = mockk<DatabaseClient.GenericExecuteSpec>()
  private val enableFkSpec = mockk<DatabaseClient.GenericExecuteSpec>()

  private val purger = R2dbcDbPurger(databaseClient)

  @Test
  fun `should purge tables`() =
    runTest {
      // FK OFF
      every { databaseClient.sql("SET FOREIGN_KEY_CHECKS = 0") } returns disableFkSpec
      every { disableFkSpec.then() } returns Mono.empty()

      // SELECT TABLES
      every { databaseClient.sql(match<String> { it.contains("SELECT TABLE_NAME") }) } returns selectSpec

      val rowsFetchSpec = mockk<RowsFetchSpec<String>>()

      every {
        selectSpec.map(any<BiFunction<Row, RowMetadata, String>>())
      } returns rowsFetchSpec

      every { rowsFetchSpec.all() } returns Flux.just("users", "orders")

      // TRUNCATE
      every { databaseClient.sql(match<String> { it.startsWith("TRUNCATE TABLE") }) } returns truncateSpec
      every { truncateSpec.then() } returns Mono.empty()

      // FK ON
      every { databaseClient.sql("SET FOREIGN_KEY_CHECKS = 1") } returns enableFkSpec
      every { enableFkSpec.then() } returns Mono.empty()

      purger.purge()
    }
}
