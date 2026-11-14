# Database Tools

A Kotlin/Spring library that provides reactive pagination, flexible query mapping, a typed SQL grid query builder, and database purging utilities for MongoDB and R2DBC (SQL) databases. Built on top of Spring Data, jOOQ and Kotlin coroutines, it removes boilerplate from common data-access patterns — paginated queries, filter-to-query conversion, typed multi-table joins and aggregates on R2DBC, sort-field allowlisting, after-commit actions, JSON column conversion, and test-environment teardown — so you can focus on business logic instead of infrastructure plumbing.

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
- [Grid Query Builder (R2DBC)](#grid-query-builder-r2dbc)
  - [Aggregates](#aggregates)
  - [SQL Rendering and Binding](#sql-rendering-and-binding)
  - [Examples](#examples)
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

The library's only transitive dependency is [jOOQ](https://www.jooq.org/) (`org.jooq:jooq`, open-source edition), which builds the SQL of grid queries. Everything else is provided by your application, so it never overrides your versions; Spring Boot's dependency management also aligns the jOOQ version with yours. Built against **Spring Boot 4.1** (Kotlin 2.4, Java 25).

| Always required | Notes |
|---|---|
| `org.springframework.boot:spring-boot-starter-webflux` | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-reactor` | |
| `org.jetbrains.kotlin:kotlin-reflect` | used by the query mappers; Spring's Kotlin support needs it anyway |

| Only for the features you use | Feature |
|---|---|
| `org.springframework.boot:spring-boot-starter-data-mongodb-reactive` | `MongoDbPaginator`, `MongoQueryMapper`, `MongoDbPurger` |
| `org.springframework.boot:spring-boot-starter-data-r2dbc` | `GridQueryBuilder` (SQL grids: filters, joins, sorting, paging), `R2dbcDbPurger`, converters, `TransactionalManager` |
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

On R2DBC (reactive SQL databases such as MySQL, PostgreSQL), pages come from [`GridQueryBuilder`](#grid-query-builder-r2dbc)'s `fetchPage`, which runs the items and count queries in parallel and returns the same `Pagination<T>`. A single table is a query without joins:

**Inject in your service:**

```kotlin
@Service
class OrderService(private val queryBuilder: GridQueryBuilder) {
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

`@QueryField` turns annotated filter data classes into query conditions: `MongoQueryMapper` builds MongoDB queries, and on SQL (R2DBC) the same filters are passed to [`GridQueryBuilder`](#grid-query-builder-r2dbc). It eliminates manual null-checks — only non-null, non-blank fields contribute to the query.

### @QueryField

Place `@QueryField` on properties of a filter data class to declare how each field maps to a DB criterion.

```kotlin
@QueryField(
    name        = "",               // Field (Mongo) or entity property / column (SQL). Defaults to the Kotlin property name.
    operator    = QueryOperator.EQ,
    ignoreEmpty = true,             // Skip null and blank String values (default: true).
    table       = "",               // Table alias from table<Entity>("alias"); see Grid Query Builder. Empty = root table.
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

Top-level keys of `base` are implicitly ANDed with it. A `base` that already has a top-level `$and` cannot be extended this way (Spring throws). Fields with `@QueryField(table = …)` or `aggregate = …` are rejected — they only apply to SQL grid queries ([Grid Query Builder](#grid-query-builder-r2dbc)).

---

### SQL Filters (R2DBC)

On SQL databases, `@QueryField` filters run through [`GridQueryBuilder`](#grid-query-builder-r2dbc) — a single table is simply a query without joins:

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

The result type can be the entity itself or a DTO (see [Grid Query Builder](#grid-query-builder-r2dbc)). Values are bound as parameters and converted to the entity property's type.

SQL wildcards in user input are always escaped — `%`, `_`, and `\` in the value are quoted automatically:

```kotlin
// User types "100%" → LIKE '%100\%%'  (not an open wildcard)
@QueryField(operator = QueryOperator.CONTAINS) val title: String? = null
```

`REGEX` and `REGEX_IGNORE_CASE` throw `UnsupportedOperationException` on SQL — use `CONTAINS`, `STARTS_WITH`, or `ENDS_WITH` instead.

`MongoQueryMapper` rejects fields with `@QueryField(table = ...)` or `aggregate`; on SQL use the [Grid Query Builder](#grid-query-builder-r2dbc).

---

### Query Mapper Extensions

```kotlin
import io.github.kostack.database_tools.query_mapper.extension.toMappedQuery
import io.github.kostack.database_tools.query_mapper.extension.toPageRequest
import io.github.kostack.database_tools.query_mapper.extension.map

// Map a filter into a MongoDB query; the receiver's sort / paging / fields are kept (SQL: use GridQueryBuilder)
val mongoQuery = Query().limit(20).toMappedQuery(filter)              // MongoDB (receiver is copied, not mutated)

// Mandatory criteria go first
val scoped = Query().toMappedQuery(filter, required = Criteria.where("tenantId").`is`(tenantId))

// Validate and sanitise a Pageable (delegates to PageRequestMapper)
val pageRequest = pageable.toPageRequest(UserEntity::class)

// Transform the content of a Pagination without rewrapping
val userDtos: Pagination<UserDto> = userPagination.map { UserDto(it) }
```

---

## Grid Query Builder (R2DBC)

`GridQueryBuilder` (auto-configured when an `R2dbcEntityTemplate` bean exists) is the library's SQL query API for **grids**: list endpoints that filter, sort and page rows. It runs `@QueryField` filters over a single table or a root table plus joined tables, and maps each row into an entity or DTO. Tables and columns are referenced through entity classes and properties:

```kotlin
@Service
class CustomerService(private val queryBuilder: GridQueryBuilder) {
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

Imports: the entry points are in `io.github.kostack.database_tools.r2dbc.grid` (`GridQueryBuilder`, `GridQuery`), and tables, columns and conditions in `io.github.kostack.database_tools.r2dbc.grid.dsl` (`table`, `EntityTable`, `EntityColumn`, `ColumnCondition`, `JoinCondition`):

```kotlin
import io.github.kostack.database_tools.r2dbc.grid.GridQueryBuilder
import io.github.kostack.database_tools.r2dbc.grid.dsl.table
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

- Conditions per table: `.where(...)` conditions AND filter fields. `or` inside a condition keeps its precedence (jOOQ parenthesises nested conditions). Several conditions, or several `.where` calls, are ANDed.
- Sort properties are DTO property names (`orderId`) or `alias.property` (`o.total`, or `order[Order::total].path`); anything that isn't a plain identifier is rejected.
- `ignoreCase()` orders (`sort=name,asc,ignorecase`) sort by `LOWER(column)`; they are ignored for aggregate properties.
- Every value is a bind parameter, converted to the entity property's type like Spring Data does (e.g. `0` → `0L` for a `Long` id, enums and custom converters applied).
- `GridQueryBuilder.from(...)` returns a `GridQuery`. It is immutable: every step returns a new `GridQuery`, so a base query (e.g. with the tenant condition) can be shared and extended.
- Misuse fails fast with `IllegalArgumentException`: an alias that isn't a plain identifier (`table<Order>("o-1")`), an ON condition that doesn't use the joined table, compares two of its own columns or uses an `existsJoin` / `aggregateJoin` table as the parent, an extra ON condition (`and = …`) on another table, a condition combining columns of different tables, or a table that was not declared (or was declared under another entity type).

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

### Examples

Three complete grid queries, with the SQL they render on MySQL (line breaks added). The entities:

```kotlin
@Table("customers")   data class Customer(@Id val id: Long, val tenantId: Long, val name: String, val email: String, val tier: Tier)
@Table("orders")      data class Order(@Id val id: Long, val tenantId: Long, val customerId: Long?, val status: OrderStatus,
                                       val total: BigDecimal, val createdAt: Instant, val deletedAt: Instant?)
@Table("order_items") data class OrderItem(@Id val id: Long, val orderId: Long, val sku: String, val quantity: Int, val price: BigDecimal)
@Table("shipments")   data class Shipment(@Id val id: Long, val orderId: Long, val carrier: String, val trackingNo: String?)
@Table("tickets")     data class Ticket(@Id val id: Long, val customerId: Long, val status: String)
@Table("accounts")    data class Account(@Id val id: Long, val iban: String, val owner: String)
@Table("transfers")   data class Transfer(@Id val id: Long, val fromAccountId: Long, val toAccountId: Long, val amount: BigDecimal, val bookedAt: Instant)
```

#### Orders grid: all four join kinds, fixed conditions with `OR`, an aggregate filter

```kotlin
data class OrderGridFilter(
    @QueryField(operator = IN) val status: List<OrderStatus>? = null,
    @QueryField(name = "createdAt", operator = GTE) val createdFrom: Instant? = null,
    @QueryField(name = "createdAt", operator = LT) val createdTo: Instant? = null,
    @QueryField(table = "c", name = "name", operator = CONTAINS_IGNORE_CASE) val customer: String? = null,
    @QueryField(table = "i", name = "sku") val sku: String? = null,                                  // activates EXISTS
    @QueryField(table = "li", aggregate = Aggregate.COUNT, operator = GTE) val minItems: Long? = null // filter on COUNT
)

data class OrderGridRow(
    val id: Long, val status: OrderStatus, val total: BigDecimal, val createdAt: Instant,
    @SelectColumn(table = "c", name = "name") val customerName: String,
    @SelectColumn(table = "sh", name = "carrier") val carrier: String?,            // LEFT → nullable
    @SelectColumn(table = "sh", name = "trackingNo") val trackingNo: String?,
    @SelectAggregate(table = "li", function = Aggregate.COUNT) val itemCount: Long,
    @SelectAggregate(table = "li", function = Aggregate.SUM, name = "quantity") val units: Long?
)

val order = table<Order>("o"); val customer = table<Customer>("c"); val shipment = table<Shipment>("sh")
val item = table<OrderItem>("i"); val lines = table<OrderItem>("li")       // same entity, two roles

queryBuilder
    .from(order)
    .join(customer).on(order[Order::customerId] eq customer[Customer::id])    // Long? FK vs Long id
    .leftJoin(shipment).on(order[Order::id] eq shipment[Shipment::orderId])
    .existsJoin(item).on(order[Order::id] eq item[OrderItem::orderId])
    .aggregateJoin(lines).on(order[Order::id] eq lines[OrderItem::orderId])
    .where(
        order[Order::tenantId] eq tenantId,
        order[Order::deletedAt].isNull(),
        (order[Order::status] ne OrderStatus.CANCELLED) or (order[Order::total] gt BigDecimal("1000")),
        lines[OrderItem::quantity] gt 0                                      // on the aggregate table: scopes what is counted
    )
    .fetchPage<OrderGridRow>(filter, PageRequest.of(2, 20, Sort.by(Sort.Order.asc("customerName").ignoreCase(), Sort.Order.desc("total"))))
```

```sql
SELECT o.`id` AS `id`, o.`status` AS `status`, o.`total` AS `total`, o.`created_at` AS `created_at`,
       c.`name` AS `customer_name`, sh.`carrier` AS `carrier`, sh.`tracking_no` AS `tracking_no`,
       (SELECT count(*) FROM `order_items` AS li WHERE (li.`quantity` > ? AND li.`order_id` = o.`id`)) AS `item_count`,
       (SELECT sum(li.`quantity`) FROM `order_items` AS li WHERE (li.`quantity` > ? AND li.`order_id` = o.`id`)) AS `units`
FROM `orders` AS o
JOIN `customers` AS c ON o.`customer_id` = c.`id`
LEFT OUTER JOIN `shipments` AS sh ON o.`id` = sh.`order_id`
WHERE (o.`tenant_id` = ? AND o.`deleted_at` IS NULL AND (o.`status` <> ? OR o.`total` > ?)        -- fixed conditions
   AND o.`created_at` >= ? AND o.`status` IN (?, ?) AND upper(c.`name`) LIKE upper(?)          -- filter fields
   AND o.`id` IN (SELECT i.`order_id` FROM `order_items` AS i WHERE i.`sku` = ?)              -- EXISTS (sku filter)
   AND (SELECT count(*) FROM `order_items` AS li WHERE (li.`quantity` > ? AND li.`order_id` = o.`id`)) >= ?)
ORDER BY lower(c.`name`) ASC, o.`total` DESC LIMIT 20 OFFSET 40
-- binds: [0, 0, 7, CANCELLED, 1000, 2026-01-01T00:00:00Z, PAID, SHIPPED, %acme%, SKU-1, 0, 2]
```

- Fixed root conditions come before the filter fields in `WHERE`; the `OR` keeps its parentheses, and enums are bound as their names.
- The fixed condition on the aggregate table `li` lands inside every `li` subquery, so only lines with `quantity > 0` are counted; it does not remove orders.
- The `EXISTS` filter on `sku` never duplicates orders, even when an order has several matching lines.

#### Customers grid: stats over paid orders only, "has an open ticket" filter, fixed `OR`

```kotlin
data class CustomerGridFilter(
    @QueryField(operator = CONTAINS_IGNORE_CASE) val email: String? = null,
    @QueryField(table = "paid", aggregate = Aggregate.SUM, name = "total", operator = GTE) val minSpent: BigDecimal? = null,
    @QueryField(table = "t", name = "status") val ticketStatus: String? = null
)

data class CustomerGridRow(
    val id: Long, val name: String, val tier: Tier,
    @SelectAggregate(table = "paid", function = Aggregate.COUNT) val paidOrders: Long,
    @SelectAggregate(table = "paid", function = Aggregate.SUM, name = "total") val spent: BigDecimal?,
    @SelectAggregate(table = "paid", function = Aggregate.MAX, name = "createdAt") val lastOrderAt: Instant?
)

val c = table<Customer>("c"); val paid = table<Order>("paid"); val ticket = table<Ticket>("t")

queryBuilder
    .from(c)
    .aggregateJoin(paid).on(c[Customer::id] eq paid[Order::customerId], and = paid[Order::status] eq OrderStatus.PAID)
    .existsJoin(ticket).on(c[Customer::id] eq ticket[Ticket::customerId])
    .where(c[Customer::tenantId] eq tenantId, (c[Customer::tier] eq Tier.GOLD) or (c[Customer::tier] eq Tier.PLATINUM))
    .fetchPage<CustomerGridRow>(CustomerGridFilter(minSpent = BigDecimal("500"), ticketStatus = "OPEN"),
                                PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "spent")))
```

```sql
SELECT c.`id` AS `id`, c.`name` AS `name`, c.`tier` AS `tier`,
       (SELECT count(*) FROM `orders` AS paid WHERE (paid.`status` = ? AND paid.`customer_id` = c.`id`)) AS `paid_orders`,
       (SELECT sum(paid.`total`) FROM `orders` AS paid WHERE (paid.`status` = ? AND paid.`customer_id` = c.`id`)) AS `spent`,
       (SELECT max(paid.`created_at`) FROM `orders` AS paid WHERE (paid.`status` = ? AND paid.`customer_id` = c.`id`)) AS `last_order_at`
FROM `customers` AS c
WHERE (c.`tenant_id` = ? AND (c.`tier` = ? OR c.`tier` = ?)
   AND c.`id` IN (SELECT t.`customer_id` FROM `tickets` AS t WHERE t.`status` = ?)
   AND (SELECT sum(paid.`total`) FROM `orders` AS paid WHERE (paid.`status` = ? AND paid.`customer_id` = c.`id`)) >= ?)
ORDER BY `spent` DESC LIMIT 20 OFFSET 40
-- binds: [PAID, PAID, PAID, 7, GOLD, PLATINUM, OPEN, PAID, 500]
```

- `and = paid[Order::status] eq PAID` restricts every aggregate, including the `minSpent` filter, to paid orders. Customers without paid orders still appear, with `paidOrders = 0` and `spent = null`.
- `sort=spent` orders by the select alias instead of repeating the subquery.

#### Transfers grid: the same entity joined twice

```kotlin
data class TransferGridFilter(
    @QueryField(table = "src", name = "owner", operator = CONTAINS_IGNORE_CASE) val from: String? = null,
    @QueryField(table = "dst", name = "owner", operator = CONTAINS_IGNORE_CASE) val to: String? = null,
    @QueryField(name = "amount", operator = GTE) val minAmount: BigDecimal? = null
)

data class TransferGridRow(
    val id: Long, val amount: BigDecimal, val bookedAt: Instant,
    @SelectColumn(table = "src", name = "iban") val fromIban: String,
    @SelectColumn(table = "src", name = "owner") val fromOwner: String,
    @SelectColumn(table = "dst", name = "iban") val toIban: String,
    @SelectColumn(table = "dst", name = "owner") val toOwner: String
)

val t = table<Transfer>("t"); val src = table<Account>("src"); val dst = table<Account>("dst")

queryBuilder
    .from(t)
    .join(src).on(t[Transfer::fromAccountId] eq src[Account::id])
    .join(dst).on(t[Transfer::toAccountId] eq dst[Account::id])
    .where(t[Transfer::bookedAt] ge monthStart, t[Transfer::bookedAt] lt nextMonthStart)    // a range = ge + lt
    .fetchPage<TransferGridRow>(TransferGridFilter(from = "smith", minAmount = BigDecimal("100")),
                                PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "bookedAt")))
```

```sql
SELECT t.`id` AS `id`, t.`amount` AS `amount`, t.`booked_at` AS `booked_at`,
       src.`iban` AS `from_iban`, src.`owner` AS `from_owner`, dst.`iban` AS `to_iban`, dst.`owner` AS `to_owner`
FROM `transfers` AS t
JOIN `accounts` AS src ON t.`from_account_id` = src.`id`
JOIN `accounts` AS dst ON t.`to_account_id` = dst.`id`
WHERE (t.`booked_at` >= ? AND t.`booked_at` < ? AND t.`amount` >= ? AND upper(src.`owner`) LIKE upper(?))
ORDER BY t.`booked_at` DESC LIMIT 20 OFFSET 40
-- binds: [2026-11-01T00:00:00Z, 2026-12-01T00:00:00Z, 100, %smith%]
```

- The two aliases are independent tables, and the DTO picks columns from each. The `to` filter is `null`, so it adds nothing to the SQL.

Out of scope for the builder (use jOOQ or `DatabaseClient` directly): comparing two columns ("source owner = destination owner"), `OR` across tables ("GOLD customers **or** customers with an open ticket") and `GROUP BY` reports ("revenue per month").

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
| `field`   | What to sort by: the document field (MongoDB), or a grid row property / `alias.property` (`GridQueryBuilder`). Defaults to `name` when blank. |

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
| `GridQueryBuilder` | an `R2dbcEntityTemplate` bean exists |
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

The grid query tests run against H2 and against MySQL 8.4 via [Testcontainers](https://testcontainers.com). The MySQL tests need a running Docker daemon and are **skipped** when Docker is unavailable.

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
