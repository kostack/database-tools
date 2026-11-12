package io.github.kostack.database_tools.purger

import io.r2dbc.spi.Connection
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class R2dbcDbPurger(
  private val databaseClient: DatabaseClient
) {
  suspend fun purge() {
    log.warn("Attempting to truncate all tables...")

    try {
      Mono
        .usingWhen(
          Mono.from(databaseClient.connectionFactory.create()),
          { connection ->
            Flux
              .from(
                connection
                  .createStatement(
                    """
                    SELECT TABLE_NAME
                    FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_TYPE = 'BASE TABLE'
                      AND TABLE_NAME != 'flyway_schema_history'
                    """.trimIndent()
                  ).execute()
              ).concatMap { result -> result.map { row, _ -> row.get("TABLE_NAME", String::class.java)!! } }
              .collectList()
              .flatMap { tableNames ->
                execute(connection, "SET FOREIGN_KEY_CHECKS = 0")
                  .thenMany(
                    Flux.fromIterable(tableNames).concatMap { tableName ->
                      execute(connection, "TRUNCATE TABLE `${tableName.replace("`", "``")}`")
                        .doOnSuccess { log.debug("Truncated table: {}", tableName) }
                    }
                  ).then()
              }
          },
          { restoreAndClose(it) },
          { connection, _ -> restoreAndClose(connection) },
          { restoreAndClose(it) }
        ).awaitSingleOrNull()

      log.info("Database purged successfully")
    } catch (e: Exception) {
      log.error("Error purging database", e)
      throw e
    }
  }

  private fun restoreAndClose(connection: Connection): Mono<Void> =
    Mono.usingWhen(
      Mono.just(connection),
      { execute(it, "SET FOREIGN_KEY_CHECKS = 1") },
      { Mono.from(it.close()) }
    )

  private fun execute(
    connection: Connection,
    sql: String
  ): Mono<Void> =
    Flux
      .defer { connection.createStatement(sql).execute() }
      .concatMap { result -> result.rowsUpdated }
      .then()

  companion object {
    val log: Logger = LoggerFactory.getLogger(R2dbcDbPurger::class.java)
  }
}
