package org.loesak.esque.core;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.loesak.esque.core.elasticsearch.RestClientOperations;
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsqueIT extends AbstractElasticsearchIT {

    @Test
    void execute_createsEsqueIndex() throws IOException {
        try (RestClient client = createRestClient()) {
            Esque esque = new Esque(client, "create-index-test");
            esque.execute();

            Response response = client.performRequest(new Request("HEAD", "/.esque"));
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        }
    }

    @Test
    void execute_runsAllMigrations() throws IOException {
        try (RestClient client = createRestClient()) {
            Esque esque = new Esque(client, "run-all-test");
            esque.execute();

            // verify all three test indices were created
            assertIndexExists(client, "/test-index-v1");
            assertIndexExists(client, "/test-index-v2");
            assertIndexExists(client, "/test-index-v3");
        }
    }

    @Test
    void execute_recordsMigrationHistory() throws IOException {
        String migrationKey = "history-test";
        try (RestClient client = createRestClient()) {
            Esque esque = new Esque(client, migrationKey);
            esque.execute();

            RestClientOperations ops = new RestClientOperations(client, migrationKey);
            List<MigrationRecord> records = ops.getMigrationRecords();

            assertThat(records).hasSize(3);

            // verify order
            assertThat(records.get(0).order()).isEqualTo(0);
            assertThat(records.get(0).filename()).isEqualTo("V1.0.0__CreateTestIndex.yml");
            assertThat(records.get(0).version()).isEqualTo("1.0.0");
            assertThat(records.get(0).description()).isEqualTo("CreateTestIndex");

            assertThat(records.get(1).order()).isEqualTo(1);
            assertThat(records.get(1).filename()).isEqualTo("V1.1.0__CreateSecondIndex.yml");

            assertThat(records.get(2).order()).isEqualTo(2);
            assertThat(records.get(2).filename()).isEqualTo("V2.0.0__CreateThirdIndex.yml");

            // verify metadata is populated
            for (MigrationRecord record : records) {
                assertThat(record.migrationKey()).isEqualTo(migrationKey);
                assertThat(record.checksum()).isNotNull();
                assertThat(record.installedOn()).isNotNull();
                assertThat(record.executionTime()).isNotNull();
                assertThat(record.executionTime()).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    @Test
    void execute_isIdempotent() throws IOException {
        String migrationKey = "idempotent-test";
        try (RestClient client = createRestClient()) {
            // first execution
            new Esque(client, migrationKey).execute();

            RestClientOperations ops = new RestClientOperations(client, migrationKey);
            List<MigrationRecord> firstRun = ops.getMigrationRecords();
            assertThat(firstRun).hasSize(3);

            // second execution should not fail and should not create duplicate records
            new Esque(client, migrationKey).execute();

            List<MigrationRecord> secondRun = ops.getMigrationRecords();
            assertThat(secondRun).hasSize(3);

            // records should be identical
            for (int i = 0; i < 3; i++) {
                assertThat(secondRun.get(i).filename()).isEqualTo(firstRun.get(i).filename());
                assertThat(secondRun.get(i).checksum()).isEqualTo(firstRun.get(i).checksum());
                assertThat(secondRun.get(i).installedOn()).isEqualTo(firstRun.get(i).installedOn());
            }
        }
    }

    @Test
    void execute_differentKeysAreIndependent() throws IOException {
        try (RestClient client = createRestClient()) {
            String key1 = "independent-key-1";
            String key2 = "independent-key-2";

            // execute migrations under key1
            new Esque(client, key1).execute();

            RestClientOperations ops1 = new RestClientOperations(client, key1);
            RestClientOperations ops2 = new RestClientOperations(client, key2);

            // key1 should have 3 migration records
            List<MigrationRecord> records1 = ops1.getMigrationRecords();
            assertThat(records1).hasSize(3);
            records1.forEach(r -> assertThat(r.migrationKey()).isEqualTo(key1));

            // key2 should have no records - migrations are scoped to the key
            List<MigrationRecord> records2 = ops2.getMigrationRecords();
            assertThat(records2).isEmpty();
        }
    }

    @Test
    void execute_recordsUserWhenProvided() throws IOException {
        String migrationKey = "user-test";
        String user = "integration-test-user";
        try (RestClient client = createRestClient()) {
            Esque esque = new Esque(client, migrationKey, user);
            esque.execute();

            RestClientOperations ops = new RestClientOperations(client, migrationKey);
            List<MigrationRecord> records = ops.getMigrationRecords();

            for (MigrationRecord record : records) {
                assertThat(record.installedBy()).isEqualTo(user);
            }
        }
    }

    @Test
    void execute_recordsNullUserWhenNotProvided() throws IOException {
        String migrationKey = "no-user-test";
        try (RestClient client = createRestClient()) {
            Esque esque = new Esque(client, migrationKey);
            esque.execute();

            RestClientOperations ops = new RestClientOperations(client, migrationKey);
            List<MigrationRecord> records = ops.getMigrationRecords();

            for (MigrationRecord record : records) {
                assertThat(record.installedBy()).isNull();
            }
        }
    }

    private void assertIndexExists(RestClient client, String indexPath) throws IOException {
        Response response = client.performRequest(new Request("HEAD", indexPath));
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
    }
}
