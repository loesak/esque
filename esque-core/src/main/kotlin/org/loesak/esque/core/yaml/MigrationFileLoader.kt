package org.loesak.esque.core.yaml

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.loesak.esque.core.yaml.model.MigrationFile
import tools.jackson.databind.SerializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

private val log = KotlinLogging.logger {}

internal class MigrationFileLoader(
    private val templateResolver: MigrationTemplateResolver = MigrationTemplateResolver(emptyMap()),
) {

  fun load(): List<MigrationFile> {
    log.info { "Loading migration files from [$MIGRATION_DEFINITION_DIRECTORY]" }

    val rawFiles =
        Files.list(
                Paths.get(
                    checkNotNull(
                            javaClass.classLoader.getResource("$MIGRATION_DEFINITION_DIRECTORY/")) {
                              "Migration directory not found on classpath"
                            }
                        .toURI()))
            .filter(Files::isRegularFile)
            .filter { FILE_NAME_PATTERN.matches(it.toFile().name) }
            .map { readRaw(it) }
            .sorted()
            .toList()

    log.info { "Found [${rawFiles.size}] migration files" }

    templateResolver.validate(rawFiles)

    return rawFiles.map { file ->
      val resolvedContents = templateResolver.resolveContents(file.contents)
      file.copy(
          metadata = file.metadata.copy(checksum = calculateChecksum(resolvedContents)),
          contents = resolvedContents,
      )
    }
  }

  companion object {
    private const val MIGRATION_DEFINITION_DIRECTORY = "es.migration"
    private const val MIGRATION_DEFINITION_FILE_NAME_REGEX = "^V((\\d+\\.?)+)__(\\w+)\\.yml$"
    private val FILE_NAME_PATTERN = Regex(MIGRATION_DEFINITION_FILE_NAME_REGEX)

    private val YAML_MAPPER = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val YAML_MAPPER_SORTED =
        YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build()
    private val MESSAGE_DIGEST: MessageDigest = MessageDigest.getInstance("MD5")

    private fun readRaw(path: Path): MigrationFile {
      val filename = path.toFile().name
      log.info { "Reading contents of migration file [$filename]" }

      val match =
          FILE_NAME_PATTERN.matchEntire(filename)
              ?: error("filename does not match expected pattern")

      return try {
        MigrationFile(
            metadata =
                MigrationFile.MigrationFileMetadata(
                    filename = filename,
                    version = match.groupValues[1],
                    description = match.groupValues[3],
                    checksum = 0,
                ),
            contents =
                YAML_MAPPER.readValue(
                    Files.newInputStream(path),
                    MigrationFile.MigrationFileContents::class.java,
                ),
        )
      } catch (e: Exception) {
        throw RuntimeException("failed to read the contents of migration file [$filename]", e)
      }
    }

    internal fun calculateChecksum(contents: MigrationFile.MigrationFileContents): Int {
      MESSAGE_DIGEST.reset()
      MESSAGE_DIGEST.update(YAML_MAPPER_SORTED.writeValueAsBytes(contents))
      return ByteBuffer.wrap(MESSAGE_DIGEST.digest()).int
    }
  }
}
