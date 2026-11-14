package io.github.kostack.database_tools.query_mapper

import io.github.kostack.database_tools.query_mapper.annotation.QueryField
import io.r2dbc.spi.ConnectionFactories
import org.jooq.Condition
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.relational.core.query.Criteria
import org.springframework.r2dbc.core.DatabaseClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders conditions with the MySQL dialect, without a database. */
class JooqConditionMapperTest {
  enum class Status { ACTIVE, DELETED }

  data class Person(
    @Id val id: Long,
    val name: String,
    val age: Int,
    val status: Status
  )

  private val template =
    R2dbcEntityTemplate(DatabaseClient.create(ConnectionFactories.get("r2dbc:h2:mem:///unused")), MySqlDialect.INSTANCE)
  private val mapper = JooqConditionMapper(template.converter)
  private val dsl = DSL.using(SQLDialect.MYSQL)
  private val target =
    JooqConditionMapper.Target(template.converter.mappingContext.getRequiredPersistentEntity(Person::class.java)) {
      DSL.field(DSL.name("p", it))
    }

  private fun filter(source: Any): List<Condition> = QueryMapper.activeFields(source).map { mapper.map(it, target) }

  private fun sql(condition: Condition): String = dsl.render(condition)

  private fun binds(condition: Condition): List<Any?> = dsl.extractBindValues(condition).toList()

  data class Eq(
    @QueryField val name: String? = null
  )

  data class Ne(
    @QueryField(operator = QueryOperator.NE) val name: String? = null
  )

  data class Comparisons(
    @QueryField(name = "age", operator = QueryOperator.GT) val gt: Int? = null,
    @QueryField(name = "age", operator = QueryOperator.GTE) val gte: Int? = null,
    @QueryField(name = "age", operator = QueryOperator.LT) val lt: Int? = null,
    @QueryField(name = "age", operator = QueryOperator.LTE) val lte: Int? = null
  )

  data class Text(
    @QueryField(name = "name", operator = QueryOperator.CONTAINS) val contains: String? = null,
    @QueryField(name = "name", operator = QueryOperator.CONTAINS_IGNORE_CASE) val containsIc: String? = null,
    @QueryField(name = "name", operator = QueryOperator.STARTS_WITH) val startsWith: String? = null,
    @QueryField(name = "name", operator = QueryOperator.ENDS_WITH_IGNORE_CASE) val endsWithIc: String? = null
  )

  data class Sets(
    @QueryField(name = "status", operator = QueryOperator.IN) val statuses: List<Status>? = null,
    @QueryField(name = "age", operator = QueryOperator.NOT_IN) val notAges: List<Int>? = null
  )

  data class Nulls(
    @QueryField(name = "name", operator = QueryOperator.IS_NULL, ignoreEmpty = false) val missing: String? = null,
    @QueryField(name = "age", operator = QueryOperator.EXISTS) val present: Boolean? = null
  )

  data class Regex(
    @QueryField(name = "name", operator = QueryOperator.REGEX) val pattern: String? = null
  )

  data class CustomName(
    @QueryField(name = "status") val state: Status? = null
  )

  @Test
  fun `EQ binds the value`() {
    val condition = filter(Eq("John")).single()
    assertEquals("`p`.`name` = ?", sql(condition))
    assertEquals(listOf<Any?>("John"), binds(condition))
  }

  @Test
  fun `NE renders not equal`() {
    assertEquals("`p`.`name` <> ?", sql(filter(Ne("John")).single()))
  }

  @Test
  fun `comparison operators`() {
    assertEquals(
      setOf("`p`.`age` > ?", "`p`.`age` >= ?", "`p`.`age` < ?", "`p`.`age` <= ?"),
      filter(Comparisons(1, 2, 3, 4)).map(::sql).toSet()
    )
  }

  @Test
  fun `LIKE operators escape wildcards and build patterns`() {
    val conditions = filter(Text(contains = "100%_\\", containsIc = "x", startsWith = "ab", endsWithIc = "z"))
    // pattern -> rendered SQL
    val byPattern = conditions.associate { binds(it).single() as String to sql(it) }

    assertEquals(
      mapOf(
        "%100\\%\\_\\\\%" to "`p`.`name` like ?",
        "%x%" to "upper(`p`.`name`) like upper(?)",
        "ab%" to "`p`.`name` like ?",
        "%z" to "upper(`p`.`name`) like upper(?)"
      ),
      byPattern
    )
  }

  @Test
  fun `IN and NOT_IN bind each element converted to the property type`() {
    val conditions = filter(Sets(statuses = listOf(Status.ACTIVE, Status.DELETED), notAges = listOf(1, 2)))
    val byText = conditions.associate { sql(it) to binds(it) }

    assertEquals(listOf<Any?>("ACTIVE", "DELETED"), byText.getValue("`p`.`status` in (?, ?)"))
    assertEquals(listOf<Any?>(1, 2), byText.getValue("`p`.`age` not in (?, ?)"))
  }

  @Test
  fun `null checks ignore the value`() {
    assertEquals(
      setOf("`p`.`name` is null", "`p`.`age` is not null"),
      filter(Nulls(present = true)).map(::sql).toSet()
    )
  }

  @Test
  fun `empty values are skipped`() {
    assertEquals(emptyList(), filter(Eq(null)))
    assertEquals(emptyList(), filter(Eq("  ")))
    assertEquals(emptyList(), filter(Sets(statuses = emptyList())))
  }

  @Test
  fun `custom field name maps through the entity and converts enums`() {
    val condition = filter(CustomName(Status.ACTIVE)).single()
    assertEquals("`p`.`status` = ?", sql(condition))
    assertEquals(listOf<Any?>("ACTIVE"), binds(condition))
  }

  @Test
  fun `REGEX is not supported`() {
    assertThrows<UnsupportedOperationException> { filter(Regex(".*")) }
  }

  @Test
  fun `criteria keep OR precedence, ignoreCase and type conversion`() {
    val criteria =
      Criteria
        .where("id")
        .greaterThan(0)
        .and(
          Criteria
            .where("name")
            .`is`("a")
            .ignoreCase(true)
            .or("name")
            .`is`("b")
        )

    val condition = mapper.map(criteria, target)!!

    assertEquals("(`p`.`id` > ? and (upper(`p`.`name`) = upper(?) or `p`.`name` = ?))", sql(condition))
    assertEquals(listOf<Any?>(0L, "a", "b"), binds(condition))
  }

  @Test
  fun `empty criteria map to null`() {
    assertEquals(null, mapper.map(Criteria.empty(), target))
  }
}
