package io.github.kostack.database_tools.r2dbc.transactional

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitOne
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TransactionalManagerIntegrationTest {
  private val connectionFactory: ConnectionFactory =
    ConnectionFactories.get("r2dbc:h2:mem:///tx-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
  private val databaseClient = DatabaseClient.create(connectionFactory)
  private val transactionalOperator = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))

  private var manager = newManager()

  private fun newManager(shutdownTimeout: Duration = 5.seconds) =
    TransactionalManager(shutdownTimeout = shutdownTimeout).also { it.start() }

  @BeforeEach
  fun setUp() =
    runBlocking {
      databaseClient.sql("CREATE TABLE users (name VARCHAR(64) PRIMARY KEY)").await()
    }

  @AfterEach
  fun tearDown() {
    manager.stop()
  }

  @Test
  fun `runs action after commit and sees committed data`(): Unit =
    runBlocking {
      val seen = CompletableDeferred<Long>()

      transactionalOperator.executeAndAwait {
        insertUser("alice")
        manager.afterCommit("count-users") { seen.complete(countUsers()) }
      }

      assertEquals(1L, withTimeout(5.seconds) { seen.await() })
    }

  @Test
  fun `does not run action when transaction rolls back`(): Unit =
    runBlocking {
      val calls = AtomicInteger()

      transactionalOperator.executeAndAwait { status ->
        insertUser("alice")
        manager.afterCommit("never") { calls.incrementAndGet() }
        status.setRollbackOnly()
      }
      manager.stop() // drains anything that might have been scheduled

      assertEquals(0, calls.get())
      assertEquals(0L, countUsers())
    }

  @Test
  fun `does not run action when transaction fails`(): Unit =
    runBlocking {
      val calls = AtomicInteger()

      assertFailsWith<IllegalStateException> {
        transactionalOperator.executeAndAwait {
          insertUser("alice")
          manager.afterCommit("never") { calls.incrementAndGet() }
          error("boom")
        }
      }
      manager.stop()

      assertEquals(0, calls.get())
      assertEquals(0L, countUsers())
    }

  @Test
  fun `failing action does not fail the committed transaction`(): Unit =
    runBlocking {
      val after = CompletableDeferred<Unit>()

      transactionalOperator.executeAndAwait {
        insertUser("alice")
        manager.afterCommit("fails") { error("listener failed") }
        manager.afterCommit("still-runs") { after.complete(Unit) }
      }

      withTimeout(5.seconds) { after.await() }
      assertEquals(1L, countUsers())
    }

  @Test
  fun `caller does not wait for after-commit action`(): Unit =
    runBlocking {
      val release = CompletableDeferred<Unit>()
      val finished = CompletableDeferred<Unit>()

      withTimeout(5.seconds) {
        transactionalOperator.executeAndAwait {
          insertUser("alice")
          manager.afterCommit("slow") {
            release.await()
            finished.complete(Unit)
          }
        }
      }

      assertFalse(finished.isCompleted)
      release.complete(Unit)
      withTimeout(5.seconds) { finished.await() }
    }

  @Test
  fun `action writes are committed in their own transaction`(): Unit =
    runBlocking {
      val done = CompletableDeferred<Unit>()

      transactionalOperator.executeAndAwait {
        insertUser("alice")
        manager.afterCommit("write") {
          insertUser("bob")
          done.complete(Unit)
        }
      }

      withTimeout(5.seconds) { done.await() }
      assertEquals(2L, countUsers())
    }

  @Test
  fun `cancels action that exceeds its timeout`(): Unit =
    runBlocking {
      val started = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      val next = CompletableDeferred<Unit>()

      transactionalOperator.executeAndAwait {
        manager.afterCommit("hangs", timeout = 100.milliseconds) {
          started.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            cancelled.complete(Unit)
          }
        }
        manager.afterCommit("next") { next.complete(Unit) }
      }

      withTimeout(5.seconds) {
        started.await()
        cancelled.await()
        next.await()
      }
    }

  @Test
  fun `afterCommit fails outside a transaction`(): Unit =
    runBlocking {
      val failure = assertFailsWith<IllegalStateException> { manager.afterCommit("outside") {} }
      assertEquals("After-commit outside requires an active transaction", failure.message)
      assertIs<NoTransactionException>(failure.cause)
    }

  @Test
  fun `inTransaction runs action inside a transaction and fails outside`(): Unit =
    runBlocking {
      val result = transactionalOperator.executeAndAwait { manager.inTransaction("inside") { 42 } }
      assertEquals(42, result)

      val failure = assertFailsWith<IllegalStateException> { manager.inTransaction("outside") { 42 } }
      assertEquals("outside requires an active transaction", failure.message)
    }

  @Test
  fun `stop waits for running actions to finish`(): Unit =
    runBlocking {
      val started = CompletableDeferred<Unit>()
      val finished = AtomicInteger()

      transactionalOperator.executeAndAwait {
        manager.afterCommit("slow") {
          started.complete(Unit)
          delay(300.milliseconds)
          finished.incrementAndGet()
        }
      }
      withTimeout(5.seconds) { started.await() }

      manager.stop()

      assertEquals(1, finished.get())
      assertFalse(manager.isRunning)
    }

  @Test
  fun `stop cancels actions still running after shutdown timeout`(): Unit =
    runBlocking {
      manager.stop()
      manager = newManager(shutdownTimeout = 100.milliseconds)
      val started = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()

      transactionalOperator.executeAndAwait {
        manager.afterCommit("hangs", timeout = Duration.INFINITE) {
          started.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            cancelled.complete(Unit)
          }
        }
      }
      withTimeout(5.seconds) { started.await() }

      manager.stop()

      withTimeout(5.seconds) { cancelled.await() }
    }

  @Test
  fun `drops actions committed after stop`(): Unit =
    runBlocking {
      val calls = AtomicInteger()
      manager.stop()

      transactionalOperator.executeAndAwait {
        insertUser("alice")
        manager.afterCommit("late") { calls.incrementAndGet() }
      }

      delay(100.milliseconds)
      assertEquals(0, calls.get())
      assertEquals(1L, countUsers(), "transaction itself still commits")
    }

  private suspend fun insertUser(name: String) {
    databaseClient.sql("INSERT INTO users (name) VALUES (:name)").bind("name", name).await()
  }

  private suspend fun countUsers(): Long =
    databaseClient
      .sql("SELECT COUNT(*) AS c FROM users")
      .map { row ->
        row.get("c", java.lang.Long::class.java)!!.toLong()
      }.awaitOne()
}
