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

    return (0 until maxParts)
        .map { i ->
          val thisVal = thisParts.getOrElse(i) { "0" }.toInt()
          val thatVal = thatParts.getOrElse(i) { "0" }.toInt()
          thisVal.compareTo(thatVal)
        }
        .firstOrNull { it != 0 }
        ?: metadata.version.compareTo(other.metadata.version).takeIf { it != 0 }
        ?: metadata.description.compareTo(other.metadata.description)
  }
}
