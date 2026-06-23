package org.loesak.esque.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.elasticsearch.client.Request
import org.junit.jupiter.api.Test
import org.loesak.esque.core.elasticsearch.RestClientOperations

class EsqueIT : AbstractElasticsearchIT() {

  private val templateProperties = mapOf("templatedIndexName" to "test-index-v4")

  @Test
  fun execute_createsEsqueIndex() {
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = "create-index-test"), templateProperties)
          .execute()
      val response = client.performRequest(Request("HEAD", "/.esque"))
      assertThat(response.statusLine.statusCode).isEqualTo(200)
    }
  }

  @Test
  fun execute_runsAllMigrations() {
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = "run-all-test"), templateProperties).execute()
      assertIndexExists(client, "/test-index-v1")
      assertIndexExists(client, "/test-index-v2")
      assertIndexExists(client, "/test-index-v3")
      assertIndexExists(client, "/test-index-v4")
    }
  }

  @Test
  fun execute_recordsMigrationHistory() {
    val migrationKey = "history-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()

      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(records).hasSize(4)

      assertThat(records[0].order).isEqualTo(0)
      assertThat(records[0].filename).isEqualTo("V1.0.0__CreateTestIndex.yml")
      assertThat(records[0].version).isEqualTo("1.0.0")
      assertThat(records[0].description).isEqualTo("CreateTestIndex")

      assertThat(records[1].order).isEqualTo(1)
      assertThat(records[1].filename).isEqualTo("V1.1.0__CreateSecondIndex.yml")

      assertThat(records[2].order).isEqualTo(2)
      assertThat(records[2].filename).isEqualTo("V2.0.0__CreateThirdIndex.yml")

      assertThat(records[3].order).isEqualTo(3)
      assertThat(records[3].filename).isEqualTo("V3.0.0__CreateTemplatedIndex.yml")

      for (record in records) {
        assertThat(record.migrationKey).isEqualTo(migrationKey)
        assertThat(record.checksum).isNotNull()
        assertThat(record.installedOn).isNotNull()
        assertThat(record.executionTime).isNotNull()
        assertThat(record.executionTime).isGreaterThanOrEqualTo(0L)
      }
    }
  }

  @Test
  fun execute_isIdempotent() {
    val migrationKey = "idempotent-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val firstRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(firstRun).hasSize(4)

      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val secondRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(secondRun).hasSize(4)

      for (i in 0..3) {
        assertThat(secondRun[i].filename).isEqualTo(firstRun[i].filename)
        assertThat(secondRun[i].checksum).isEqualTo(firstRun[i].checksum)
        assertThat(secondRun[i].installedOn).isEqualTo(firstRun[i].installedOn)
      }
    }
  }

  @Test
  fun execute_differentKeysAreIndependent() {
    createRestClient().use { client ->
      val key1 = "independent-key-1"
      val key2 = "independent-key-2"

      Esque(client, EsqueConfiguration(migrationKey = key1), templateProperties).execute()

      val records1 = RestClientOperations(client, key1).getMigrationRecords()
      assertThat(records1).hasSize(4)
      records1.forEach { assertThat(it.migrationKey).isEqualTo(key1) }

      val records2 = RestClientOperations(client, key2).getMigrationRecords()
      assertThat(records2).isEmpty()
    }
  }

  @Test
  fun execute_recordsUserWhenProvided() {
    val migrationKey = "user-test"
    val user = "integration-test-user"
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = migrationKey, migrationUser = user),
              templateProperties,
          )
          .execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      for (record in records) {
        assertThat(record.installedBy).isEqualTo(user)
      }
    }
  }

  @Test
  fun execute_recordsNullUserWhenNotProvided() {
    val migrationKey = "no-user-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      for (record in records) {
        assertThat(record.installedBy).isNull()
      }
    }
  }

  @Test
  fun execute_withPropertiesAndNoPlaceholdersInFiles_succeedsAndIgnoresExtraProperties() {
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = "extra-properties-test"),
              mapOf("unused" to "value", "templatedIndexName" to "test-index-v4"),
          )
          .execute()
      assertIndexExists(client, "/test-index-v1")
    }
  }

  @Test
  fun execute_withTemplateVariables_substitutesValuesInPathAndCreatesCorrectIndex() {
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = "templating-substitution-test"),
              templateProperties,
          )
          .execute()
      assertIndexExists(client, "/test-index-v4")
    }
  }

  @Test
  fun execute_withMissingTemplateVariable_throwsBeforeAnyMigrationRuns() {
    createRestClient().use { client ->
      assertThatThrownBy {
            Esque(client, EsqueConfiguration(migrationKey = "missing-var-test")).execute()
          }
          .rootCause()
          .hasMessageContaining("templatedIndexName")

      val records = RestClientOperations(client, "missing-var-test").getMigrationRecords()
      assertThat(records).isEmpty()
    }
  }

  private fun assertIndexExists(client: org.elasticsearch.client.RestClient, indexPath: String) {
    val response = client.performRequest(Request("HEAD", indexPath))
    assertThat(response.statusLine.statusCode).isEqualTo(200)
  }
}
