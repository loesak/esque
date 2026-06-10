package org.loesak.esque.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.concurrent.ElasticsearchDocumentLock
import org.loesak.esque.core.elasticsearch.RestClientOperations
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord
import org.loesak.esque.core.yaml.MigrationFileLoader
import org.loesak.esque.core.yaml.MigrationTemplateResolver
import org.loesak.esque.core.yaml.model.MigrationFile

private val log = KotlinLogging.logger {}

class Esque
@JvmOverloads
constructor(
    client: RestClient,
    private val migrationKey: String,
    private val migrationUser: String? = null,
    properties: Map<String, String> = emptyMap(),
) : Closeable {

  private val migrationLoader = MigrationFileLoader(MigrationTemplateResolver(properties))
  private val operations = RestClientOperations(client, migrationKey)
  private val lock: Lock = ElasticsearchDocumentLock(operations)

  override fun close() {
    try {
      lock.unlock()
    } catch (e: IllegalMonitorStateException) {
      // intentionally left blank. means lock is already unlocked
    } catch (e: Exception) {
      log.warn(e) {
        "failed to release a execution lock. you may need to manually delete the lock document yourself"
      }
    }

    try {
      operations.close()
    } catch (e: Exception) {
      log.warn(e) { "failed to close rest client. this is likely not an issue" }
    }
  }

  fun execute() {
    log.info { "Starting esque execution" }
    try {
      initialize()

      val files = migrationLoader.load()

      val history = operations.getMigrationRecords()

      verifyStateIntegrity(files, history)
      runMigrations(files)

      log.info { "Completed esque execution" }
    } catch (e: Exception) {
      throw RuntimeException("Failed to run esque execution", e)
    }
  }

  private fun initialize() {
    log.info { "Initializing esque as needed" }
    if (!operations.checkMigrationIndexExists()) {
      operations.createMigrationIndex()
    }
  }

  private fun verifyStateIntegrity(files: List<MigrationFile>, history: List<MigrationRecord>) {
    log.info { "Verifying integrity of migration state as compared to found migration files" }

    check(history.size <= files.size) {
      "the migration records are showing more migrations than the local system defines. did you refactor your files or use an incorrect migration key?"
    }

    check(history.isEmpty() || history.size == history.last().order + 1) {
      "the migration records seem to be corrupt as some records appear to be missing."
    }

    history.forEach { record -> verifyRecordIntegrity(record, files) }

    log.info { "Integrity checks passed" }
  }

  private fun verifyRecordIntegrity(record: MigrationRecord, files: List<MigrationFile>) {
    val companion =
        files.firstOrNull { it.metadata.filename == record.filename }
            ?: error(
                "could not find migration file matching migration history record by filename [${record.filename}]")

    check(
        record.order == files.indexOf(companion) &&
            record.version == companion.metadata.version &&
            record.description == companion.metadata.description &&
            record.checksum == companion.metadata.checksum &&
            record.migrationKey == migrationKey) {
          "could not verify integrity of migration history record for filename [${record.filename}]. did you refactor your migration scripts after a previous execution?"
        }
  }

  private fun runMigrations(files: List<MigrationFile>) {
    try {
      files.forEach { file ->
        try {
          log.info { "Attempting to acquire lock for execution" }

          if (lock.tryLock(LOCK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            log.info {
              "Lock acquired. Executing queries defined in migration file [${file.metadata.filename}]"
            }

            if (operations.getMigrationRecordForMigrationFile(file, migrationKey) != null) {
              log.info {
                "Migration for migration file [${file.metadata.filename}] and migration key [$migrationKey] appears to already have been executed. Skipping"
              }
            } else {
              val start = Instant.now()
              runMigrationForFile(file)
              val end = Instant.now()
              val duration = Duration.between(start, end).toMillis()

              log.info {
                "Execution complete for migration file [${file.metadata.filename}]. Took [$duration] milliseconds"
              }

              operations.createMigrationRecord(
                  MigrationRecord(
                      migrationKey = migrationKey,
                      order = files.indexOf(file),
                      filename = file.metadata.filename,
                      version = file.metadata.version,
                      description = file.metadata.description,
                      checksum = file.metadata.checksum,
                      installedBy = migrationUser,
                      installedOn = end,
                      executionTime = duration,
                  ))
            }
          } else {
            // TODO: this could happen for long running queries. need to look for something a bit
            // smarter or allow to be configurable
            log.error {
              "Failed to acquire lock in the allotted time period. Did a lock not get cleared as part of a previous execution?"
            }
            error("failed to acquire lock")
          }
        } catch (e: Exception) {
          throw RuntimeException(
              "Failed to execute queries in migration file [${file.metadata.filename}]", e)
          // TODO: should we write a "FAILED" migration record?
        } finally {
          log.info { "Releasing execution lock" }
          lock.unlock()
        }
      }
    } catch (e: Exception) {
      throw IllegalStateException("failed to run migrations", e)
    }
  }

  private fun runMigrationForFile(file: MigrationFile) {
    log.info { "Executing queries defined in migration file [${file.metadata.filename}]" }

    val requests = file.contents.requests
    requests.forEachIndexed { position, definition ->
      try {
        log.info {
          "Executing query in position [$position] defined in migration file [${file.metadata.filename}]"
        }
        operations.executeMigrationDefinition(definition)
        log.info {
          "Query in position [$position] defined in migration file [${file.metadata.filename}] executed successfully"
        }
      } catch (e: Exception) {
        throw IllegalStateException(
            "Failed to execute query in position [$position] defined in migration file [${file.metadata.filename}]",
            e)
      }
    }

    log.info {
      "Execution complete for queries defined in migration file [${file.metadata.filename}]"
    }
  }

  companion object {
    private const val LOCK_TIMEOUT_MINUTES = 5L
  }
}
