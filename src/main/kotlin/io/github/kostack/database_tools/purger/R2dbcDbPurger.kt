package io.github.kostack.database_tools.purger

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

@Component
class R2dbcDbPurger(
  private val databaseClient: DatabaseClient
) {
  suspend fun purge() {
    log.warn("Attempting to drop all tables...")

    try {
      databaseClient
        .sql("SET FOREIGN_KEY_CHECKS = 0")
        .then()
        .awaitFirstOrNull()

      val tableNames =
        databaseClient
          .sql(
            """
            SELECT TABLE_NAME
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_TYPE = 'BASE TABLE'
              AND TABLE_NAME != 'flyway_schema_history'
            """.trimIndent()
          ).map { row, _ -> row.get("TABLE_NAME", String::class.java)!! }
          .all()
          .collectList()
          .awaitSingle()
          .filterNotNull()

      for (tableName in tableNames) {
        databaseClient
          .sql("TRUNCATE TABLE $tableName")
          .then()
          .awaitFirstOrNull()

        log.debug("Truncated table: {}", tableName)
      }

      log.info("Database purged successfully")
    } catch (e: Exception) {
      log.error("Error purging database", e)
      throw e
    } finally {
      try {
        databaseClient
          .sql("SET FOREIGN_KEY_CHECKS = 1")
          .then()
          .awaitFirstOrNull()
      } catch (restoreException: Exception) {
        log.error("Failed to restore FOREIGN_KEY_CHECKS", restoreException)
      }
    }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(R2dbcDbPurger::class.java)
  }
}
