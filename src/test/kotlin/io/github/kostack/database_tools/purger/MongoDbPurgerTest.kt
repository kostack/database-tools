package io.github.kostack.database_tools.purger

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MongoDbPurgerTest {
  @Test
  fun `should purge all collections successfully`() =
    runTest {
      val reactiveMongoOperations = mockk<ReactiveMongoOperations>()
      val mongoDbPurger = MongoDbPurger(reactiveMongoOperations)

      every { reactiveMongoOperations.collectionNames } returns Flux.just("collection1", "collection2")
      every { reactiveMongoOperations.dropCollection(any<String>()) } returns Mono.empty()

      mongoDbPurger.purge()

      verify(exactly = 1) { reactiveMongoOperations.collectionNames }
      verify(exactly = 1) { reactiveMongoOperations.dropCollection("collection1") }
      verify(exactly = 1) { reactiveMongoOperations.dropCollection("collection2") }
      confirmVerified(reactiveMongoOperations)
    }

  @Test
  fun `should handle no collections gracefully`() =
    runTest {
      val reactiveMongoOperations = mockk<ReactiveMongoOperations>()
      val mongoDbPurger = MongoDbPurger(reactiveMongoOperations)

      every { reactiveMongoOperations.getCollectionNames() } returns Flux.empty()

      mongoDbPurger.purge()

      verify(exactly = 1) { reactiveMongoOperations.collectionNames }
      confirmVerified(reactiveMongoOperations)
    }

  @Test
  fun `should handle error during drop collection`() =
    runTest {
      val reactiveMongoOperations = mockk<ReactiveMongoOperations>()
      val mongoDbPurger = MongoDbPurger(reactiveMongoOperations)

      every { reactiveMongoOperations.collectionNames } returns Flux.just("collection1", "collection2")
      every { reactiveMongoOperations.dropCollection(any<String>()) } returns
        Mono.error(RuntimeException("Drop failed"))

      val exception =
        assertFailsWith<RuntimeException> {
          mongoDbPurger.purge()
        }

      assertEquals("Drop failed", exception.message)

      verify(exactly = 1) { reactiveMongoOperations.collectionNames }
      verify(exactly = 1) { reactiveMongoOperations.dropCollection("collection1") }
      confirmVerified(reactiveMongoOperations)
    }
}
