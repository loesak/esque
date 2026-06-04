package org.loesak.esque.core.elasticsearch.documents

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
data class MigrationRecord(
    val migrationKey: String,
    val order: Int,
    val filename: String,
    val version: String,
    val description: String,
    val checksum: Int,
    val installedBy: String?,
    val installedOn: Instant,
    val executionTime: Long,
)
