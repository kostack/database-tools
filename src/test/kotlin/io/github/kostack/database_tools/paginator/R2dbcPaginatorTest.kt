package io.github.kostack.database_tools.paginator

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.core.ReactiveSelectOperation
import org.springframework.data.relational.core.query.Query
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class R2dbcPaginatorTest {
  private val entityTemplate = mockk<R2dbcEntityTemplate>()
  private val selectSpec = mockk<ReactiveSelectOperation.ReactiveSelect<String>>()
  private val matchingSpec = mockk<ReactiveSelectOperation.TerminatingSelect<String>>()

  private val paginator = R2dbcPaginator(entityTemplate)

  @Test
  fun `should return empty pagination when no records exist`() =
    runTest {
      val query = Query.empty()
      val pageable: Pageable = PageRequest.of(0, 10)
      val targetClass = String::class

      every { entityTemplate.count(any(), eq(targetClass.java)) } returns Mono.just(0L)
      every { entityTemplate.select(targetClass.java) } returns selectSpec
      every { selectSpec.matching(any()) } returns matchingSpec
      every { matchingSpec.all() } returns Flux.empty()

      val result = paginator.toPagination(query, pageable, targetClass)

      assertTrue(result.content.isEmpty())
      assertEquals(10, result.limit)
      assertEquals(0, result.offset)
      assertEquals(0L, result.total)
    }

  @Test
  fun `should return pagination with records when records exist`() =
    runTest {
      val query = Query.empty()
      val pageable: Pageable = PageRequest.of(0, 10)
      val targetClass = String::class
      val mockData = listOf("A", "B", "C")

      every { entityTemplate.count(any(), eq(targetClass.java)) } returns Mono.just(3L)
      every { entityTemplate.select(targetClass.java) } returns selectSpec
      every { selectSpec.matching(any()) } returns matchingSpec
      every { matchingSpec.all() } returns Flux.fromIterable(mockData)

      val result = paginator.toPagination(query, pageable, targetClass)

      assertEquals(mockData, result.content)
      assertEquals(10, result.limit)
      assertEquals(0, result.offset)
      assertEquals(3L, result.total)
    }

  @Test
  fun `should handle pagination with custom page offsets`() =
    runTest {
      val query = Query.empty()
      val pageable: Pageable = PageRequest.of(1, 5)
      val targetClass = String::class
      val mockData = listOf("F", "G", "H", "I", "J")

      every { entityTemplate.count(any(), eq(targetClass.java)) } returns Mono.just(15L)
      every { entityTemplate.select(targetClass.java) } returns selectSpec
      every { selectSpec.matching(any()) } returns matchingSpec
      every { matchingSpec.all() } returns Flux.fromIterable(mockData)

      val result = paginator.toPagination(query, pageable, targetClass)

      assertEquals(mockData, result.content)
      assertEquals(5, result.limit)
      assertEquals(5, result.offset)
      assertEquals(15L, result.total)
    }

  @Test
  fun `should return pagination with empty page when out of bounds`() =
    runTest {
      val query = Query.empty()
      val pageable: Pageable = PageRequest.of(10, 5)
      val targetClass = String::class

      every { entityTemplate.count(any(), eq(targetClass.java)) } returns Mono.just(3L)
      every { entityTemplate.select(targetClass.java) } returns selectSpec
      every { selectSpec.matching(any()) } returns matchingSpec
      every { matchingSpec.all() } returns Flux.empty()

      val result = paginator.toPagination(query, pageable, targetClass)

      assertTrue(result.content.isEmpty())
      assertEquals(5, result.limit)
      assertEquals(50, result.offset)
      assertEquals(3L, result.total)
    }
}
