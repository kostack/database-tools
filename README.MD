# Database Tools

A Kotlin/Spring library that provides reactive pagination, flexible query mapping, a typed SQL query builder, and database purging utilities for MongoDB and R2DBC (SQL) databases. Built on top of Spring Data, jOOQ and Kotlin coroutines, it removes boilerplate from common data-access patterns — paginated queries, filter-to-query conversion, typed multi-table joins and aggregates on R2DBC, sort-field allowlisting, after-commit actions, JSON column conversion, and test-environment teardown — so you can focus on business logic instead of infrastructure plumbing.

```kotlin
val customer = table<Customer>("c")
val order = table<Order>("o")

val page: Pagination<CustomerGridDto> = queryBuilder
    .from(customer)
    .leftJoin(order).on(customer[Customer::id] eq order[Order::customerId])
    .fetchPage<CustomerGridDto>(filter = filter, pageable = pageable)
```

## Table of Contents

- [Installation](#installation)
- [Pagination](#pagination)
  - [Pagination Response Model](#pagination-response-model)
  - [MongoDbPaginator](#mongodbpaginator)
  - [R2DBC Pagination](#r2dbc-pagination)
- [Query Mapper](#query-mapper)
  - [@QueryField](#queryfield)
  - [QueryOperator](#queryoperator)
  - [MongoQueryMapper](#mongoquerymapper)
  - [SQL Filters (R2DBC)](#sql-filters-r2dbc)
  - [Query Mapper Extensions](#query-mapper-extensions)
- [Query Builder (R2DBC)](#query-builder-r2dbc)
  - [Aggregates](#aggregates)
  - [SQL Rendering and Binding](#sql-rendering-and-binding)
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
    implementation("io.github.kostack:database-tools:<version>")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'io.github.kostack:database-tools:<version>'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.kostack</groupId>
    <artifactId>database-tools</artifactId>
    <version>VERSION</version>
</dependency>
```

Replace `<version>` / `VERSION` with the latest release tag from [GitHub Releases](https://github.com/kostack/database-tools/releases).

The library is published to [Maven Central](https://central.sonatype.com/artifact/io.github.kostack/database-tools), so no additional repository configuration is needed.

### Requirements

The library's only transitive dependency is [jOOQ](https://www.jooq.org/) (`org.jooq:jooq`, open-source edition), which builds the SQL of join queries. Everything else is provided by your application, so it never overrides your versions; Spring Boot's dependency management also aligns the jOOQ version with yours. Built against **Spring Boot 4.1** (Kotlin 2.4, Java 25).

| Always required | Notes |
|---|---|
| `org.springframework.boot:spring-boot-starter-webflux` | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-reactor` | |
| `org.jetbrains.kotlin:kotlin-reflect` | used by the query mappers; Spring's Kotlin support needs it anyway |

| Only for the features you use | Feature |
|---|---|
| `org.springframework.boot:spring-boot-starter-data-mongodb-reactive` | `MongoDbPaginator`, `MongoQueryMapper`, `MongoDbPurger` |
| `org.springframework.boot:spring-boot-starter-data-r2dbc` | `R2dbcQueryBuilder` (SQL filters, pagination and join queries), `R2dbcDbPurger`, converters, `TransactionalManager` |
| `io.asyncer:r2dbc-mysql` | MySQL (`R2dbcDbPurger` is MySQL-only) |
| `io.swagger.core.v3:swagger-annotations` | OpenAPI schema annotations on `Pagination` |

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

**Sorting.** The `Pageable` is used as is. To allowlist sort fields, map it first with the filter class's `@Sortable` / `@SortableDefaults`: `pageable.toPageRequest(UserFilter::class)` — see [PageRequestMapper](#pagerequestmapper).

`ignoreCase()` orders (`sort=name,asc,ignorecase`) are **not supported** on MongoDB: Spring Data MongoDB rejects them with `IllegalArgumentException`. Use a case-insensitive collation on the collection or query instead.

---

### R2DBC Pagination

On R2DBC (reactive SQL databases such as MySQL, PostgreSQL), pages come from [`R2dbcQueryBuilder`](#query-builder-r2dbc)'s `fetchPage`, which runs the items and count queries in parallel and returns the same `Pagination<T>`. A single table is a query without joins:

**Inject in your service:**

```kotlin
@Service
class OrderService(private val queryBuilder: R2dbcQueryBuilder) {
    private val order = table<Order>("o")

    suspend fun listOrders(pageable: Pageable, filter: OrderFilter): Pagination<Order> =
        queryBuilder
            .from(order)
            .fetchPage<Order>(filter, pageable.toPageRequest(OrderFilter::class))
}

@Sortable(name = "createdAt")
@SortableDefaults(defaultSortField = "createdAt", defaultSortDirection = Sort.Direction.DESC, maxPageSize = 100)
data class OrderFilter(
    @QueryField val status: String? = null
)
```

**Use in a controller:**

```kotlin
@RestController
@RequestMapping("/orders")
class OrderController(private val orderService: OrderService) {

    @GetMapping
    suspend fun getOrders(pageable: Pageable, filter: OrderFilter): Pagination<Order> =
        orderService.listOrders(pageable, filter)
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

- The count query uses the same filter fields and conditions without `ORDER BY` / `LIMIT` / `OFFSET`, so the total is accurate regardless of the requested page.
- **Sorting.** `pageable.toPageRequest(Filter::class)` allowlists sort fields and caps the page size ([details](#pagerequestmapper)); without it, any entity property may be sorted on.
- **Case-insensitive sorting.** `ignoreCase()` orders (e.g. `sort=name,asc,ignorecase`) are sorted by `LOWER(column)`.
- Fixed conditions (e.g. a tenant) go in `.where(order[Order::tenantId] eq tenantId)`; `@Embedded` properties on the result type are not supported.

---

## Query Mapper

`@QueryField` turns annotated filter data classes into query conditions: `MongoQueryMapper` builds MongoDB queries, and on SQL (R2DBC) the same filters are passed to [`R2dbcQueryBuilder`](#query-builder-r2dbc). It eliminates manual null-checks — only non-null, non-blank fields contribute to the query.

### @QueryField

Place `@QueryField` on properties of a filter data class to declare how each field maps to a DB criterion.

```kotlin
@QueryField(
    name        = "",               // Field (Mongo) or entity property / column (SQL). Defaults to the Kotlin property name.
    operator    = QueryOperator.EQ,
    ignoreEmpty = true,             // Skip null and blank String values (default: true).
    table       = "",               // Table alias from table<Entity>("alias"); see Query Builder. Empty = root table.
    aggregate   = Aggregate.NONE    // Compare COUNT/SUM/AVG/MIN/MAX of the column; see Aggregates.
)
```

**Custom column name:**

```kotlin
data class UserFilter(
    @QueryField(name = "user_id") val userId: String? = null
)
// → queries the "user_id" column, not "userId" (on SQL, an entity property name is mapped to its column)
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
| `NE` | `<> value` | `{ $ne: value }` | Any |
| `CONTAINS` | `LIKE '%value%'` | regex `value` | String |
| `CONTAINS_IGNORE_CASE` | `UPPER(col) LIKE UPPER('%value%')` | regex `value` + `i` | String |
| `STARTS_WITH` | `LIKE 'value%'` | regex `^value` | String |
| `STARTS_WITH_IGNORE_CASE` | `UPPER(col) LIKE UPPER('value%')` | regex `^value` + `i` | String |
| `ENDS_WITH` | `LIKE '%value'` | regex `value$` | String |
| `ENDS_WITH_IGNORE_CASE` | `UPPER(col) LIKE UPPER('%value')` | regex `value$` + `i` | String |
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

On SQL, every value is a bind parameter and `LIKE` wildcards in it are escaped. With `aggregate`, the column side is the aggregate subquery (see [Aggregates](#aggregates)).

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

### SQL Filters (R2DBC)

On SQL databases, `@QueryField` filters run through [`R2dbcQueryBuilder`](#query-builder-r2dbc) — a single table is simply a query without joins:

```kotlin
data class OrderFilter(
    @QueryField val customerId: String? = null,
    @QueryField(operator = QueryOperator.IN) val statuses: List<String>? = null,
    @QueryField(operator = QueryOperator.GTE) val totalAmount: BigDecimal? = null,
)

val order = table<Order>("o")

val page: Pagination<Order> = queryBuilder
    .from(order)
    .where(order[Order::tenantId] eq tenantId)                         // fixed condition, placed first
    .fetchPage<Order>(OrderFilter(customerId = "cust-42", statuses = listOf("PAID", "SHIPPED")), pageable)
// WHERE (o.`tenant_id` = ? AND o.`customer_id` = ? AND o.`statuses` IN (?, ?)) ... LIMIT ...
```

The result type can be the entity itself or a DTO (see [Query Builder](#query-builder-r2dbc)). Values are bound as parameters and converted to the entity property's type.

SQL wildcards in user input are always escaped — `%`, `_`, and `\` in the value are quoted automatically:

```kotlin
// User types "100%" → LIKE '%100\%%'  (not an open wildcard)
@QueryField(operator = QueryOperator.CONTAINS) val title: String? = null
```

`REGEX` and `REGEX_IGNORE_CASE` throw `UnsupportedOperationException` on SQL — use `CONTAINS`, `STARTS_WITH`, or `ENDS_WITH` instead.

`MongoQueryMapper` rejects fields with `@QueryField(table = ...)` or `aggregate`; on SQL use the [Query Builder](#query-builder-r2dbc).

---

### Query Mapper Extensions

```kotlin
import io.github.kostack.database_tools.query_mapper.extension.toMappedQuery
import io.github.kostack.database_tools.query_mapper.extension.toPageRequest
import io.github.kostack.database_tools.query_mapper.extension.map

// Map a filter into a MongoDB query; the receiver's sort / paging / fields are kept (SQL: use R2dbcQueryBuilder)
val mongoQuery = Query().limit(20).toMappedQuery(filter)              // MongoDB (receiver is copied, not mutated)

// Mandatory criteria go first
val scoped = Query().toMappedQuery(filter, required = Criteria.where("tenantId").`is`(tenantId))

// Validate and sanitise a Pageable (delegates to PageRequestMapper)
val pageRequest = pageable.toPageRequest(UserEntity::class)

// Transform the content of a Pagination without rewrapping
val userDtos: Pagination<UserDto> = userPagination.map { UserDto(it) }
```

---

## Query Builder (R2DBC)

`R2dbcQueryBuilder` (auto-configured when an `R2dbcEntityTemplate` bean exists) is the library's SQL query API for **grids**: list endpoints that filter, sort and page rows. It runs `@QueryField` filters over a single table or a root table plus joined tables, and maps each row into an entity or DTO. Tables and columns are referenced through entity classes and properties:

```kotlin
@Service
class CustomerService(private val queryBuilder: R2dbcQueryBuilder) {
    private val customer = table<Customer>("c")
    private val order = table<Order>("o")

    suspend fun grid(filter: CustomerGridFilter, pageable: Pageable): Pagination<CustomerGridDto> =
        queryBuilder
            .from(customer)
            .leftJoin(order)
            .on(customer[Customer::id] eq order[Order::customerId])
            .fetchPage<CustomerGridDto>(filter = filter, pageable = pageable)
}
```

The SQL is built with jOOQ and executed through Spring's `R2dbcEntityTemplate`, so entity mapping, converters and transactions work as with any other Spring Data R2DBC query. Supported databases: **MySQL**, **H2** and **PostgreSQL** (see [SQL Rendering and Binding](#sql-rendering-and-binding)).

**Scope.** A grid query is one root table plus joins on a single key column each (`join`, `leftJoin`), filter-only child tables (`existsJoin`) and per-row aggregates (`aggregateJoin`), with typed fixed conditions, `@QueryField` filters, sorting and paging. It deliberately does not cover `GROUP BY` reports, `OR` across tables, column-to-column conditions, composite join keys or writes; use jOOQ or `DatabaseClient` directly for those.

**1. Declare tables and joins.** The alias given to `table(...)` is what `@QueryField(table = …)` and `@SelectColumn(table = …)` refer to. Each join is followed by `.on(...)`, which compares one key column of the joined table with a column of an already declared table, in either order.

```kotlin
val user = table<User>("u")
val order = table<Order>("o")
val address = table<Address>("a")
val item = table<Item>("i")

val query = queryBuilder
    .from(user)
    .join(order).on(user[User::id] eq order[Order::userId])               // INNER JOIN
    .leftJoin(address).on(user[User::id] eq address[Address::userId])     // LEFT OUTER JOIN
    .existsJoin(item).on(order[Order::id] eq item[Item::orderId])         // filter only
```

| Expression | Meaning |
|---|---|
| `table<Order>("o")` | the `Order` entity's table under alias `o` |
| `order[Order::total]` | column `o.total`, resolved through the entity mapping; typed by the property type without nullability (`Long?` → `Long`) |
| `order[Order::total].path` | `"o.total"`, e.g. for `Sort.by(order[Order::total].path)` |
| `a eq b` | the ON key equality, one per join; both sides must have the same type, so a nullable foreign key (`Order::customerId: Long?`) joins a `Long` id, while `Long` vs `String` does not compile |

| Builder | SQL | Columns selectable | Notes |
|---|---|---|---|
| `.join(t)` | `JOIN … ON …` | yes | one result row per joined row |
| `.leftJoin(t)` | `LEFT OUTER JOIN … ON …` | yes | missing matches give `NULL`s |
| `.existsJoin(t)` | `o.id IN (SELECT i.order_id FROM … WHERE <conditions>)` | no | semi-join: never duplicates rows; skipped when no filter field or `.where` condition targets it |
| `.aggregateJoin(t)` | correlated subqueries, see [Aggregates](#aggregates) | aggregates only | never joined as rows |

The same entity can be joined twice under different aliases (e.g. `table<Order>("o")` and `table<Order>("s")`).

**Conditions.** Fixed conditions are written with the same typed columns. The value must have the column's type and is never `null`; use `isNull()` / `isNotNull()` to test for `NULL`, also on nullable columns:

| Expression | SQL |
|---|---|
| `order[Order::status] eq "PAID"` / `ne` | `o.status = ?` / `<> ?` |
| `order[Order::total] gt 100` / `ge` / `lt` / `le` | `o.total > ?` / `>=` / `<` / `<=` |
| `order[Order::status] isIn listOf("NEW", "PAID")` / `notIn` | `o.status IN (?, ?)` / `NOT IN` |
| `order[Order::deletedAt].isNull()` / `.isNotNull()` | `o.deleted_at IS NULL` / `IS NOT NULL` |
| `(a eq x) and (b eq y)`, `(a eq x) or (b eq y)` | combined in parentheses |

Each condition uses the columns of one table (combining tables with `and` / `or` throws); pass conditions on different tables to `.where(...)` separately. Values are converted to the property type (enums, custom converters).

**Extra ON conditions.** `and = …` adds a condition on the joined table's own columns:

```kotlin
queryBuilder.from(user)
    .leftJoin(order).on(user[User::id] eq order[Order::userId], and = order[Order::status] eq "PAID")
// LEFT OUTER JOIN `orders` AS o ON (u.`id` = o.`user_id` AND o.`status` = ?)
```

| Join | Where the `and` condition goes |
|---|---|
| `join` / `leftJoin` | into `ON`. For `leftJoin` this keeps users without a paid order (with `NULL` columns); the same condition via a filter field or `.where(…)` lands in `WHERE` and drops them. |
| `existsJoin` | into the subquery; it does not activate the EXISTS filter on its own (filter fields / `.where` still do) |
| `aggregateJoin` | into every aggregate subquery of that alias, always |

**2. Tag filter fields** with the table alias:

```kotlin
data class UserOrderFilter(
    @QueryField(operator = QueryOperator.CONTAINS) val name: String? = null,      // root (u)
    @QueryField(table = "o") val status: String? = null,                          // o.status
    @QueryField(table = "o", name = "total", operator = QueryOperator.GTE) val minTotal: Int? = null,
    @QueryField(table = "i") val sku: String? = null                              // EXISTS on items
)
```

**3. Declare the result type.** It can be the root entity itself or a grid-row DTO whose properties are placed with `@SelectColumn` (and `@SelectAggregate`, see [Aggregates](#aggregates)). Unannotated properties come from the root table; each value is selected `AS` the DTO property's column name.

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
val rows: Flow<UserOrderRow> = query.fetch<UserOrderRow>(filter, Sort.by("orderId"))     // all rows, e.g. an export
val total: Long = query.count(filter)
val page: Pagination<UserOrderRow> = query.fetchPage<UserOrderRow>(filter, pageable)

// Fixed conditions, placed before the filter fields of their table:
query.where(user[User::tenantId] eq tenantId).fetchPage<UserOrderRow>(filter, pageable)
```

| Call | Returns |
|---|---|
| `.fetchPage<T>(filter, pageable)` | `Pagination<T>`: one page plus the total count |
| `.fetch<T>(filter, sort)` | `Flow<T>` with all matching rows (e.g. an export) |
| `.count(filter)` | `Long` |

`fetch(T::class, …)` / `fetchPage(T::class, …)` take the result type explicitly.

- Conditions per table: `.where(...)` conditions AND filter fields. `or` inside a condition keeps its precedence (jOOQ parenthesises nested conditions). Several conditions, or several `.where` calls, are ANDed.
- Sort properties are DTO property names (`orderId`) or `alias.property` (`o.total`, or `order[Order::total].path`); anything that isn't a plain identifier is rejected.
- `ignoreCase()` orders (`sort=name,asc,ignorecase`) sort by `LOWER(column)`; they are ignored for aggregate properties.
- Every value is a bind parameter, converted to the entity property's type like Spring Data does (e.g. `0` → `0L` for a `Long` id, enums and custom converters applied).
- Builders are immutable: every step returns a new builder, so a base query can be shared and extended.
- Misuse fails fast with `IllegalArgumentException`: an ON condition that doesn't use the joined table or compares two of its own columns, an extra ON condition (`and = …`) on another table, a condition combining columns of different tables, or a table that was not declared (or was declared under another entity type).

**Allowlisting sort fields.** Put `@Sortable` / `@SortableDefaults` on the filter class and map the `Pageable` with it. `field` may be a DTO property or `alias.property`:

```kotlin
@SortableDefaults(defaultSortField = "orderId", maxPageSize = 50)
@Sortable(name = "orderId")
@Sortable(name = "name")
@Sortable(name = "total", field = "o.total")
data class UserOrderFilter(
    @QueryField(table = "o") val status: String? = null
)

// GET ?status=PAID&sort=total,desc&size=500
query.fetchPage<UserOrderRow>(filter, pageable.toPageRequest(UserOrderFilter::class))
```

Unknown sort fields throw `IllegalArgumentException`, the default sort applies when none is requested, and the page size is capped (the returned `Pagination.limit` reflects the cap) — see [PageRequestMapper](#pagerequestmapper). Without the mapping, any DTO property or `alias.property` may be sorted on.

### Aggregates

`COUNT`, `SUM`, `AVG`, `MIN` and `MAX` over an `aggregateJoin` table are computed **per root row** as correlated scalar subqueries, so results stay one row per root entity and `count` / paging / sorting work unchanged.

```kotlin
val user = table<User>("u")
val stats = table<Order>("s")

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

queryBuilder
    .from(user)
    .aggregateJoin(stats).on(user[User::id] eq stats[Order::userId])
    .fetchPage<UserStatsRow>(filter, PageRequest.of(0, 20, Sort.by(DESC, "totalSum")))
```

```sql
SELECT u.`id` AS `id`, u.`name` AS `name`,
       (SELECT count(*) FROM `orders` AS s WHERE (s.`status` = ? AND s.`user_id` = u.`id`)) AS `order_count`, ...
FROM `users` AS u
WHERE (SELECT count(*) FROM `orders` AS s WHERE (s.`status` = ? AND s.`user_id` = u.`id`)) >= ?
ORDER BY `total_sum` DESC
```

- **Plain conditions on an aggregate alias** (filter fields or `.where(stats[Order::status] eq …)`) restrict the rows being aggregated, not the root rows. To filter root rows by related rows, add a separate `existsJoin` alias for the same table.
- Aggregate filter operators: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`/`NOT_EXISTS`, `IS_NOT_NULL`/`EXISTS`. `LIKE`-style operators throw `UnsupportedOperationException`.
- Over no rows `COUNT` is `0`; `SUM`, `AVG`, `MIN`, `MAX` are `NULL`, so declare those DTO properties nullable.
- Sorting by an aggregate DTO property orders by its select alias, so the subquery is not repeated.
- Each aggregate used in both the select list and a filter runs as its own subquery; index the correlation column (e.g. `orders.user_id`). For report-style grouping across many dimensions, prefer a view or a hand-written query.

### SQL Rendering and Binding

Each statement is rendered with a jOOQ `DSLContext` derived from the template's Spring R2DBC dialect, then handed to Spring as a `PreparedOperation`:

| Spring dialect | jOOQ dialect | Bind markers |
|---|---|---|
| `MySqlDialect` | `MYSQL` | `?` |
| `H2Dialect` | `H2` | `$1, $2, …` |
| `PostgresDialect` | `POSTGRES` | `$1, $2, …` |

Other dialects (Oracle, SQL Server) throw `UnsupportedOperationException`.

1. Filter fields, `.where` conditions and extra `ON` conditions become jOOQ conditions; values are wrapped as bind parameters, never inlined. `LIMIT` / `OFFSET` are inlined numbers.
2. Table and column names come from the entity mapping with Spring's quoting and letter casing; raw names must be plain identifiers.
3. jOOQ renders the SQL text and returns the bind values in text order — including the values of an aggregate subquery that appears both in the select list and in `WHERE`.
4. Spring runs the SQL and binds the values by position.

For example, on MySQL:

```kotlin
queryBuilder
    .from(user)
    .join(order).on(user[User::id] eq order[Order::userId])
    .existsJoin(item).on(order[Order::id] eq item[Item::orderId])
    .where(user[User::id] gt 0L, order[Order::total] gt 1)
    .fetchPage<UserOrderRow>(
        UserOrderFilter(name = "ali", status = "PAID", sku = "A"),
        PageRequest.of(2, 5, Sort.by("orderId"))
    )
```

```sql
SELECT u.`id` AS `id`, u.`name` AS `name`, o.`id` AS `order_id`, o.`status` AS `status`, o.`total` AS `order_total`
FROM `users` AS u JOIN `orders` AS o ON u.`id` = o.`user_id`
WHERE (u.`id` > ? AND u.`name` LIKE ? AND o.`total` > ? AND o.`status` = ?
  AND o.`id` IN (SELECT i.`order_id` FROM `items` AS i WHERE i.`sku` = ?))
ORDER BY o.`id` ASC LIMIT 5 OFFSET 10
-- binds: [0, "%ali%", 1, "PAID", "A"]; the count query is the same WHERE without ORDER BY / LIMIT
```

---

## Page Request Mapping

`PageRequestMapper` validates and sanitises a Spring `Pageable` before it reaches the database. It enforces an allowed sort-field allowlist and caps the page size, preventing clients from sorting on arbitrary columns or requesting unbounded result sets.

### @Sortable

Declare which fields the client is allowed to sort on. Apply it to the **filter class** of the endpoint (not to the document/table class) and pass that class to `PageRequestMapper`.

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
| `field`   | What to sort by: the document field (MongoDB), or a result-DTO property / `alias.property` (query builder). Defaults to `name` when blank. |

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

val mongoPage = mongoDbPaginator.toPagination(query, safePageable, User::class)                     // MongoDB
val sqlPage = queryBuilder.from(table<User>("u")).fetchPage<User>(filter, safePageable)              // SQL
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
        val query = Query()
        filter.search?.let { query.addCriteria(Criteria.where("fullName").regex(it, "i")) }

        return paginator.toPagination(query, pageable.toPageRequest(UserFilter::class), User::class)
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
| `R2dbcQueryBuilder` | an `R2dbcEntityTemplate` bean exists |
| `R2dbcDbPurger` | a `DatabaseClient` bean exists |
| `TransactionalManager` | Spring's reactive transaction support is on the classpath |
| `R2dbcCustomConversions` (JSON converters) | a `ConnectionFactory` and a Jackson 3 `ObjectMapper` bean exist |

`PageRequestMapper` and `MongoQueryMapper` are plain singletons (`object`s) and need no bean.

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

The R2DBC query builder tests run against H2 and against MySQL 8.4 via [Testcontainers](https://testcontainers.com). The MySQL tests need a running Docker daemon and are **skipped** when Docker is unavailable.

### Publishing to Maven Local

To try a local build in another project, publish it to your local Maven repository (`~/.m2/repository`). Signing keys are not needed:

```bash
./gradlew publishToMavenLocal                          # version 1.0.0
./gradlew publishToMavenLocal -PprojectVersion=1.2.3   # custom version
```

Then add `mavenLocal()` to the consuming project's repositories:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.kostack:database-tools:1.2.3")
}
```

---

## License

MIT License
