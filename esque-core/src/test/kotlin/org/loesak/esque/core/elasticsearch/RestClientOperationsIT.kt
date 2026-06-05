package org.loesak.esque.core.elasticsearch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.elasticsearch.client.Request
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.loesak.esque.core.AbstractElasticsearchIT
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord
import org.loesak.esque.core.yaml.model.MigrationFile
import java.time.Instant

class RestClientOperationsIT : AbstractElasticsearchIT() {
    private lateinit var client: RestClient
    private lateinit var operations: RestClientOperations

    companion object {
        private const val MIGRATION_KEY = "rest-ops-test"
    }

    @BeforeEach
    fun setUp() {
        client = createRestClient()
        operations = RestClientOperations(client, MIGRATION_KEY)
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    @Test
    fun checkMigrationIndexExists_returnsFalseWhenIndexDoesNotExist() {
        assertThat(operations.checkMigrationIndexExists()).isFalse()
    }

    @Test
    fun createMigrationIndex_createsIndexSuccessfully() {
        operations.createMigrationIndex()
        assertThat(operations.checkMigrationIndexExists()).isTrue()
    }

    @Test
    fun createMigrationIndex_handlesAlreadyExistsGracefully() {
        operations.createMigrationIndex()
        operations.createMigrationIndex()
        assertThat(operations.checkMigrationIndexExists()).isTrue()
    }

    @Test
    fun createAndDeleteLockRecord() {
        operations.createMigrationIndex()
        operations.createLockRecord()
        operations.deleteLockRecord()
    }

    @Test
    fun createLockRecord_throwsWhenLockAlreadyExists() {
        operations.createMigrationIndex()
        operations.createLockRecord()
        assertThatThrownBy { operations.createLockRecord() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun getMigrationRecords_returnsEmptyListWhenNoneExist() {
        operations.createMigrationIndex()
        assertThat(operations.getMigrationRecords()).isEmpty()
    }

    @Test
    fun createMigrationRecord_andGetMigrationRecords() {
        operations.createMigrationIndex()

        val now = Instant.now()
        val record = MigrationRecord(MIGRATION_KEY, 0, "V1.0.0__Test.yml", "1.0.0", "Test", 12345, "test-user", now, 100L)
        operations.createMigrationRecord(record)

        val records = operations.getMigrationRecords()
        assertThat(records).hasSize(1)

        val retrieved = records[0]
        assertThat(retrieved.migrationKey).isEqualTo(MIGRATION_KEY)
        assertThat(retrieved.order).isEqualTo(0)
        assertThat(retrieved.filename).isEqualTo("V1.0.0__Test.yml")
        assertThat(retrieved.version).isEqualTo("1.0.0")
        assertThat(retrieved.description).isEqualTo("Test")
        assertThat(retrieved.checksum).isEqualTo(12345)
        assertThat(retrieved.installedBy).isEqualTo("test-user")
        assertThat(retrieved.executionTime).isEqualTo(100L)
    }

    @Test
    fun createMigrationRecord_multipleRecordsReturnedInOrder() {
        operations.createMigrationIndex()

        val now = Instant.now()
        operations.createMigrationRecord(MigrationRecord(MIGRATION_KEY, 0, "V1.0.0__First.yml", "1.0.0", "First", 111, "user", now, 10L))
        operations.createMigrationRecord(MigrationRecord(MIGRATION_KEY, 1, "V1.1.0__Second.yml", "1.1.0", "Second", 222, "user", now, 20L))
        operations.createMigrationRecord(MigrationRecord(MIGRATION_KEY, 2, "V2.0.0__Third.yml", "2.0.0", "Third", 333, "user", now, 30L))

        val records = operations.getMigrationRecords()
        assertThat(records).hasSize(3)
        assertThat(records[0].order).isEqualTo(0)
        assertThat(records[1].order).isEqualTo(1)
        assertThat(records[2].order).isEqualTo(2)
    }

    @Test
    fun getMigrationRecordForMigrationFile_returnsNullWhenNotFound() {
        operations.createMigrationIndex()
        val file =
            MigrationFile(
                MigrationFile.MigrationFileMetadata("V1.0.0__Test.yml", "1.0.0", "Test", 12345),
                MigrationFile.MigrationFileContents(emptyList()),
            )
        assertThat(operations.getMigrationRecordForMigrationFile(file, MIGRATION_KEY)).isNull()
    }

    @Test
    fun getMigrationRecordForMigrationFile_returnsRecordWhenFound() {
        operations.createMigrationIndex()

        val now = Instant.now()
        operations.createMigrationRecord(MigrationRecord(MIGRATION_KEY, 0, "V1.0.0__Test.yml", "1.0.0", "Test", 12345, "user", now, 50L))

        val file =
            MigrationFile(
                MigrationFile.MigrationFileMetadata("V1.0.0__Test.yml", "1.0.0", "Test", 12345),
                MigrationFile.MigrationFileContents(emptyList()),
            )

        val found = operations.getMigrationRecordForMigrationFile(file, MIGRATION_KEY)
        assertThat(found).isNotNull()
        assertThat(found!!.filename).isEqualTo("V1.0.0__Test.yml")
        assertThat(found.migrationKey).isEqualTo(MIGRATION_KEY)
    }

    @Test
    fun executeMigrationDefinition_createsIndexInElasticsearch() {
        val definition = MigrationFile.MigrationFileRequestDefinition("PUT", "/test-exec-index", "application/json; charset=utf-8")
        operations.executeMigrationDefinition(definition)
        val response = client.performRequest(Request("HEAD", "/test-exec-index"))
        assertThat(response.statusLine.statusCode).isEqualTo(200)
    }

    @Test
    fun executeMigrationDefinition_withBodyAndParams() {
        operations.executeMigrationDefinition(
            MigrationFile.MigrationFileRequestDefinition("PUT", "/test-param-index", "application/json; charset=utf-8"),
        )

        val definition =
            MigrationFile.MigrationFileRequestDefinition(
                "POST",
                "/_aliases",
                "application/json; charset=utf-8",
                null,
                """
                {
                    "actions": [
                        { "add": { "index": "test-param-index", "alias": "test-param-alias" } }
                    ]
                }
                """.trimIndent(),
            )

        operations.executeMigrationDefinition(definition)

        val response = client.performRequest(Request("HEAD", "/test-param-alias"))
        assertThat(response.statusLine.statusCode).isEqualTo(200)
    }

    @Test
    fun createMigrationRecord_throwsWhenKeyMismatch() {
        val record = MigrationRecord("wrong-key", 0, "V1.0.0__Test.yml", "1.0.0", "Test", 12345, "user", Instant.now(), 100L)
        assertThatThrownBy { operations.createMigrationRecord(record) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("migration record migration key must match operational migration key")
    }
}
