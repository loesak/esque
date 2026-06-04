package org.loesak.esque.core.elasticsearch.documents

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
data class MigrationRecord(
    @get:JvmName("migrationKey") val migrationKey: String,
    @get:JvmName("order") val order: Int,
    @get:JvmName("filename") val filename: String,
    @get:JvmName("version") val version: String,
    @get:JvmName("description") val description: String,
    @get:JvmName("checksum") val checksum: Int,
    @get:JvmName("installedBy") val installedBy: String?,
    @get:JvmName("installedOn") val installedOn: Instant,
    @get:JvmName("executionTime") val executionTime: Long,
)
