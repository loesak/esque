package org.loesak.esque.core.elasticsearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.loesak.esque.core.elasticsearch.documents.MigrationLock;
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord;
import org.loesak.esque.core.yaml.model.MigrationFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
TODO: change try/catch blocks from logging and rethrowing to throwing own wrapped exception
 */
@Slf4j
public class RestClientOperations {

    private static final String MIGRATION_DOCUMENT_INDEX = ".esque";
    private static final String MIGRATION_LOCK_DOCUMENT_ID_PREFIX = "lock";

    private static final String HTTP_METHOD_HEAD = "HEAD";
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_DELETE = "DELETE";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestClientOperations(RestClient restClient) {
        this.restClient = restClient;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.registerModule(new Jdk8Module());
        this.objectMapper.registerModule(new ParameterNamesModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Boolean checkMigrationIndexExists() throws Exception {
        try {
            log.info("Checking if migration index with name [{}] exists", MIGRATION_DOCUMENT_INDEX);

            Boolean status = this
                    .sendRequest(new Request(HTTP_METHOD_HEAD, MIGRATION_DOCUMENT_INDEX))
                    .getStatusLine()
                    .getStatusCode() == 200;

            log.info("Determined migration index with name [{}] {}", MIGRATION_DOCUMENT_INDEX, status ? "exists" : "does not exist");

            return status;
        } catch (Exception e) {
            log.error("Failed to check if migration index with name [{}] exists", MIGRATION_DOCUMENT_INDEX, e);
            throw e;
        }
    }

    public void createMigrationIndex() throws Exception {
        try {
            log.info("Creating migration index with name [{}]", MIGRATION_DOCUMENT_INDEX);

            Request request = new Request(HTTP_METHOD_PUT, MIGRATION_DOCUMENT_INDEX);
            // TODO: settings and mappings from file esque-index-definition.json

            this.sendRequest(request);

            log.info("Migration index with name [{}] created", MIGRATION_DOCUMENT_INDEX);
        } catch (Exception e) {
            log.error("Failed to create migration index with name [{}]", MIGRATION_DOCUMENT_INDEX, e);
            throw e;
        }
    }

    public void createLockRecord(final String migrationKey) throws Exception {
        try {
            log.info("Creating lock document for migration key [{}]", migrationKey);

            Request request = new Request(
                    HTTP_METHOD_PUT,
                    String.format(
                            "%s/_doc/%s:%s",
                            MIGRATION_DOCUMENT_INDEX,
                            MIGRATION_LOCK_DOCUMENT_ID_PREFIX,
                            migrationKey));
            request.addParameter("op_type", "create");
            request.setJsonEntity(this.objectMapper.writeValueAsString(new MigrationLock(Instant.now())));

            /*
             because of the op_type query parameter value of create, if the lock
             exists, the request will fail and an exception will be thrown
            */
            this.sendRequest(request);

            log.info("Lock document for migration key [{}] created", migrationKey);
        } catch (Exception e) {
            log.error("Failed to create lock document for migration key [{}]", migrationKey, e);
            throw e;
        }
    }

    public void deleteLockRecord(final String migrationKey) throws IOException {
        try {
            log.info("Deleting lock document for key [{}]", migrationKey);

            this.sendRequest(
                    new Request(
                            HTTP_METHOD_DELETE,
                            String.format(
                                    "%s/_doc/%s:%s",
                                    MIGRATION_DOCUMENT_INDEX,
                                    MIGRATION_LOCK_DOCUMENT_ID_PREFIX,
                                    migrationKey)));

            log.info("Lock document for key [{}] deleted", migrationKey);
        } catch (Exception e) {
            log.error("Failed to delete lock document for key [{}]", migrationKey, e);
            throw e;
        }
    }

    public List<MigrationRecord> getMigrationRecords(final String migrationKey) throws IOException {
        try {
            log.info("Getting migration records for migration key [{}]", migrationKey);

            Request request = new Request(HTTP_METHOD_GET, String.format("%s/_search", MIGRATION_DOCUMENT_INDEX));
            request.setJsonEntity(String.format(
                    // TODO: objectify this JSON?
                    "{ \"query\": { \"bool\": { \"filter\": [ { \"term\": { \"migration.migrationKey\": \"%s\" } } ] } } }",
                    migrationKey));

            Response response = this.sendRequest(request);
            Map<String, Object> content = this.objectMapper.readValue(response.getEntity().getContent(), new TypeReference<Map<String, Object>>() {});

            List<MigrationRecord> records = content.entrySet().stream()
                   .filter(entry -> entry.getKey().equals("hits"))
                   .flatMap(entry -> ((Map<String, Object>) entry.getValue()).entrySet().stream())
                   .filter(entry -> entry.getKey().equals("hits"))
                   .flatMap(entry -> ((List<Map<String, Object>>) entry.getValue()).stream())
                   .flatMap(item -> item.entrySet().stream())
                   .filter(entry -> entry.getKey().equals("_source"))
                   .map(entry -> (MigrationRecord) this.objectMapper.convertValue(entry.getValue(), new TypeReference<MigrationRecord>() {}))
                   .collect(Collectors.toUnmodifiableList());

            log.info("Found [{}] migration records", records.size());

            return records;
        } catch (Exception e) {
            log.error("Failed to get migration records for migration key [{}}", migrationKey, e);
            throw e;
        }
    }

    // TODO: iterating over the migration file queries should be done outside of this class
    public void executeMigrationFileQueries(final MigrationFile migrationFile) throws IOException {
        try {
            log.info("Executing queries defined in migration file [{}]", migrationFile.getMetadata().getFilename());

            List<MigrationFile.MigrationFileRequestDefinition> requests = migrationFile.getContents().getRequests();
            for (MigrationFile.MigrationFileRequestDefinition definition : requests) {
                final Integer position = requests.indexOf(definition);

                try {
                    log.info("Executing query in position [{}] defined in migration file [{}]", position, migrationFile.getMetadata().getFilename());

                    Request request = new Request(definition.getMethod(), definition.getPath());
                    if (definition.getParams() != null) {
                        definition.getParams().entrySet().forEach(entry -> request.addParameter(entry.getKey(), entry.getValue()));
                    }

                    if (definition.getBody() != null && !definition.getBody().trim().equals("")) {
                        request.setEntity(new NStringEntity(definition.getBody(), ContentType.parse(definition.getContentType())));
                    }

                    this.sendRequest(request);

                    log.info("Query in position [{}] defined in migration file [{}] executed successfully", position, migrationFile.getMetadata().getFilename());
                } catch (Exception e) {
                    log.error("Failed to execute query in position [{}] defined in migration file [{}]", position, migrationFile.getMetadata().getFilename(), e);
                    throw e;
                }
            }

            log.info("Execution complete for queries defined in migration file [{}]", migrationFile.getMetadata().getFilename());
        } catch (Exception e) {
            log.error("Failed to execute migration query", e);
            throw e;
        }
    }

    public void createMigrationRecord(final MigrationRecord record) throws IOException {
        try {
            log.info("Creating migration record for migration definition file [{}]", record.getFilename());

            Request request = new Request(
                    HTTP_METHOD_POST,
                    String.format(
                            "%s/_doc",
                            MIGRATION_DOCUMENT_INDEX));
            request.addParameter("refresh", "true");
            request.setJsonEntity(this.objectMapper.writeValueAsString(record));

            this.sendRequest(request);

            log.info("Migration record for migration definition file [{}] created", record.getFilename());
        } catch (Exception e) {
            log.error("Failed to creat migration record for migration definition file [{}]", record.getFilename(), e);
            throw e;
        }
    }

    private Response sendRequest(Request request) throws IOException {
        log.debug("sending request [{}]", request);

        Response response = this.restClient.performRequest(request);

        log.debug("received response [{}]", response);

        return response;
    }

}
