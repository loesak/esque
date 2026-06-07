package org.loesak.esque.core

import org.assertj.core.api.Assertions.assertThat
import org.elasticsearch.client.Request
import org.junit.jupiter.api.Test
import org.loesak.esque.core.elasticsearch.RestClientOperations

class EsqueIT : AbstractElasticsearchIT() {

  @Test
  fun execute_createsEsqueIndex() {
    createRestClient().use { client ->
      Esque(client, "create-index-test").execute()
      val response = client.performRequest(Request("HEAD", "/.esque"))
      assertThat(response.statusLine.statusCode).isEqualTo(200)
    }
  }

  @Test
  fun execute_runsAllMigrations() {
    createRestClient().use { client ->
      Esque(client, "run-all-test").execute()
      assertIndexExists(client, "/test-index-v1")
      assertIndexExists(client, "/test-index-v2")
      assertIndexExists(client, "/test-index-v3")
    }
  }

  @Test
  fun execute_recordsMigrationHistory() {
    val migrationKey = "history-test"
    createRestClient().use { client ->
      Esque(client, migrationKey).execute()

      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(records).hasSize(3)

      assertThat(records[0].order).isEqualTo(0)
      assertThat(records[0].filename).isEqualTo("V1.0.0__CreateTestIndex.yml")
      assertThat(records[0].version).isEqualTo("1.0.0")
      assertThat(records[0].description).isEqualTo("CreateTestIndex")

      assertThat(records[1].order).isEqualTo(1)
      assertThat(records[1].filename).isEqualTo("V1.1.0__CreateSecondIndex.yml")

      assertThat(records[2].order).isEqualTo(2)
      assertThat(records[2].filename).isEqualTo("V2.0.0__CreateThirdIndex.yml")

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
      Esque(client, migrationKey).execute()
      val firstRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(firstRun).hasSize(3)

      Esque(client, migrationKey).execute()
      val secondRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(secondRun).hasSize(3)

      for (i in 0..2) {
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

      Esque(client, key1).execute()

      val records1 = RestClientOperations(client, key1).getMigrationRecords()
      assertThat(records1).hasSize(3)
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
      Esque(client, migrationKey, user).execute()
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
      Esque(client, migrationKey).execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      for (record in records) {
        assertThat(record.installedBy).isNull()
      }
    }
  }

  @Test
  fun execute_withPropertiesAndNoPlaceholdersInFiles_succeedsAndIgnoresExtraProperties() {
    createRestClient().use { client ->
      Esque(client, "extra-properties-test", null, mapOf("unused" to "value")).execute()
      assertIndexExists(client, "/test-index-v1")
    }
  }

  @Test
  fun execute_withEmptyPropertiesAndNoPlaceholdersInFiles_behavesIdenticallyToNoPropertiesArg() {
    val migrationKey = "empty-properties-test"
    createRestClient().use { client ->
      Esque(client, migrationKey, null, emptyMap()).execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(records).hasSize(3)
    }
  }

  private fun assertIndexExists(client: org.elasticsearch.client.RestClient, indexPath: String) {
    val response = client.performRequest(Request("HEAD", indexPath))
    assertThat(response.statusLine.statusCode).isEqualTo(200)
  }
}
