# Database Tools

A Kotlin/Spring library that provides reactive pagination, flexible query mapping, and database purging utilities for MongoDB and R2DBC (SQL) databases. Built on top of Spring Data and Kotlin coroutines, it removes boilerplate from common data-access patterns — paginated queries, filter-to-query conversion, sort-field allowlisting, and test-environment teardown — so you can focus on business logic instead of infrastructure plumbing.

## Table of Contents

- [Installation](#installation)
- [Pagination](#pagination)
  - [Pagination Response Model](#pagination-response-model)
  - [MongoDbPaginator](#mongodbpaginator)
  - [R2dbcPaginator](#r2dbcpaginator)
- [Query Mapper](#query-mapper)
  - [@QueryField](#queryfield)
  - [QueryOperator](#queryoperator)
  - [MongoQueryMapper](#mongoquarymapper)
  - [SqlQueryMapper](#sqlquerymapper)
  - [Query Mapper Extensions](#query-mapper-extensions)
- [Page Request Mapping](#page-request-mapping)
  - [@Sortable](#sortable)
  - [@SortableDefaults](#sortabledefaults)
  - [PageRequestMapper](#pagerequestmapper)
- [Purger](#purger)
  - [MongoDbPurger](#mongodbpurger)
  - [R2dbcDbPurger](#r2dbcdbpurger)
- [License](#license)

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.database-tools:database-tools:<version>")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.database-tools:database-tools:<version>'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.database-tools</groupId>
    <artifactId>database-tools</artifactId>
    <version>VERSION</version>
</dependency>
```

Replace `<version>` / `VERSION` with the latest release tag from [GitHub Releases](https://github.com/kostack/database-tools/releases).

The library is published to [Maven Central](https://central.sonatype.com/artifact/io.github.database-tools/database-tools), so no additional repository configuration is needed.

---

## Pagination

### Pagination Response Model

`Pagination<T>` is the standard response wrapper for paginated API endpoints.

```kotlin
data class Pagination<T>(
    val content: List<T>,   // Items on the current page
    val limit: Int,         // Max items per page
    val offset: Long,       // Starting index of this page
    val total: Long         // Total number of items across all pages
)
```

**Example JSON response:**

```json
{
  "content": [
    { "id": 1, "name": "Alice" },
    { "id": 2, "name": "Bob" }
  ],
  "limit": 10,
  "offset": 0,
  "total": 42
}
```

---

### MongoDbPaginator

`MongoDbPaginator` is a Spring `@Component` that fetches a page of results and the total count concurrently from MongoDB.

**Inject in your service:**

```kotlin
@Service
class UserService(private val paginator: MongoDbPaginator) {

    suspend fun listUsers(pageable: Pageable): Pagination<User> {
        val query = Query(Criteria.where("active").`is`(true))
        return paginator.toPagination(query, pageable, User::class)
    }
}
```

**Use in a controller:**

```kotlin
@RestController
@RequestMapping("/users")
class UserController(private val userService: UserService) {

    @GetMapping
    suspend fun getUsers(pageable: Pageable): Pagination<User> =
        userService.listUsers(pageable)
}
```

**Request:**
```
GET /users?page=0&size=10
```

**Response:**
```json
{
  "content": [...],
  "limit": 10,
  "offset": 0,
  "total": 57
}
```

The items and count queries run in parallel via `coroutineScope` — the total is never blocked by the item fetch.

---

### R2dbcPaginator

`R2dbcPaginator` is a Spring `@Component` that does the same for R2DBC (reactive SQL databases such as MySQL, PostgreSQL).

**Inject in your service:**

```kotlin
@Service
class OrderService(private val paginator: R2dbcPaginator) {

    suspend fun listOrders(pageable: Pageable, status: String): Pagination<Order> {
        val query = Query.query(Criteria.where("status").`is`(status))
        return paginator.toPagination(query, pageable, Order::class)
    }
}
```

**Use in a controller:**

```kotlin
@RestController
@RequestMapping("/orders")
class OrderController(private val orderService: OrderService) {

    @GetMapping
    suspend fun getOrders(
        pageable: Pageable,
        @RequestParam status: String
    ): Pagination<Order> = orderService.listOrders(pageable, status)
}
```

**Request:**
```
GET /orders?status=OPEN&page=1&size=5
```

**Response:**
```json
{
  "content": [...],
  "limit": 5,
  "offset": 5,
  "total": 23
}
```

The count query uses only the filter criteria (no `LIMIT`/`OFFSET`), ensuring an accurate total regardless of the requested page.

---

## Query Mapper

`query_mapper` converts annotated filter data classes into database query objects for MongoDB and SQL (R2DBC). It eliminates manual null-checks — only non-null, non-blank fields contribute to the query.

### @QueryField

Place `@QueryField` on properties of a filter data class to declare how each field maps to a DB criterion.

```kotlin
@QueryField(
    name        = "",               // DB field / column name. Defaults to the Kotlin property name.
    operator    = QueryOperator.EQ,
    ignoreEmpty = true              // Skip null and blank String values (default: true).
)
```

**Custom column name:**

```kotlin
data class UserFilter(
    @QueryField(name = "user_id") val userId: String? = null
)
// → queries the "user_id" column, not "userId"
```

**`ignoreEmpty = false`** is required for value-independent operators like `EXISTS` and `IS_NULL`, since the property value is irrelevant:

```kotlin
data class ActiveFilter(
    @QueryField(operator = QueryOperator.IS_NOT_NULL, ignoreEmpty = false) val deletedAt: String? = null
)
```

---

### QueryOperator

| Operator | SQL | MongoDB | Value type |
|---|---|---|---|
| `EQ` | `= value` | `{ field: value }` | Any |
| `NE` | `!= value` | `{ $ne: value }` | Any |
| `CONTAINS` | `LIKE '%value%'` | regex `value` | String |
| `CONTAINS_IGNORE_CASE` | `LIKE '%value%'` + ignoreCase | regex `value` + `i` | String |
| `STARTS_WITH` | `LIKE 'value%'` | regex `^value` | String |
| `STARTS_WITH_IGNORE_CASE` | `LIKE 'value%'` + ignoreCase | regex `^value` + `i` | String |
| `ENDS_WITH` | `LIKE '%value'` | regex `value$` | String |
| `ENDS_WITH_IGNORE_CASE` | `LIKE '%value'` + ignoreCase | regex `value$` + `i` | String |
| `REGEX` | ❌ not supported | `{ $regex: value }` | String |
| `REGEX_IGNORE_CASE` | ❌ not supported | `{ $regex: value, $options: 'i' }` | String |
| `GT` | `> value` | `{ $gt: value }` | Any |
| `GTE` | `>= value` | `{ $gte: value }` | Any |
| `LT` | `< value` | `{ $lt: value }` | Any |
| `LTE` | `<= value` | `{ $lte: value }` | Any |
| `IN` | `IN (values)` | `{ $in: [values] }` | Collection |
| `NOT_IN` | `NOT IN (values)` | `{ $nin: [values] }` | Collection |
| `EXISTS` | `IS NOT NULL` | `{ $exists: true }` | Any |
| `NOT_EXISTS` | `IS NULL` | `{ $exists: false }` | Any |
| `IS_NULL` | `IS NULL` | `{ field: null }` | Any |
| `IS_NOT_NULL` | `IS NOT NULL` | `{ $ne: null }` | Any |

---

### MongoQueryMapper

`MongoQueryMapper` is a stateless singleton. Call `toQuery(filter)` to get a `org.springframework.data.mongodb.core.query.Query`. Returns an empty `Query` when no fields are active.

```kotlin
data class ProductFilter(
    @QueryField val category: String? = null,
    @QueryField(operator = QueryOperator.GTE) val price: Double? = null,
    @QueryField(operator = QueryOperator.CONTAINS_IGNORE_CASE) val name: String? = null,
    @QueryField(operator = QueryOperator.IN) val tags: List<String>? = null
)

val query = MongoQueryMapper.toQuery(
    ProductFilter(category = "electronics", price = 49.99, name = "phone")
)
// { $and: [{ category: "electronics" }, { price: { $gte: 49.99 } }, { name: { $regex: "phone", $options: "i" } }] }

mongoTemplate.find(query, Product::class.java)
```

Use `toCriteriaList(filter)` to get the raw `List<Criteria>` when you need to compose with your own criteria.

---

### SqlQueryMapper

`SqlQueryMapper` is a stateless singleton. Call `toQuery(filter)` to get a `org.springframework.data.relational.core.query.Query`. Returns `Query.empty()` when no fields are active.

```kotlin
data class OrderFilter(
    @QueryField val customerId: String? = null,
    @QueryField(operator = QueryOperator.IN) val statuses: List<String>? = null,
    @QueryField(operator = QueryOperator.GTE) val totalAmount: BigDecimal? = null,
)

val query = SqlQueryMapper.toQuery(
    OrderFilter(customerId = "cust-42", statuses = listOf("PAID", "SHIPPED"))
)
// WHERE customer_id = 'cust-42' AND statuses IN ('PAID', 'SHIPPED')

r2dbcEntityTemplate.select(Order::class.java).matching(query).all()
```

SQL wildcards in user input are always escaped — `%`, `_`, and `\` in the value are quoted automatically:

```kotlin
// User types "100%" → LIKE '%100\%%'  (not an open wildcard)
@QueryField(operator = QueryOperator.CONTAINS) val title: String? = null
```

`REGEX` and `REGEX_IGNORE_CASE` throw `UnsupportedOperationException` on SQL — use `CONTAINS`, `STARTS_WITH`, or `ENDS_WITH` instead.

---

### Query Mapper Extensions

```kotlin
import io.github.kostack.database_tools.query_mapper.extension.toMappedQuery
import io.github.kostack.database_tools.query_mapper.extension.toPageRequest
import io.github.kostack.database_tools.query_mapper.extension.map

// Build a query directly from a filter object
val mongoQuery = Query().toMappedQuery(filter)       // MongoDB
val sqlQuery   = Query.empty().toMappedQuery(filter) // SQL

// Validate and sanitise a Pageable (delegates to PageRequestMapper)
val pageRequest = pageable.toPageRequest(UserEntity::class)

// Transform the content of a Pagination without rewrapping
val userDtos: Pagination<UserDto> = userPagination.map { UserDto(it) }
```

---

## Page Request Mapping

`PageRequestMapper` validates and sanitises a Spring `Pageable` before it reaches the database. It enforces an allowed sort-field allowlist and caps the page size, preventing clients from sorting on arbitrary columns or requesting unbounded result sets.

### @Sortable

Declare which fields the client is allowed to sort on. Apply it to the class that will be passed to `PageRequestMapper` (typically a filter/request DTO or your domain entity).

```kotlin
@Sortable(name = "createdAt")
@Sortable(name = "name", field = "fullName")   // "name" in the API maps to "fullName" in the DB
data class UserFilter(
    val search: String? = null
)
```

| Parameter | Description |
|-----------|-------------|
| `name`    | The sort field name as exposed in the API (`sort=name,asc`). |
| `field`   | The actual database column/document field. Defaults to `name` when blank. |

### @SortableDefaults

Configure default sorting and page-size limits at the class level.

```kotlin
@SortableDefaults(
    defaultSortField = "createdAt",
    defaultSortDirection = Sort.Direction.DESC,
    maxPageSize = 50
)
@Sortable(name = "createdAt")
@Sortable(name = "name")
data class UserFilter(val search: String? = null)
```

| Parameter              | Default | Description |
|------------------------|---------|-------------|
| `defaultSortField`     | `""`    | Applied when the client sends no `sort` parameter. Must be one of the declared `@Sortable` names. |
| `defaultSortDirection` | `DESC`  | Direction used with the default sort field. |
| `maxPageSize`          | `25`    | Maximum allowed value for `size`. Requests exceeding this are clamped. |

### PageRequestMapper

`PageRequestMapper` is an object (stateless utility) — no injection needed. Call `toPageRequest` before passing the `Pageable` to a paginator.

```kotlin
val safePageable = PageRequestMapper.toPageRequest(pageable, UserFilter::class)
val result = paginator.toPagination(query, safePageable, User::class)
```

**Full controller example:**

```kotlin
@SortableDefaults(defaultSortField = "createdAt", maxPageSize = 50)
@Sortable(name = "createdAt")
@Sortable(name = "name", field = "fullName")
data class UserFilter(val search: String? = null)

@RestController
@RequestMapping("/users")
class UserController(private val paginator: MongoDbPaginator) {

    @GetMapping
    suspend fun getUsers(
        pageable: Pageable,
        filter: UserFilter
    ): Pagination<User> {
        val safePageable = PageRequestMapper.toPageRequest(pageable, UserFilter::class)

        val query = Query()
        filter.search?.let { query.addCriteria(Criteria.where("fullName").regex(it, "i")) }

        return paginator.toPagination(query, safePageable, User::class)
    }
}
```

**Sorting by an unsupported field throws `IllegalArgumentException`:**

```
GET /users?sort=password,asc
→ IllegalArgumentException: Unsupported sort fields: [password]. Allowed fields: [createdAt, name]
```

**Page size is clamped to `maxPageSize`:**

```
GET /users?size=1000
→ effective size = 50 (clamped to maxPageSize)
```

---

## Purger

The purger components are intended for **test environments only**. They wipe all data from the database and are useful for resetting state between integration tests.

> **Warning:** Never inject or call a purger in production code. Both components log a `WARN` message on invocation as a safety signal.

### MongoDbPurger

`MongoDbPurger` is a Spring `@Component` that drops every collection in the connected MongoDB database.

```kotlin
@Component
class MongoDbPurger(private val reactiveMongoOperations: ReactiveMongoOperations)
```

It iterates all collection names reactively and drops each one sequentially. If any `dropCollection` call fails, the exception propagates immediately and remaining collections are left intact.

**Usage in a test:**

```kotlin
@SpringBootTest
class UserRepositoryTest(
    private val purger: MongoDbPurger,
    private val userRepository: UserRepository
) {

    @BeforeEach
    fun setUp() = runTest {
        purger.purge()
    }

    @Test
    fun `should save and retrieve user`() = runTest {
        userRepository.save(User(name = "Alice"))
        assertEquals(1, userRepository.count())
    }
}
```

---

### R2dbcDbPurger

`R2dbcDbPurger` is a Spring `@Component` that truncates all user-defined tables in the connected MySQL database.

```kotlin
@Component
class R2dbcDbPurger(private val databaseClient: DatabaseClient)
```

It uses `TRUNCATE TABLE` rather than `DROP TABLE`, so the schema (columns, indexes, constraints) is preserved — only the rows are deleted. The sequence is:

1. `SET FOREIGN_KEY_CHECKS = 0` — disable FK constraints so tables can be truncated in any order
2. Query `INFORMATION_SCHEMA.TABLES` for all `BASE TABLE` entries in the current schema, excluding `flyway_schema_history`
3. `TRUNCATE TABLE <name>` for each discovered table
4. `SET FOREIGN_KEY_CHECKS = 1` — re-enable FK constraints (runs in `finally`, so it always executes even if truncation fails)

**Usage in a test:**

```kotlin
@SpringBootTest
class OrderRepositoryTest(
    private val purger: R2dbcDbPurger,
    private val orderRepository: OrderRepository
) {

    @BeforeEach
    fun setUp() = runTest {
        purger.purge()
    }

    @Test
    fun `should persist order`() = runTest {
        orderRepository.save(Order(status = "OPEN"))
        assertEquals(1, orderRepository.count())
    }
}
```

**Error handling:**

- If a `TRUNCATE` fails, the exception is logged and re-thrown.
- `FOREIGN_KEY_CHECKS = 1` is always restored in `finally`, even when an exception occurs, to avoid leaving the session in a broken state.
- `flyway_schema_history` is excluded from truncation so Flyway migration state is never wiped.

---

## License

MIT License

Copyright (c) 2024 Nikolay Georgiev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
