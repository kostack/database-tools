package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.Sortable
import io.github.kostack.database_tools.query_mapper.annotation.SortableDefaults
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageRequestMapperTest {
  @SortableDefaults(defaultSortField = "createdAt", defaultSortDirection = Sort.Direction.ASC, maxPageSize = 20)
  @Sortable(name = "createdAt", field = "created_at")
  @Sortable(name = "name")
  class EntityWithDefaults

  @Sortable(name = "createdAt", field = "created_at")
  class EntityNoDefaults

  @SortableDefaults(defaultSortField = "missing")
  @Sortable(name = "createdAt")
  class EntityWithBrokenDefault

  @SortableDefaults(maxPageSize = 10)
  @Sortable(name = "score")
  class EntityWithMaxPage

  class EntityNoAnnotations

  // --- sort field mapping ---

  @Test
  fun `sort field name is mapped to DB field from Sortable annotation`() {
    val pageable = PageRequest.of(0, 10, Sort.by("createdAt"))
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertNotNull(result.sort.getOrderFor("created_at"))
    assertNull(result.sort.getOrderFor("createdAt"))
  }

  @Test
  fun `sort field without explicit field attribute uses name as DB field`() {
    val pageable = PageRequest.of(0, 10, Sort.by("name"))
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertNotNull(result.sort.getOrderFor("name"))
  }

  @Test
  fun `sort direction is preserved after field mapping`() {
    val pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertEquals(Sort.Direction.DESC, result.sort.getOrderFor("created_at")?.direction)
  }

  // --- invalid sort fields ---

  @Test
  fun `invalid sort field throws IllegalArgumentException`() {
    val pageable = PageRequest.of(0, 10, Sort.by("nonExistent"))
    assertThrows<IllegalArgumentException> {
      PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    }
  }

  @Test
  fun `invalid sort field exception message includes the offending field`() {
    val pageable = PageRequest.of(0, 10, Sort.by("badField"))
    val ex =
      assertThrows<IllegalArgumentException> {
        PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
      }
    assertTrue(ex.message!!.contains("badField"))
  }

  // --- default sort ---

  @Test
  fun `default sort is applied when pageable has no sort`() {
    val pageable = PageRequest.of(0, 10)
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertNotNull(result.sort.getOrderFor("created_at"))
  }

  @Test
  fun `default sort direction from SortableDefaults is respected`() {
    val pageable = PageRequest.of(0, 10)
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertEquals(Sort.Direction.ASC, result.sort.getOrderFor("created_at")?.direction)
  }

  @Test
  fun `explicit sort overrides default sort`() {
    val pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "name"))
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    assertNotNull(result.sort.getOrderFor("name"))
    assertNull(result.sort.getOrderFor("created_at"))
  }

  @Test
  fun `broken default sort field throws IllegalArgumentException`() {
    val pageable = PageRequest.of(0, 10)
    assertThrows<IllegalArgumentException> {
      PageRequestMapper.toPageRequest(pageable, EntityWithBrokenDefault::class)
    }
  }

  // --- no SortableDefaults ---

  @Test
  fun `entity without SortableDefaults has no default sort`() {
    val pageable = PageRequest.of(0, 10)
    val result = PageRequestMapper.toPageRequest(pageable, EntityNoDefaults::class)
    assertFalse(result.sort.isSorted)
  }

  @Test
  fun `entity without SortableDefaults uses fallback max page size of 100`() {
    val pageable = PageRequest.of(0, 200)
    val result = PageRequestMapper.toPageRequest(pageable, EntityNoDefaults::class)
    assertEquals(100, result.pageSize)
  }

  // --- page size coercion ---

  @Test
  fun `page size is capped at maxPageSize`() {
    val pageable = PageRequest.of(0, 50)
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithMaxPage::class)
    assertEquals(10, result.pageSize)
  }

  @Test
  fun `page size within maxPageSize is kept as-is`() {
    val pageable = PageRequest.of(0, 5)
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithMaxPage::class)
    assertEquals(5, result.pageSize)
  }

  @Test
  fun `page size equal to maxPageSize is kept as-is`() {
    val pageable = PageRequest.of(0, 10)
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithMaxPage::class)
    assertEquals(10, result.pageSize)
  }

  // --- page number ---

  @Test
  fun `page number is preserved`() {
    val pageable = PageRequest.of(3, 10, Sort.by("score"))
    val result = PageRequestMapper.toPageRequest(pageable, EntityWithMaxPage::class)
    assertEquals(3, result.pageNumber)
  }

  // --- entity with no annotations ---

  @Test
  fun `entity with no annotations and no sort produces unsorted result`() {
    val pageable = PageRequest.of(0, 10)
    val result = PageRequestMapper.toPageRequest(pageable, EntityNoAnnotations::class)
    assertFalse(result.sort.isSorted)
  }

  @Test
  fun `entity with no annotations rejects any sort field`() {
    val pageable = PageRequest.of(0, 10, Sort.by("anything"))
    assertThrows<IllegalArgumentException> {
      PageRequestMapper.toPageRequest(pageable, EntityNoAnnotations::class)
    }
  }

  // --- automatic use by paginators ---

  @SortableDefaults(maxPageSize = 5)
  class EntityOnlyDefaults

  @Sortable(name = "a", field = "b")
  @Sortable(name = "b", field = "c")
  class EntityWithOverlappingNames

  @Test
  fun `isSortable detects Sortable or SortableDefaults`() {
    assertTrue(PageRequestMapper.isSortable(EntityWithDefaults::class))
    assertTrue(PageRequestMapper.isSortable(EntityNoDefaults::class))
    assertTrue(PageRequestMapper.isSortable(EntityOnlyDefaults::class))
    assertFalse(PageRequestMapper.isSortable(EntityNoAnnotations::class))
  }

  @Test
  fun `mapping twice is a no-op`() {
    val pageable = PageRequest.of(1, 50, Sort.by(Sort.Order.desc("createdAt").ignoreCase()))

    val once = PageRequestMapper.toPageRequest(pageable, EntityWithDefaults::class)
    val twice = PageRequestMapper.toPageRequest(once, EntityWithDefaults::class)

    assertEquals(once, twice)
    assertTrue(twice.sort.getOrderFor("created_at")!!.isIgnoreCase)
  }

  @Test
  fun `already-mapped field name is accepted`() {
    val result =
      PageRequestMapper.toPageRequest(
        PageRequest.of(0, 10, Sort.by("created_at")),
        EntityWithDefaults::class
      )
    assertNotNull(result.sort.getOrderFor("created_at"))
  }

  @Test
  fun `api name wins over another entry's field`() {
    val result = PageRequestMapper.toPageRequest(PageRequest.of(0, 10, Sort.by("b")), EntityWithOverlappingNames::class)
    assertNotNull(result.sort.getOrderFor("c"))
    assertNull(result.sort.getOrderFor("b"))
  }

  @Test
  fun `fields that are neither api names nor mapped fields are still rejected`() {
    assertThrows<IllegalArgumentException> {
      PageRequestMapper.toPageRequest(PageRequest.of(0, 10, Sort.by("password")), EntityWithDefaults::class)
    }
  }
}
