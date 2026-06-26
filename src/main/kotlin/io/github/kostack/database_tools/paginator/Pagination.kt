package io.github.kostack.database_tools.paginator

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A generic Pagination class for API responses.")
data class Pagination<T>(
  @field:ArraySchema(
    schema =
      Schema(
        description = "The list of items for the current page."
      )
  )
  @field:JsonProperty("content")
  val content: List<T>,
  @field:Schema(
    description = "The maximum number of items allowed per page.",
    example = "10"
  )
  @field:JsonProperty("limit")
  val limit: Int,
  @field:Schema(
    description = "The offset of the current page (starting index).",
    example = "0"
  )
  @field:JsonProperty("offset")
  val offset: Long,
  @field:Schema(
    description = "The total number of items available.",
    example = "100"
  )
  @field:JsonProperty("total")
  val total: Long
)
