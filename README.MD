# Database Tools

A Kotlin/Spring library that provides reactive pagination, flexible query mapping, and database purging utilities for MongoDB and R2DBC (SQL) databases. Built on top of Spring Data and Kotlin coroutines, it removes boilerplate from common data-access patterns — paginated queries, filter-to-query conversion (including multi-table joins and aggregates on R2DBC), sort-field allowlisting, after-commit actions, JSON column conversion, and test-environment teardown — so you can focus on business logic instead of infrastructure plumbing.

## Table of Contents

- [Installation](#installation)
- [Pagination](#pagination)
  - [Pagination Response Model](#pagination-response-model)
  - [MongoDbPaginator](#mongodbpaginator)
  - [R2dbcPaginator](#r2dbcpaginator)
- [Query Mapper](#query-mapper)
  - [@QueryField](#queryfield)
  - [QueryOperator](#queryoperator)
  - [MongoQueryMapper](#mongoquerymapper)
  - [SqlQueryMapper](#sqlquerymapper)
  - [Query Mapper Extensions](#query-mapper-extensions)
  - [Join Queries (R2DBC)](#join-queries-r2dbc)
    - [Aggregates](#aggregates)
- [Page Request Mapping](#page-request-mapping)
  - [@Sortable](#sortable)
  - [@SortableDefaults](#sortabledefaults)
  - [PageRequestMapper](#pagerequestmapper)
- [R2DBC Support](#r2dbc-support)
  - [TransactionalManager](#transactionalmanager)
  - [JSON Converters](#json-converters)
- [Auto-configuration](#auto-configuration)
- [Purger](#purger)
  - [MongoDbPurger](#mongodbpurger)
  - [R2dbcDbPurger](#r2dbcdbpurger)
- [Development](#development)
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

`MongoDbPaginator` is auto-configured as a Spring bean (when a `ReactiveMongoTemplate` bean exists) and fetches a page of results and the total count concurrently from MongoDB.

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

**Sorting.** Pass the filter class that carries `@Sortable` / `@SortableDefaults` as `sortableSource` to validate and map the `Pageable` (allowlist, default sort, page-size cap) — see [Sort rules in paginators](#sort-rules-in-paginators). Without it the `Pageable` is used as is.

`ignoreCase()` orders (`sort=name,asc,ignorecase`) are **not supported** on MongoDB: Spring Data MongoDB rejects them with `IllegalArgumentException`. Use a case-insensitive collation on the collection or query instead.

---

### R2dbcPaginator

`R2dbcPaginator` is auto-configured (when an `R2dbcEntityTemplate` bean exists) and does the same for R2DBC (reactive SQL databases such as MySQL, PostgreSQL).

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

**Sorting.** As with `MongoDbPaginator`, pass the annotated filter class as `sortableSource` ([details](#sort-rules-in-paginators)); without it the `Pageable` is used as is.

**Case-insensitive sorting.** `ignoreCase()` orders (e.g. `sort=name,asc,ignorecase` or via `PageRequestMapper`) are sorted by `LOWER(column)`. Spring's `Query` rejects such orders, so these requests run through [`R2dbcJoinQuery`](#join-queries-r2dbc) with a single-table spec — same criteria mapping and entity conversion. In that case a column projection (`Query.columns(...)`) is not supported and `@Embedded` properties on the entity are rejected. Requests without `ignoreCase` use `R2dbcEntityTemplate` exactly as before.

---

## Query Mapper

`query_mapper` converts annotated filter data classes into database query objects for MongoDB and SQL (R2DBC). It eliminates manual null-checks — only non-null, non-blank fields contribute to the query.

### @QueryField

Place `@QueryField` on properties of a filter data class to declare how each field maps to a DB criterion.

```kotlin
@QueryField(
    name        = "",               // DB field / column name. Defaults to the Kotlin property name.
    operator    = QueryOperator.EQ,
    ignoreEmpty = true,             // Skip null and blank String values (default: true).
    table       = "",               // Joined table alias; see Join Queries. Empty = root table.
    aggregate   = Aggregate.NONE    // Compare COUNT/SUM/AVG/MIN/MAX of the column; see Aggregates.
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

**Merging into an existing query.** `toQuery(filter, base, required)` returns a copy of `base` (sort, paging and projection kept; `base` is not mutated) with `$and: [required, mapped…]` added:

```kotlin
val query = MongoQueryMapper.toQuery(
    filter,
    base     = Query().with(pageable),
    required = Criteria.where("tenantId").`is`(tenantId)
)
```

Top-level keys of `base` are implicitly ANDed with it. A `base` that already has a top-level `$and` cannot be extended this way (Spring throws). Fields with `@QueryField(table = …)` or `aggregate = …` are rejected — they only apply to SQL join queries.

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
// WHERE customer_id = ? AND (statuses IN (?, ?))   -- values bound: 'cust-42', 'PAID', 'SHIPPED'

r2dbcEntityTemplate.select(Order::class.java).matching(query).all()
```

SQL wildcards in user input are always escaped — `%`, `_`, and `\` in the value are quoted automatically:

```kotlin
// User types "100%" → LIKE '%100\%%'  (not an open wildcard)
@QueryField(operator = QueryOperator.CONTAINS) val title: String? = null
```

`REGEX` and `REGEX_IGNORE_CASE` throw `UnsupportedOperationException` on SQL — use `CONTAINS`, `STARTS_WITH`, or `ENDS_WITH` instead.

**Merging into an existing query.** `toQuery(filter, base, required)` keeps `base`'s sort, limit, offset and columns and combines the criteria as `required AND (base criteria) AND mapped…`. Each part is parenthesised, so an `OR` inside `base` or `required` keeps its meaning:

```kotlin
val query = SqlQueryMapper.toQuery(
    filter,
    base     = Query.empty().with(pageable),
    required = Criteria.where("tenant_id").`is`(tenantId)
)
// WHERE tenant_id = ? AND (customer_id = ?) ... ORDER BY ... LIMIT ...

SqlQueryMapper.and(query, Criteria.where("deleted").isFalse) // AND one criteria into a query
```

Single-table mappers (SQL and Mongo) reject fields with `@QueryField(table = ...)`; use [Join Queries](#join-queries-r2dbc).

---

### Query Mapper Extensions

```kotlin
import io.github.kostack.database_tools.query_mapper.extension.toMappedQuery
import io.github.kostack.database_tools.query_mapper.extension.toPageRequest
import io.github.kostack.database_tools.query_mapper.extension.map

// Map a filter into a query; the receiver's sort / paging / columns are kept
val mongoQuery = Query().limit(20).toMappedQuery(filter)              // MongoDB (receiver is copied, not mutated)
val sqlQuery   = Query.empty().with(pageable).toMappedQuery(filter)   // SQL

// Mandatory criteria go first
val scoped = Query.empty().toMappedQuery(filter, required = Criteria.where("tenant_id").`is`(tenantId))

// AND extra criteria into an existing SQL query (sort / paging kept)
val active = sqlQuery.and(Criteria.where("deleted").isFalse)

// Validate and sanitise a Pageable (delegates to PageRequestMapper)
val pageRequest = pageable.toPageRequest(UserEntity::class)

// Transform the content of a Pagination without rewrapping
val userDtos: Pagination<UserDto> = userPagination.map { UserDto(it) }
```

---

### Join Queries (R2DBC)

Spring Data's `Query` / `Criteria` are single-table. `R2dbcJoinQuery` (auto-configured when an `R2dbcEntityTemplate` bean exists) runs the same `@QueryField` filters over a root table plus joined tables and maps each row into a DTO.

**1. Describe the joins** with `JoinSpec`. In each `on` pair the left side is `alias.column` of an already declared table (bare name = root), the right side a column of the joined table. Entity property names are mapped to their columns; anything else is used as a raw column name.

```kotlin
val spec = JoinSpec.from(User::class, "u")
    .join(Order::class, "o", "u.id" to "userId")                               // INNER JOIN (default)
    .join(Address::class, "a", "u.id" to "userId", mode = JoinMode.LEFT)       // LEFT OUTER JOIN
    .join(Item::class, "i", "o.id" to "orderId", mode = JoinMode.EXISTS)       // filter only
```

| Mode | SQL | Columns selectable | Notes |
|---|---|---|---|
| `INNER` | `JOIN … ON …` | yes | one result row per joined row |
| `LEFT` | `LEFT OUTER JOIN … ON …` | yes | missing matches give `NULL`s |
| `EXISTS` | `u.id IN (SELECT i.order_id FROM … WHERE <criteria>)` | no | semi-join: never duplicates rows; skipped when no criteria target it |
| `AGGREGATE` | correlated subqueries, see [Aggregates](#aggregates) | aggregates only | never joined as rows |

**Extra ON conditions.** `onCriteria` adds value conditions on the joined table's own columns:

```kotlin
JoinSpec.from(User::class, "u")
    .join(Order::class, "o", "u.id" to "userId", mode = JoinMode.LEFT, onCriteria = Criteria.where("status").`is`("PAID"))
// LEFT OUTER JOIN orders o ON u.id = o.user_id AND (o.status = ?)
```

| Mode | Where `onCriteria` goes |
|---|---|
| `INNER` / `LEFT` | into `ON`. For `LEFT` this keeps users without a paid order (with `NULL` columns); the same criteria via a filter field or `required["o"]` land in `WHERE` and drop them. |
| `EXISTS` | into the subquery; it does not activate the EXISTS filter on its own (filter fields / `required` still do) |
| `AGGREGATE` | into every aggregate subquery of that alias, always |

Column names are resolved against the joined entity; comparing against another table's column is not supported (use the `on` column pairs for that).

**2. Tag filter fields** with the table alias:

```kotlin
data class UserOrderFilter(
    @QueryField(operator = QueryOperator.CONTAINS) val name: String? = null,      // root (u)
    @QueryField(table = "o") val status: String? = null,                          // o.status
    @QueryField(table = "o", name = "total", operator = QueryOperator.GTE) val minTotal: Int? = null,
    @QueryField(table = "i") val sku: String? = null                              // EXISTS on items
)
```

**3. Declare the result DTO** with `@SelectColumn`. Unannotated properties come from the root table; each value is selected `AS` the DTO property's column name.

```kotlin
data class UserOrderRow(
    val id: Long,
    val name: String,
    @SelectColumn(table = "o", name = "id") val orderId: Long,
    @SelectColumn(table = "o") val status: String,
    @SelectColumn(table = "a", name = "city") val city: String?   // LEFT → nullable
)
```

**4. Run it:**

```kotlin
val rows: Flow<UserOrderRow> = joinQuery.select(spec, UserOrderRow::class, filter, Query.empty().sort(Sort.by("orderId")))
val total: Long = joinQuery.count(spec, filter)
val page: Pagination<UserOrderRow> = joinQuery.toPagination(spec, UserOrderRow::class, pageable, filter)

// Mandatory criteria per alias are placed first for that table:
joinQuery.select(spec, UserOrderRow::class, filter, required = mapOf("u" to Criteria.where("tenant_id").`is`(tenantId)))
```

- Criteria per alias: `required[alias] AND (query criteria — root only) AND filter fields`; each part is parenthesised.
- Sort properties are DTO property names (`orderId`) or `alias.column` (`o.total`); anything that isn't a plain identifier is rejected.
- `ignoreCase()` orders sort by `LOWER(column)` (ignored for aggregate properties). Spring's `Query.sort` rejects them, so pass such orders via the `sort` parameter of `select`, or through the `Pageable` of `toPagination`.
- Everything is bound as parameters. Bind markers are generated in SQL text order, as MySQL's positional `?` requires.

**Allowlisting sort fields.** Put `@Sortable` / `@SortableDefaults` on the filter class; `toPagination` picks them up from the `filter` you pass. `field` may be a DTO property or `alias.column`:

```kotlin
@SortableDefaults(defaultSortField = "orderId", maxPageSize = 50)
@Sortable(name = "orderId")
@Sortable(name = "name")
@Sortable(name = "total", field = "o.total")
data class UserOrderFilter(
    @QueryField(table = "o") val status: String? = null
)

joinQuery.toPagination(spec, UserOrderRow::class, pageable, filter)   // GET ?status=PAID&sort=total,desc&size=500
```

The `Pageable` then goes through `PageRequestMapper` (see [Sort rules in paginators](#sort-rules-in-paginators)): unknown sort fields throw `IllegalArgumentException`, the default sort applies when none is requested, and the page size is capped (the returned `Pagination.limit` reflects the cap). Pass `sortableSource = OtherClass::class` to take the rules from another class, or `sortableSource = null` to opt out — then any DTO property or `alias.column` may be sorted on. With no filter, or an unannotated one, the `Pageable` is used as is.

#### Aggregates

`COUNT`, `SUM`, `AVG`, `MIN` and `MAX` over a `JoinMode.AGGREGATE` table are computed **per root row** as correlated scalar subqueries, so results stay one row per root entity and `count` / paging / sorting work unchanged.

```kotlin
val spec = JoinSpec.from(User::class, "u")
    .join(Order::class, "s", "u.id" to "userId", mode = JoinMode.AGGREGATE)

data class UserStatsRow(
    val id: Long,
    val name: String,
    @SelectAggregate(table = "s", function = Aggregate.COUNT) val orderCount: Long,              // COUNT(*)
    @SelectAggregate(table = "s", function = Aggregate.SUM, name = "total") val totalSum: Long?,
    @SelectAggregate(table = "s", function = Aggregate.AVG, name = "total") val avgTotal: Double?
)

data class UserStatsFilter(
    @QueryField(table = "s") val status: String? = null,                                         // scopes the aggregation
    @QueryField(table = "s", aggregate = Aggregate.COUNT, operator = QueryOperator.GTE) val minOrders: Long? = null,
    @QueryField(table = "s", aggregate = Aggregate.AVG, name = "total", operator = QueryOperator.GT) val avgAbove: Int? = null
)

joinQuery.toPagination(spec, UserStatsRow::class, PageRequest.of(0, 20, Sort.by(DESC, "totalSum")), filter)
```

```sql
SELECT u.id AS id, u.name AS name,
       (SELECT COUNT(*) FROM orders s WHERE (s.status = ?) AND s.user_id = u.id) AS order_count, ...
FROM users u
WHERE ((SELECT COUNT(*) FROM orders s WHERE (s.status = ?) AND s.user_id = u.id) >= ?)
ORDER BY total_sum DESC
```

- **Plain criteria on an `AGGREGATE` alias** (filter fields or `required["s"]`) restrict the rows being aggregated, not the root rows. To filter root rows by related rows, add a separate `EXISTS` alias for the same table.
- Aggregate filter operators: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`/`NOT_EXISTS`, `IS_NOT_NULL`/`EXISTS`. `LIKE`-style operators throw `UnsupportedOperationException`.
- Over no rows `COUNT` is `0`; `SUM`, `AVG`, `MIN`, `MAX` are `NULL`, so declare those DTO properties nullable.
- Sorting by an aggregate DTO property orders by its select alias, so the subquery is not repeated.
- Each aggregate used in both the select list and a filter runs as its own subquery; index the correlation column (e.g. `orders.user_id`). For report-style grouping across many dimensions, prefer a view or a hand-written query.

## Page Request Mapping

`PageRequestMapper` validates and sanitises a Spring `Pageable` before it reaches the database. It enforces an allowed sort-field allowlist and caps the page size, preventing clients from sorting on arbitrary columns or requesting unbounded result sets.

### @Sortable

Declare which fields the client is allowed to sort on. Apply it to the **filter class** of the endpoint (not to the document/table class) and pass that class to `PageRequestMapper` or as a paginator's `sortableSource`.

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

A class with `@Sortable` but no `@SortableDefaults` has no default sort and a page-size cap of **100**.

### PageRequestMapper

`PageRequestMapper` is an object (stateless utility) — no injection needed. Call `toPageRequest` to validate a `Pageable` against a class's `@Sortable` / `@SortableDefaults`:

```kotlin
val safePageable = PageRequestMapper.toPageRequest(pageable, UserFilter::class)   // or pageable.toPageRequest(UserFilter::class)
val result = paginator.toPagination(query, safePageable, User::class)
```

#### Sort rules in paginators

`@Sortable` / `@SortableDefaults` belong on the **filter class**, never on the document/table class. The paginators apply them through a `sortableSource` parameter instead of a manual `toPageRequest` call:

| | `sortableSource` default |
|---|---|
| `MongoDbPaginator.toPagination`, `R2dbcPaginator.toPagination` | `null` — they receive a built `Query`, not the filter, so pass `sortableSource = UserFilter::class` |
| `R2dbcJoinQuery.toPagination` | the `filter`'s class when it is annotated — passing the filter is enough |

```kotlin
paginator.toPagination(query, pageable, User::class, sortableSource = UserFilter::class)
joinQuery.toPagination(spec, UserOrderRow::class, pageable, filter)          // rules from filter's class
```

- Mapping is idempotent: a `Pageable` you already ran through `toPageRequest` passes unchanged, because already-mapped field names (`fullName` below) are accepted alongside the API names (`name`). An API name still wins if it equals another entry's field.

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
        val query = Query()
        filter.search?.let { query.addCriteria(Criteria.where("fullName").regex(it, "i")) }

        return paginator.toPagination(query, pageable, User::class, sortableSource = UserFilter::class)
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

## R2DBC Support

### TransactionalManager

`TransactionalManager` runs work **after the current reactive transaction commits** — sending events, emails or cache invalidations only once the data is really stored. It is auto-configured; inject it where needed.

```kotlin
@Service
class OrderService(
    private val orders: OrderRepository,
    private val transactional: TransactionalManager,
    private val events: EventPublisher
) {
    @Transactional
    suspend fun place(order: Order): Order {
        val saved = orders.save(order)
        transactional.afterCommit("order-placed-${saved.id}") {
            events.publish(OrderPlaced(saved.id))   // runs only if the transaction commits
        }
        return saved
    }
}
```

Programmatic transactions work the same way (`transactionalOperator.executeAndAwait { … }`).

**`afterCommit(name, timeout = 30s) { … }`**

- Must be called inside an active reactive transaction with synchronization; otherwise it throws `IllegalStateException`.
- Runs only if the transaction **commits** — never after a rollback or a failed transaction.
- **Fire-and-forget:** the action runs in the background on `Dispatchers.Default`; the caller (and the HTTP response) does not wait for it.
- Runs **outside** the original transaction. It sees the committed data; its own writes are not part of that transaction (wrap them in a new transaction if they must be atomic).
- Failures and timeouts are **logged, not rethrown**, and never affect the already committed transaction. An action exceeding `timeout` is cancelled. If the work must not be lost, persist it (e.g. an outbox table) instead of relying on the action alone.
- `name` is used in log messages only.

**`inTransaction(name) { … }`** runs the block if a transaction is active and throws `IllegalStateException` otherwise — a guard for code that must never run outside a transaction.

**Shutdown.** The bean is a `SmartLifecycle`. On application stop it stops accepting new actions (late ones are dropped with a `WARN`), waits up to `shutdownTimeout` (10s) for running actions, then cancels the rest. This happens after the web server has stopped taking requests and before the connection pool is closed.

To change the defaults, declare your own bean (the auto-configured one backs off):

```kotlin
@Bean
fun transactionalManager() = TransactionalManager(defaultTimeout = 1.minutes, shutdownTimeout = 30.seconds)
```

---

### JSON Converters

Store `List<String>` and `Map<String, Any?>` entity properties as JSON text (e.g. MySQL `JSON` or `TEXT` columns) without writing converters:

```kotlin
@Table("products")
data class Product(
    @Id val id: Long? = null,
    val tags: List<String> = emptyList(),          // column: ["new","sale"]
    val attributes: Map<String, Any?> = emptyMap() // column: {"color":"red","size":42}
)
```

| Converter | Direction | Behaviour |
|---|---|---|
| `ListToStringConverter` | `List<String>` → `String` | JSON array |
| `StringToListConverter` | `String` → `List<String>` | blank → empty list; `null` elements dropped |
| `MapToJsonConverter` | `Map<String, Any?>` → `String` | JSON object |
| `JsonToMapConverter` | `String` → `Map<String, Any?>` | blank → empty map |

- Serialization uses the application's Jackson 3 `ObjectMapper` (`tools.jackson.databind.ObjectMapper`), so its configuration applies.
- **Invalid JSON is read as an empty list / map** and logged at `ERROR` — the row still loads. Saving that entity back would overwrite the column with `[]` / `{}`.
- The conversions apply to every `List<String>` / `Map<String, Any?>` property of every R2DBC entity.

**Registration.** `R2dbcConvertersAutoConfiguration` provides an `R2dbcCustomConversions` bean (for the dialect of your `ConnectionFactory`) containing these converters when both a `ConnectionFactory` and a Jackson `ObjectMapper` bean exist. Without Jackson, Spring Boot's default conversions are used unchanged.

If you declare **your own** `R2dbcCustomConversions`, the auto-configuration backs off and the JSON converters are **not** registered — add them yourself:

```kotlin
@Bean
fun r2dbcCustomConversions(objectMapper: ObjectMapper, connectionFactory: ConnectionFactory) =
    R2dbcCustomConversions.of(
        DialectResolver.getDialect(connectionFactory),
        R2dbcConverters(objectMapper).toList() + listOf(MyOtherConverter())
    )
```

---

## Auto-configuration

All beans are registered by Spring Boot auto-configuration and back off when you declare a bean of the same type.

| Bean | Registered when |
|---|---|
| `MongoDbPaginator` | a `ReactiveMongoTemplate` bean exists |
| `MongoDbPurger` | a `ReactiveMongoOperations` bean exists |
| `R2dbcPaginator`, `R2dbcJoinQuery` | an `R2dbcEntityTemplate` bean exists |
| `R2dbcDbPurger` | a `DatabaseClient` bean exists |
| `TransactionalManager` | Spring's reactive transaction support is on the classpath |
| `R2dbcCustomConversions` (JSON converters) | a `ConnectionFactory` and a Jackson 3 `ObjectMapper` bean exist |

`PageRequestMapper`, `MongoQueryMapper` and `SqlQueryMapper` are plain singletons (`object`s) and need no bean.

---

## Purger

The purger components are intended for **test environments only**. They wipe all data from the database and are useful for resetting state between integration tests.

> **Warning:** Never inject or call a purger in production code. Both components log a `WARN` message on invocation as a safety signal.

### MongoDbPurger

`MongoDbPurger` drops every collection in the connected MongoDB database. It is auto-configured when a `ReactiveMongoOperations` bean exists.

```kotlin
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

`R2dbcDbPurger` truncates all user-defined tables in the connected MySQL database. It is auto-configured when a `DatabaseClient` bean exists.

```kotlin
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

## Development

```bash
./gradlew check            # tests, formatting (Spotless/ktlint) and coverage verification
./gradlew spotlessApply    # fix formatting
./gradlew installGitHook   # pre-commit hook that enforces formatting
```

The R2DBC join-query tests run against H2 and against MySQL 8.4 via [Testcontainers](https://testcontainers.com). The MySQL tests need a running Docker daemon and are **skipped** when Docker is unavailable.

---

## License

MIT License
