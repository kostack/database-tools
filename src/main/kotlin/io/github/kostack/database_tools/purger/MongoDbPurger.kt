package io.github.kostack.database_tools.purger

import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import org.springframework.stereotype.Component

@Component
class MongoDbPurger(
  private val reactiveMongoOperations: ReactiveMongoOperations
) {
  suspend fun purge() {
    log.warn("Attempting to drop all collections reactively...")

    reactiveMongoOperations.collectionNames
      .asFlow()
      .collect { collection ->
        reactiveMongoOperations.dropCollection(collection).awaitSingleOrNull()
      }
  }

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
