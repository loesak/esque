package org.loesak.esque.core.elasticsearch;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.loesak.esque.core.AbstractElasticsearchIT;
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord;
import org.loesak.esque.core.yaml.model.MigrationFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientOperationsIT extends AbstractElasticsearchIT {

    private static final String MIGRATION_KEY = "rest-ops-test";

    private RestClient client;
    private RestClientOperations operations;

    @BeforeEach
    void setUp() {
        client = createRestClient();
        operations = new RestClientOperations(client, MIGRATION_KEY);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
    }

    @Test
    void checkMigrationIndexExists_returnsFalseWhenIndexDoesNotExist() {
        assertThat(operations.checkMigrationIndexExists()).isFalse();
    }

    @Test
    void createMigrationIndex_createsIndexSuccessfully() {
        operations.createMigrationIndex();

        assertThat(operations.checkMigrationIndexExists()).isTrue();
    }

    @Test
    void createMigrationIndex_handlesAlreadyExistsGracefully() {
        operations.createMigrationIndex();
        // calling again should not throw
        operations.createMigrationIndex();

        assertThat(operations.checkMigrationIndexExists()).isTrue();
    }

    @Test
    void createAndDeleteLockRecord() {
        operations.createMigrationIndex();

        // create lock should succeed
        operations.createLockRecord();

        // delete lock should succeed
        operations.deleteLockRecord();
    }

    @Test
    void createLockRecord_throwsWhenLockAlreadyExists() {
        operations.createMigrationIndex();

        operations.createLockRecord();

        // second create should fail because op_type=create rejects duplicates
        assertThatThrownBy(() -> operations.createLockRecord())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getMigrationRecords_returnsEmptyListWhenNoneExist() {
        operations.createMigrationIndex();

        List<MigrationRecord> records = operations.getMigrationRecords();

        assertThat(records).isEmpty();
    }

    @Test
    void createMigrationRecord_andGetMigrationRecords() {
        operations.createMigrationIndex();

        Instant now = Instant.now();
        MigrationRecord record = new MigrationRecord(
                MIGRATION_KEY, 0, "V1.0.0__Test.yml", "1.0.0",
                "Test", 12345, "test-user", now, 100L);

        operations.createMigrationRecord(record);

        // need to wait for refresh since we use refresh=true on create
        List<MigrationRecord> records = operations.getMigrationRecords();

        assertThat(records).hasSize(1);
        MigrationRecord retrieved = records.get(0);
        assertThat(retrieved.migrationKey()).isEqualTo(MIGRATION_KEY);
        assertThat(retrieved.order()).isEqualTo(0);
        assertThat(retrieved.filename()).isEqualTo("V1.0.0__Test.yml");
        assertThat(retrieved.version()).isEqualTo("1.0.0");
        assertThat(retrieved.description()).isEqualTo("Test");
        assertThat(retrieved.checksum()).isEqualTo(12345);
        assertThat(retrieved.installedBy()).isEqualTo("test-user");
        assertThat(retrieved.executionTime()).isEqualTo(100L);
    }

    @Test
    void createMigrationRecord_multipleRecordsReturnedInOrder() {
        operations.createMigrationIndex();

        Instant now = Instant.now();
        operations.createMigrationRecord(new MigrationRecord(
                MIGRATION_KEY, 0, "V1.0.0__First.yml", "1.0.0",
                "First", 111, "user", now, 10L));
        operations.createMigrationRecord(new MigrationRecord(
                MIGRATION_KEY, 1, "V1.1.0__Second.yml", "1.1.0",
                "Second", 222, "user", now, 20L));
        operations.createMigrationRecord(new MigrationRecord(
                MIGRATION_KEY, 2, "V2.0.0__Third.yml", "2.0.0",
                "Third", 333, "user", now, 30L));

        List<MigrationRecord> records = operations.getMigrationRecords();

        assertThat(records).hasSize(3);
        assertThat(records.get(0).order()).isEqualTo(0);
        assertThat(records.get(1).order()).isEqualTo(1);
        assertThat(records.get(2).order()).isEqualTo(2);
    }

    @Test
    void getMigrationRecordForMigrationFile_returnsNullWhenNotFound() {
        operations.createMigrationIndex();

        MigrationFile file = new MigrationFile(
                new MigrationFile.MigrationFileMetadata("V1.0.0__Test.yml", "1.0.0", "Test", 12345),
                new MigrationFile.MigrationFileContents(List.of()));

        MigrationRecord record = operations.getMigrationRecordForMigrationFile(file, MIGRATION_KEY);

        assertThat(record).isNull();
    }

    @Test
    void getMigrationRecordForMigrationFile_returnsRecordWhenFound() {
        operations.createMigrationIndex();

        Instant now = Instant.now();
        operations.createMigrationRecord(new MigrationRecord(
                MIGRATION_KEY, 0, "V1.0.0__Test.yml", "1.0.0",
                "Test", 12345, "user", now, 50L));

        MigrationFile file = new MigrationFile(
                new MigrationFile.MigrationFileMetadata("V1.0.0__Test.yml", "1.0.0", "Test", 12345),
                new MigrationFile.MigrationFileContents(List.of()));

        MigrationRecord found = operations.getMigrationRecordForMigrationFile(file, MIGRATION_KEY);

        assertThat(found).isNotNull();
        assertThat(found.filename()).isEqualTo("V1.0.0__Test.yml");
        assertThat(found.migrationKey()).isEqualTo(MIGRATION_KEY);
    }

    @Test
    void executeMigrationDefinition_createsIndexInElasticsearch() throws IOException {
        MigrationFile.MigrationFileRequestDefinition definition =
                new MigrationFile.MigrationFileRequestDefinition(
                        "PUT", "/test-exec-index", "application/json; charset=utf-8", null, null);

        operations.executeMigrationDefinition(definition);

        // verify the index was actually created
        Response response = client.performRequest(new Request("HEAD", "/test-exec-index"));
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    }

    @Test
    void executeMigrationDefinition_withBodyAndParams() throws IOException {
        // create an index first
        operations.executeMigrationDefinition(
                new MigrationFile.MigrationFileRequestDefinition(
                        "PUT", "/test-param-index", "application/json; charset=utf-8", null, null));

        // execute a request with body (add alias)
        MigrationFile.MigrationFileRequestDefinition definition =
                new MigrationFile.MigrationFileRequestDefinition(
                        "POST", "/_aliases", "application/json; charset=utf-8", null,
                        """
                        {
                            "actions": [
                                { "add": { "index": "test-param-index", "alias": "test-param-alias" } }
                            ]
                        }
                        """);

        operations.executeMigrationDefinition(definition);

        // verify the alias exists
        Response response = client.performRequest(new Request("HEAD", "/test-param-alias"));
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    }

    @Test
    void createMigrationRecord_throwsWhenKeyMismatch() {
        MigrationRecord record = new MigrationRecord(
                "wrong-key", 0, "V1.0.0__Test.yml", "1.0.0",
                "Test", 12345, "user", Instant.now(), 100L);

        assertThatThrownBy(() -> operations.createMigrationRecord(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration record migration key must match operational migration key");
    }
}
