package org.loesak.esque.core.elasticsearch.documents

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
data class MigrationRecord(
    @get:JvmName("migrationKey") @JsonProperty("migrationKey") val migrationKey: String,
    @get:JvmName("order") @JsonProperty("order") val order: Int,
    @get:JvmName("filename") @JsonProperty("filename") val filename: String,
    @get:JvmName("version") @JsonProperty("version") val version: String,
    @get:JvmName("description") @JsonProperty("description") val description: String,
    @get:JvmName("checksum") @JsonProperty("checksum") val checksum: Int,
    @get:JvmName("installedBy") @JsonProperty("installedBy") val installedBy: String?,
    @get:JvmName("installedOn") @JsonProperty("installedOn") val installedOn: Instant,
    @get:JvmName("executionTime") @JsonProperty("executionTime") val executionTime: Long,
)
