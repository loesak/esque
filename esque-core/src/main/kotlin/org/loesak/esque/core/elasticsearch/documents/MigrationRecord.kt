package org.loesak.esque.core.elasticsearch.documents

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
data class MigrationRecord(
    @get:JvmName("migrationKey") @get:JsonProperty("migrationKey") val migrationKey: String,
    @get:JvmName("order") @get:JsonProperty("order") val order: Int,
    @get:JvmName("filename") @get:JsonProperty("filename") val filename: String,
    @get:JvmName("version") @get:JsonProperty("version") val version: String,
    @get:JvmName("description") @get:JsonProperty("description") val description: String,
    @get:JvmName("checksum") @get:JsonProperty("checksum") val checksum: Int,
    @get:JvmName("installedBy") @get:JsonProperty("installedBy") val installedBy: String?,
    @get:JvmName("installedOn") @get:JsonProperty("installedOn") val installedOn: Instant,
    @get:JvmName("executionTime") @get:JsonProperty("executionTime") val executionTime: Long,
)
