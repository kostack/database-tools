package io.github.kostack.database_tools.purger

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.binding.BindMarkersFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.function.BiFunction
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class R2dbcDbPurgerTest {
  private val connectionFactory = mockk<ConnectionFactory>()
  private val connection = mockk<Connection>()
  private val executedSql = mutableListOf<String>()

  init {
    every { connectionFactory.create() } returns Mono.just(connection)
    every { connection.close() } returns Mono.empty()
    every { connection.createStatement(any()) } answers {
      val sql = firstArg<String>()
      val statement = mockk<Statement>()
      val result = mockk<Result>()
      every { result.rowsUpdated } returns Mono.just(0L)
      every { statement.execute() } answers {
        executedSql.add(sql)
        Mono.just(result)
      }
      statement
    }
  }

  private val purger =
    R2dbcDbPurger(
      DatabaseClient
        .builder()
        .connectionFactory(
          connectionFactory
        ).bindMarkers(BindMarkersFactory.anonymous("?"))
        .build()
    )

  @Test
  fun `should purge tables on one connection and escape identifiers`() =
    runTest {
      tables("users", "order`items")

      purger.purge()

      assertEquals(
        listOf(
          "SET FOREIGN_KEY_CHECKS = 0",
          "TRUNCATE TABLE `users`",
          "TRUNCATE TABLE `order``items`",
          "SET FOREIGN_KEY_CHECKS = 1"
        ),
        executedSql.drop(1)
      )
      verify(exactly = 1) { connectionFactory.create() }
      verifySequence {
        connection.createStatement(match { it.contains("SELECT TABLE_NAME") && it.contains("flyway_schema_history") })
        connection.createStatement("SET FOREIGN_KEY_CHECKS = 0")
        connection.createStatement("TRUNCATE TABLE `users`")
        connection.createStatement("TRUNCATE TABLE `order``items`")
        connection.createStatement("SET FOREIGN_KEY_CHECKS = 1")
        connection.close()
      }
    }

  @Test
  fun `should restore foreign key checks when truncate fails`() =
    runTest {
      tables("users", "orders")
      val failure = IllegalStateException("Truncate failed")
      val statement = mockk<Statement>()
      every { connection.createStatement("TRUNCATE TABLE `users`") } returns statement
      every { statement.execute() } returns Mono.error(failure)

      assertEquals(failure.message, assertFailsWith<IllegalStateException> { purger.purge() }.message)

      verifyOrderOfFailureCleanup()
      verify(exactly = 0) { connection.createStatement("TRUNCATE TABLE `orders`") }
    }

  @Test
  fun `should handle an empty database`() =
    runTest {
      tables()

      purger.purge()

      assertEquals(listOf("SET FOREIGN_KEY_CHECKS = 0", "SET FOREIGN_KEY_CHECKS = 1"), executedSql.drop(1))
      verify(exactly = 1) { connection.close() }
    }

  @Test
  fun `should report restoration failure`() =
    runTest {
      tables()
      val statement = mockk<Statement>()
      val failure = IllegalStateException("Restore failed")
      every { connection.createStatement("SET FOREIGN_KEY_CHECKS = 1") } returns statement
      every { statement.execute() } returns Mono.error(failure)

      val thrown = assertFailsWith<RuntimeException> { purger.purge() }

      assertEquals(failure.message, generateSequence<Throwable>(thrown) { it.cause }.last().message)
      verify(exactly = 1) { connection.close() }
    }

  private fun verifyOrderOfFailureCleanup() {
    io.mockk.verifyOrder {
      connection.createStatement("TRUNCATE TABLE `users`")
      connection.createStatement("SET FOREIGN_KEY_CHECKS = 1")
      connection.close()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `cancellation should wait for restoration before closing connection`() =
    runTest {
      tables("users")
      val truncateStatement = mockk<Statement>()
      every { connection.createStatement("TRUNCATE TABLE `users`") } returns truncateStatement
      every { truncateStatement.execute() } returns Mono.never()
      val restoreStatement = mockk<Statement>()
      val restoreResult = mockk<Result>()
      val restored = Sinks.empty<Long>()
      every { connection.createStatement("SET FOREIGN_KEY_CHECKS = 1") } returns restoreStatement
      every { restoreStatement.execute() } returns Mono.just(restoreResult)
      every { restoreResult.rowsUpdated } returns restored.asMono()

      val job = launch { purger.purge() }
      runCurrent()
      job.cancelAndJoin()

      verify(exactly = 1) { restoreStatement.execute() }
      verify(exactly = 0) { connection.close() }
      restored.tryEmitEmpty()
      verify(exactly = 1) { connection.close() }
    }

  private fun tables(vararg names: String) {
    val statement = mockk<Statement>()
    val result = mockk<Result>()
    every { connection.createStatement(match { it.contains("SELECT TABLE_NAME") }) } returns statement
    every { statement.execute() } answers {
      executedSql.add("SELECT TABLE_NAME")
      Mono.just(result)
    }
    every { result.map(any<BiFunction<Row, RowMetadata, String>>()) } answers {
      val mapper = firstArg<BiFunction<Row, RowMetadata, String>>()
      Flux.fromIterable(names.toList()).map { name ->
        val row = mockk<Row>()
        every { row.get("TABLE_NAME", String::class.java) } returns name
        mapper.apply(row, mockk())
      }
    }
  }
}
