package io.github.kostack.database_tools.r2dbc.transactional

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs actions after the current reactive transaction commits, in a background scope owned by this bean.
 *
 * On application shutdown the bean stops accepting new after-commit actions, waits up to
 * [shutdownTimeout] for running ones to finish, then cancels whatever is left. This happens in
 * the [SmartLifecycle] stop phase: after the web server has stopped taking new requests, and
 * before singletons such as the connection pool are destroyed.
 */
class TransactionalManager(
  private val defaultTimeout: Duration = DEFAULT_AFTER_COMMIT_TIMEOUT,
  private val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
  dispatcher: CoroutineDispatcher = Dispatchers.Default
) : SmartLifecycle {
  private val job = SupervisorJob()
  private val scope =
    CoroutineScope(
      job +
        dispatcher +
        CoroutineName("after-commit") +
        CoroutineExceptionHandler { _, failure -> logger.error("After-commit action crashed", failure) }
    )
  private val accepting = AtomicBoolean(true)
  private val running = AtomicBoolean(false)

  suspend fun <T> inTransaction(
    name: String,
    action: suspend () -> T
  ): T {
    requireActiveTransaction(name)
    return action()
  }

  suspend fun afterCommit(
    name: String,
    timeout: Duration = defaultTimeout,
    action: suspend () -> Unit
  ) {
    val transactionManager = requireActiveTransaction("After-commit $name")
    check(transactionManager.isSynchronizationActive) {
      "After-commit callbacks require active transaction synchronization"
    }
    transactionManager.registerSynchronization(
      object : TransactionSynchronization {
        override fun afterCommit(): Mono<Void> = Mono.fromRunnable { launch(name, timeout, action) }
      }
    )
  }

  private fun launch(
    name: String,
    timeout: Duration,
    action: suspend () -> Unit
  ) {
    if (!accepting.get()) {
      logger.warn("After-commit {} dropped: application is shutting down", name)
      return
    }
    scope.launch { runAfterCommit(name, timeout, action) }
  }

  private suspend fun runAfterCommit(
    name: String,
    timeout: Duration,
    action: suspend () -> Unit
  ) {
    try {
      withTimeout(timeout) { action() }
    } catch (failure: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      logger.error("After-commit {} timed out after {}", name, timeout, failure)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      logger.error("After-commit {} failed", name, failure)
    }
  }

  private suspend fun requireActiveTransaction(name: String): TransactionSynchronizationManager {
    val transactionManager =
      try {
        TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
      } catch (failure: NoTransactionException) {
        throw IllegalStateException("$name requires an active transaction", failure)
      }
    check(transactionManager.isActualTransactionActive) {
      "$name requires an active transaction"
    }
    return transactionManager
  }

  override fun start() {
    running.set(true)
  }

  override fun stop() {
    if (!running.compareAndSet(true, false)) return
    accepting.set(false)
    val pending = job.children.toList()
    if (pending.isNotEmpty()) {
      logger.info("Waiting up to {} for {} after-commit action(s) to finish", shutdownTimeout, pending.size)
      val drained = runBlocking { withTimeoutOrNull(shutdownTimeout) { pending.joinAll() } } != null
      if (!drained) {
        logger.warn(
          "Cancelling {} after-commit action(s) still running after {}",
          job.children.count(),
          shutdownTimeout
        )
      }
    }
    scope.cancel("Application shutting down")
  }

  override fun isRunning(): Boolean = running.get()

  override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 1536

  companion object {
    private val logger = LoggerFactory.getLogger(TransactionalManager::class.java)

    val DEFAULT_AFTER_COMMIT_TIMEOUT: Duration = 30.seconds
    val DEFAULT_SHUTDOWN_TIMEOUT: Duration = 10.seconds
  }
}
