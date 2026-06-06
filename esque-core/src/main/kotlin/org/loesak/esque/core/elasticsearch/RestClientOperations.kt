package org.loesak.esque.core.elasticsearch

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.time.Instant
import org.apache.http.entity.ContentType
import org.apache.http.entity.InputStreamEntity
import org.apache.http.nio.entity.NStringEntity
import org.elasticsearch.client.Request
import org.elasticsearch.client.Response
import org.elasticsearch.client.ResponseException
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.elasticsearch.documents.MigrationLock
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord
import org.loesak.esque.core.yaml.model.MigrationFile
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private val log = KotlinLogging.logger {}

internal class RestClientOperations(
    private val client: RestClient,
    private val migrationKey: String,
) : Closeable {

  private val mapper =
      JsonMapper.builder()
          .addModule(KotlinModule.Builder().build())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
          .build()

  override fun close() {
    client.close()
  }

  fun checkMigrationIndexExists(): Boolean {
    return try {
      log.info { "Checking if migration index with name [$MIGRATION_DOCUMENT_INDEX] exists" }
      val status =
          sendRequest(Request(HTTP_METHOD_HEAD, MIGRATION_DOCUMENT_INDEX)).statusLine.statusCode ==
              200
      log.info {
        "Determined migration index with name [$MIGRATION_DOCUMENT_INDEX] ${if (status) "exists" else "does not exist"}"
      }
      status
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to check if migration index with name [$MIGRATION_DOCUMENT_INDEX] exists", e)
    }
  }

  fun createMigrationIndex() {
    try {
      log.info { "Creating migration index with name [$MIGRATION_DOCUMENT_INDEX]" }

      val request = Request(HTTP_METHOD_PUT, MIGRATION_DOCUMENT_INDEX)
      request.entity =
          InputStreamEntity(
              checkNotNull(
                  javaClass.classLoader.getResourceAsStream(
                      MIGRATION_DOCUMENT_INDEX_DEFINITION_FILE_PATH)),
              ContentType.APPLICATION_JSON,
          )

      try {
        sendRequest(request)
      } catch (e: ResponseException) {
        val content: Map<String, Any> =
            mapper.readValue(
                e.response.entity.content, object : TypeReference<Map<String, Any>>() {})
        @Suppress("UNCHECKED_CAST")
        val alreadyExists =
            (content["error"] as? Map<String, Any>)?.get("type") ==
                "resource_already_exists_exception"

        if (alreadyExists) {
          log.info {
            "Creation of migration index with name [$MIGRATION_DOCUMENT_INDEX] failed because it already exists. Likely another process got ahead of this process. Ignoring exception."
          }
        } else {
          throw e
        }
      }

      log.info { "Migration index with name [$MIGRATION_DOCUMENT_INDEX] created" }
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to create migration index with name [$MIGRATION_DOCUMENT_INDEX]", e)
    }
  }

  /*
  TODO: should differentiate between a failed call and a lock document already existing.
   A lock document already existing should throw a LockAlreadyExists exception so that the caller can handle a true connection failure appropriately
  */
  fun createLockRecord() {
    try {
      log.info { "Creating lock document for migration key [$migrationKey]" }

      val request =
          Request(
              HTTP_METHOD_PUT,
              "$MIGRATION_DOCUMENT_INDEX/_doc/$MIGRATION_LOCK_DOCUMENT_ID_PREFIX:$migrationKey")
      request.addParameter("op_type", "create")
      request.setJsonEntity(mapper.writeValueAsString(MigrationLock(Instant.now())))

      sendRequest(request)

      log.info { "Lock document for migration key [$migrationKey] created" }
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to create lock document for migration key [$migrationKey]", e)
    }
  }

  fun deleteLockRecord() {
    try {
      log.info { "Deleting lock document for key [$migrationKey]" }
      sendRequest(
          Request(
              HTTP_METHOD_DELETE,
              "$MIGRATION_DOCUMENT_INDEX/_doc/$MIGRATION_LOCK_DOCUMENT_ID_PREFIX:$migrationKey"))
      log.info { "Lock document for key [$migrationKey] deleted" }
    } catch (e: Exception) {
      throw IllegalStateException("Failed to delete lock document for key [$migrationKey]", e)
    }
  }

  fun getMigrationRecords(): List<MigrationRecord> {
    return try {
      log.info { "Getting migration records for migration key [$migrationKey]" }

      val request = Request(HTTP_METHOD_GET, "$MIGRATION_DOCUMENT_INDEX/_search")
      request.setJsonEntity(FIND_ALL_BY_KEY_QUERY.format(migrationKey))

      val response = sendRequest(request)
      val content: Map<String, Any> =
          mapper.readValue(response.entity.content, object : TypeReference<Map<String, Any>>() {})

      @Suppress("UNCHECKED_CAST")
      val records =
          extractHits(content)
              .map { mapper.convertValue(it["_source"], MigrationRecord::class.java) }
              .sortedBy { it.order }

      log.info { "Found [${records.size}] migration records" }
      records
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to get migration records for migration key [$migrationKey]", e)
    }
  }

  fun getMigrationRecordForMigrationFile(
      file: MigrationFile,
      migrationKey: String
  ): MigrationRecord? {
    return try {
      log.info {
        "Getting migration record for migration file named [${file.metadata.filename}] and migration key [$migrationKey]"
      }

      val request = Request(HTTP_METHOD_GET, "$MIGRATION_DOCUMENT_INDEX/_search")
      request.setJsonEntity(
          FIND_ONE_BY_KEY_AND_FILENAME_QUERY.format(this.migrationKey, file.metadata.filename))

      val response = sendRequest(request)
      val content: Map<String, Any> =
          mapper.readValue(response.entity.content, object : TypeReference<Map<String, Any>>() {})

      @Suppress("UNCHECKED_CAST")
      val records =
          extractHits(content).map {
            mapper.convertValue(it["_source"], MigrationRecord::class.java)
          }

      when {
        records.size > 1 ->
            throw IllegalStateException(
                "found more than one migration record for migration file named [${file.metadata.filename}] and migration key [$migrationKey]")
        records.size == 1 -> {
          log.info {
            "Found existing migration record for migration file named [${file.metadata.filename}] and migration key [$migrationKey]"
          }
          records[0]
        }
        else -> {
          log.info {
            "Did not find any existing migration record for migration file named [${file.metadata.filename}] and migration key [$migrationKey]"
          }
          null
        }
      }
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to get migration records for migration file named [${file.metadata.filename}] and migration key [$migrationKey]")
    }
  }

  fun executeMigrationDefinition(definition: MigrationFile.MigrationFileRequestDefinition) {
    try {
      log.info { "Executing migration query definition" }

      val request = Request(definition.method, definition.path)
      definition.params?.forEach { (k, v) -> request.addParameter(k, v) }

      if (!definition.body.isNullOrBlank()) {
        request.entity = NStringEntity(definition.body, ContentType.parse(definition.contentType))
      }

      sendRequest(request)

      log.info { "Migration query definition executed successfully" }
    } catch (e: Exception) {
      throw IllegalStateException("Failed to execute migration query definition", e)
    }
  }

  fun createMigrationRecord(record: MigrationRecord) {
    check(record.migrationKey == migrationKey) {
      "migration record migration key must match operational migration key"
    }

    try {
      log.info { "Creating migration record for migration definition file [${record.filename}]" }

      val request = Request(HTTP_METHOD_POST, "$MIGRATION_DOCUMENT_INDEX/_doc")
      request.addParameter("refresh", "true")
      request.setJsonEntity(mapper.writeValueAsString(record))

      sendRequest(request)

      log.info { "Migration record for migration definition file [${record.filename}] created" }
    } catch (e: Exception) {
      throw IllegalStateException(
          "Failed to create migration record for migration definition file [${record.filename}]", e)
    }
  }

  private fun sendRequest(request: Request): Response {
    log.debug { "sending request [$request]" }
    val response = client.performRequest(request)
    log.debug { "received response [$response]" }
    return response
  }

  @Suppress("UNCHECKED_CAST")
  private fun extractHits(content: Map<String, Any>): List<Map<String, Any>> {
    val hits = content["hits"] as? Map<String, Any> ?: return emptyList()
    return hits["hits"] as? List<Map<String, Any>> ?: emptyList()
  }

  companion object {
    private const val MIGRATION_DOCUMENT_INDEX = "/.esque"
    private const val MIGRATION_DOCUMENT_INDEX_DEFINITION_FILE_PATH =
        "org/loesak/esque/core/elasticsearch/esque-index-defintion.json"
    private const val MIGRATION_LOCK_DOCUMENT_ID_PREFIX = "lock"
    private const val FIND_ALL_BY_KEY_QUERY =
        """{"query":{"bool":{"filter":[{"term":{"migration.migrationKey":"%s"}}]}}}"""
    private const val FIND_ONE_BY_KEY_AND_FILENAME_QUERY =
        """{"query":{"bool":{"filter":[{"term":{"migration.migrationKey":"%s"}},{"term":{"migration.filename":"%s"}}]}}}"""

    private const val HTTP_METHOD_HEAD = "HEAD"
    private const val HTTP_METHOD_GET = "GET"
    private const val HTTP_METHOD_PUT = "PUT"
    private const val HTTP_METHOD_POST = "POST"
    private const val HTTP_METHOD_DELETE = "DELETE"
  }
}
