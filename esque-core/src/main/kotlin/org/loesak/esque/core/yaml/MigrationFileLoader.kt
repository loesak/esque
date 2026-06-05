package org.loesak.esque.core.yaml

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.loesak.esque.core.yaml.model.MigrationFile
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

private val log = KotlinLogging.logger {}

internal class MigrationFileLoader {

  fun load(): List<MigrationFile> {
    log.info { "Loading migration files from [$MIGRATION_DEFINITION_DIRECTORY]" }

    val files =
        Files.list(
                Paths.get(
                    checkNotNull(
                            javaClass.classLoader.getResource("$MIGRATION_DEFINITION_DIRECTORY/")) {
                              "Migration directory not found on classpath"
                            }
                        .toURI()))
            .filter(Files::isRegularFile)
            .filter { FILE_NAME_PATTERN.matches(it.toFile().name) }
            .map { read(it) }
            .sorted()
            .toList()

    log.info { "Found [${files.size}] migration files" }

    return files
  }

  companion object {
    private const val MIGRATION_DEFINITION_DIRECTORY = "es.migration"
    private const val MIGRATION_DEFINITION_FILE_NAME_REGEX = "^V((\\d+\\.?)+)__(\\w+)\\.yml$"
    private val FILE_NAME_PATTERN = Regex(MIGRATION_DEFINITION_FILE_NAME_REGEX)

    private val YAML_MAPPER = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val MESSAGE_DIGEST: MessageDigest = MessageDigest.getInstance("MD5")

    private fun read(path: Path): MigrationFile {
      val filename = path.toFile().name
      log.info { "Reading contents of migration file [$filename]" }

      val match =
          FILE_NAME_PATTERN.matchEntire(filename)
              ?: throw IllegalStateException("filename does not match expected pattern")

      return try {
        MigrationFile(
            metadata =
                MigrationFile.MigrationFileMetadata(
                    filename = filename,
                    version = match.groupValues[1],
                    description = match.groupValues[3],
                    checksum = calculateChecksum(path),
                ),
            contents =
                YAML_MAPPER.readValue(
                    Files.newInputStream(path), MigrationFile.MigrationFileContents::class.java),
        )
      } catch (e: Exception) {
        throw RuntimeException("failed to read the contents of migration file [$filename]", e)
      }
    }

    private fun calculateChecksum(path: Path): Int {
      MESSAGE_DIGEST.reset()
      MESSAGE_DIGEST.update(Files.readAllBytes(path))
      return ByteBuffer.wrap(MESSAGE_DIGEST.digest()).int
    }
  }
}
