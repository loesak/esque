package org.loesak.esque.core.yaml.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MigrationFile(
    @get:JvmName("metadata") val metadata: MigrationFileMetadata,
    @get:JvmName("contents") val contents: MigrationFileContents,
) : Comparable<MigrationFile> {

    data class MigrationFileMetadata(
        @get:JvmName("filename") val filename: String,
        @get:JvmName("version") val version: String,
        @get:JvmName("description") val description: String,
        @get:JvmName("checksum") val checksum: Int,
    )

    data class MigrationFileContents(
        @get:JvmName("requests") @JsonProperty("requests") val requests: List<MigrationFileRequestDefinition>,
    )

    data class MigrationFileRequestDefinition(
        @get:JvmName("method") @JsonProperty("method") val method: String,
        @get:JvmName("path") @JsonProperty("path") val path: String,
        @get:JvmName("contentType") @JsonProperty("contentType") val contentType: String? = null,
        @get:JvmName("params") @JsonProperty("params") val params: Map<String, String>? = null,
        @get:JvmName("body") @JsonProperty("body") val body: String? = null,
    )

    override fun compareTo(other: MigrationFile): Int {
        val thisParts = metadata.version.split(".")
        val thatParts = other.metadata.version.split(".")
        val maxParts = maxOf(thisParts.size, thatParts.size)

        for (i in 0 until maxParts) {
            val thisVal = if (i < thisParts.size) thisParts[i].toInt() else 0
            val thatVal = if (i < thatParts.size) thatParts[i].toInt() else 0
            val result = thisVal.compareTo(thatVal)
            if (result != 0) return result
        }

        val versionResult = metadata.version.compareTo(other.metadata.version)
        if (versionResult != 0) return versionResult

        return metadata.description.compareTo(other.metadata.description)
    }
}
