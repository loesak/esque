package org.loesak.esque.core.yaml.model

internal data class MigrationFile(
    val metadata: MigrationFileMetadata,
    val contents: MigrationFileContents,
) : Comparable<MigrationFile> {

    data class MigrationFileMetadata(
        val filename: String,
        val version: String,
        val description: String,
        val checksum: Int,
    )

    data class MigrationFileContents(
        val requests: List<MigrationFileRequestDefinition>,
    )

    data class MigrationFileRequestDefinition(
        val method: String,
        val path: String,
        val contentType: String? = null,
        val params: Map<String, String>? = null,
        val body: String? = null,
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
