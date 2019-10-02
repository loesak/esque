package org.loesoft.esque.core.elasticsearch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.loesoft.esque.core.elasticsearch.documents.MigrationLock;
import org.loesoft.esque.core.elasticsearch.documents.MigrationRecord;
import org.loesoft.esque.core.yaml.model.MigrationFile;

import java.beans.ConstructorProperties;
import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class RestClientOperations implements Closeable {

    private static final String MIGRATION_DOCUMENT_INDEX = "/.esque";
    private static final String MIGRATION_DOCUMENT_INDEX_DEFINITION_FILE_PATH = "org/loesoft/esque/core/elasticsearch/esque-index-defintion.json";
    private static final String MIGRATION_LOCK_DOCUMENT_ID_PREFIX = "lock";
    private static final String MIGRATION_RECORD_SEARCH_QUERY_TEMPLATE_FIND_ALL_BY_MIGRATION_KEY = "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"migration.migrationKey\":\"%s\"}}]}}}";
    private static final String MIGRATION_RECORD_SEARCH_QUERY_TEMPLATE_FIND_ONE_BY_MIGRATION_KEY_AND_MIGRATION_FILENAME = "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"migration.migrationKey\":\"%s\"}},{\"term\":{\"migration.filename\":\"%s\"}}]}}}";

    private static final String HTTP_METHOD_HEAD = "HEAD";
    private static final String HTTP_METHOD_GET = "GET";
    private static final String HTTP_METHOD_PUT = "PUT";
    private static final String HTTP_METHOD_POST = "POST";
    private static final String HTTP_METHOD_DELETE = "DELETE";

    private final RestClient client;
    private final String migrationKey;
    private final ObjectMapper mapper;

    @ConstructorProperties({"client", "migrationKey"})
    public RestClientOperations(final RestClient client, final String migrationKey) {
        this.client = client;
        this.migrationKey = migrationKey;

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.registerModule(new Jdk8Module());
        this.mapper.registerModule(new ParameterNamesModule());
        this.mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public void close() throws IOException {
        this.client.close();
    }

    public Boolean checkMigrationIndexExists() {
        try {
            log.info("Checking if migration index with name [{}] exists", MIGRATION_DOCUMENT_INDEX);

            Boolean status = this
                    .sendRequest(new Request(HTTP_METHOD_HEAD, MIGRATION_DOCUMENT_INDEX))
                    .getStatusLine()
                    .getStatusCode() == 200;

            log.info("Determined migration index with name [{}] {}", MIGRATION_DOCUMENT_INDEX, status ? "exists" : "does not exist");

            return status;
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to check if migration index with name [%s] exists", MIGRATION_DOCUMENT_INDEX), e);
        }
    }

    public void createMigrationIndex() {
        try {
            log.info("Creating migration index with name [{}]", MIGRATION_DOCUMENT_INDEX);

            Request request = new Request(HTTP_METHOD_PUT, MIGRATION_DOCUMENT_INDEX);
            request.setEntity(new InputStreamEntity(
                    Objects.requireNonNull(this.getClass().getClassLoader().getResourceAsStream(MIGRATION_DOCUMENT_INDEX_DEFINITION_FILE_PATH)),
                    ContentType.APPLICATION_JSON));

            try {
                this.sendRequest(request);
            } catch (ResponseException e) {
                Map<String, Object> content = this.mapper.readValue(((ResponseException) e).getResponse().getEntity().getContent(), new TypeReference<Map<String,Object>>(){});
                Boolean alreadyExists = content.entrySet().stream()
                       .filter(entry -> entry.getKey().equals("error"))
                       .flatMap(entry -> ((Map<String, Object>) entry.getValue()).entrySet().stream())
                       .filter(entry -> entry.getKey().equals("type"))
                       .map(entry -> entry.getValue().equals("resource_already_exists_exception"))
                       .findFirst()
                       .orElse(false);

                if (alreadyExists) {
                    log.info("Creation of migration index with name [{}] failed because it already exists. Likely another process got ahead of this process. Ignoring exception.", MIGRATION_DOCUMENT_INDEX);
                } else {
                    throw e;
                }
            }

            log.info("Migration index with name [{}] created", MIGRATION_DOCUMENT_INDEX);
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to create migration index with name [%s]", MIGRATION_DOCUMENT_INDEX), e);
        }
    }

    /*
    TODO: should differentiate between a failed call and a lock document already existing.
     A lock document already existing should throw a LockAlreadyExists exception so that the caller can handle a true connection failure appropriately
     */
    public void createLockRecord() {
        try {
            log.info("Creating lock document for migration key [{}]", this.migrationKey);

            Request request = new Request(
                    HTTP_METHOD_PUT,
                    String.format(
                            "%s/_doc/%s:%s",
                            MIGRATION_DOCUMENT_INDEX,
                            MIGRATION_LOCK_DOCUMENT_ID_PREFIX,
                            this.migrationKey));
            request.addParameter("op_type", "create");
            request.setJsonEntity(this.mapper.writeValueAsString(new MigrationLock(Instant.now())));

            /*
             because of the op_type query parameter value of create, if the lock
             exists, the request will fail and an exception will be thrown
            */
            this.sendRequest(request);

            log.info("Lock document for migration key [{}] created", this.migrationKey);
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to create lock document for migration key [%s]", this.migrationKey), e);
        }
    }

    public void deleteLockRecord() {
        try {
            log.info("Deleting lock document for key [{}]", this.migrationKey);

            this.sendRequest(
                    new Request(
                            HTTP_METHOD_DELETE,
                            String.format(
                                    "%s/_doc/%s:%s",
                                    MIGRATION_DOCUMENT_INDEX,
                                    MIGRATION_LOCK_DOCUMENT_ID_PREFIX,
                                    this.migrationKey)));

            log.info("Lock document for key [{}] deleted", this.migrationKey);
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to delete lock document for key [%s]", this.migrationKey), e);
        }
    }

    public List<MigrationRecord> getMigrationRecords() {
        try {
            log.info("Getting migration records for migration key [{}]", this.migrationKey);

            Request request = new Request(HTTP_METHOD_GET, String.format("%s/_search", MIGRATION_DOCUMENT_INDEX));
            request.setJsonEntity(String.format(
                    MIGRATION_RECORD_SEARCH_QUERY_TEMPLATE_FIND_ALL_BY_MIGRATION_KEY,
                    this.migrationKey));

            Response response = this.sendRequest(request);
            Map<String, Object> content = this.mapper.readValue(response.getEntity().getContent(), new TypeReference<Map<String, Object>>() {});

            List<MigrationRecord> records = content.entrySet().stream()
                                                   .filter(entry -> entry.getKey().equals("hits"))
                                                   .flatMap(entry -> ((Map<String, Object>) entry.getValue()).entrySet().stream())
                                                   .filter(entry -> entry.getKey().equals("hits"))
                                                   .flatMap(entry -> ((List<Map<String, Object>>) entry.getValue()).stream())
                                                   .flatMap(item -> item.entrySet().stream())
                                                   .filter(entry -> entry.getKey().equals("_source"))
                                                   .map(entry -> (MigrationRecord) this.mapper.convertValue(entry.getValue(), new TypeReference<MigrationRecord>() {}))
                                                   .sorted(Comparator.comparing(MigrationRecord::getOrder))
                                                   .collect(Collectors.toUnmodifiableList());

            log.info("Found [{}] migration records", records.size());

            return records;
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to get migration records for migration key [%s]", this.migrationKey), e);
        }
    }

    public MigrationRecord getMigrationRecordForMigrationFile(final MigrationFile file, final String migrationKey) {
        try {
            log.info("Getting migration record for migration file named [{}] and migration key [{}]", file.getMetadata().getFilename(), migrationKey);

            Request request = new Request(HTTP_METHOD_GET, String.format("%s/_search", MIGRATION_DOCUMENT_INDEX));
            request.setJsonEntity(String.format(
                    MIGRATION_RECORD_SEARCH_QUERY_TEMPLATE_FIND_ONE_BY_MIGRATION_KEY_AND_MIGRATION_FILENAME,
                    this.migrationKey,
                    file.getMetadata().getFilename()));

            Response response = this.sendRequest(request);
            Map<String, Object> content = this.mapper.readValue(response.getEntity().getContent(), new TypeReference<Map<String, Object>>() {});

            List<MigrationRecord> records = content.entrySet().stream()
                                                   .filter(entry -> entry.getKey().equals("hits"))
                                                   .flatMap(entry -> ((Map<String, Object>) entry.getValue()).entrySet().stream())
                                                   .filter(entry -> entry.getKey().equals("hits"))
                                                   .flatMap(entry -> ((List<Map<String, Object>>) entry.getValue()).stream())
                                                   .flatMap(item -> item.entrySet().stream())
                                                   .filter(entry -> entry.getKey().equals("_source"))
                                                   .map(entry -> (MigrationRecord) this.mapper.convertValue(entry.getValue(), new TypeReference<MigrationRecord>() {}))
                                                   .collect(Collectors.toUnmodifiableList());

            if (records.size() > 1) {
                throw new IllegalStateException(String.format("found more than one migration record for migration file named [%s] and migration key [%s]", file.getMetadata().getFilename(), migrationKey));
            } if (records.size() == 1) {
                log.info("Found existing migration record for migration file named [{}] and migration key [{}]", file.getMetadata().getFilename(), migrationKey);
                return records.get(0);
            } else {
                log.info("Did not find any existing migration record for migration file named [{}] and migration key [{}]", file.getMetadata().getFilename(), migrationKey);
                return null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to get migration records for migration file named [%s] and migration key [%s]", file.getMetadata().getFilename(), migrationKey));
        }
    }

    public void executeMigrationDefinition(final MigrationFile.MigrationFileRequestDefinition definition) {
        try {
            log.info("Executing migration query definition");

            Request request = new Request(definition.getMethod(), definition.getPath());
            if (definition.getParams() != null) {
                definition.getParams().forEach(request::addParameter);
            }

            if (definition.getBody() != null && !definition.getBody().trim().equals("")) {
                request.setEntity(new NStringEntity(definition.getBody(), ContentType.parse(definition.getContentType())));
            }

            this.sendRequest(request);

            log.info("Migration query definition executed successfully");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute migration query definition", e);
        }
    }

    public void createMigrationRecord(final MigrationRecord record) {
        if (!record.getMigrationKey().equals(this.migrationKey)) {
            throw new IllegalStateException("migration record migration key must match operational migration key");
        }

        try {
            log.info("Creating migration record for migration definition file [{}]", record.getFilename());

            Request request = new Request(
                    HTTP_METHOD_POST,
                    String.format(
                            "%s/_doc",
                            MIGRATION_DOCUMENT_INDEX));
            request.addParameter("refresh", "true");
            request.setJsonEntity(this.mapper.writeValueAsString(record));

            this.sendRequest(request);

            log.info("Migration record for migration definition file [{}] created", record.getFilename());
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Failed to creat migration record for migration definition file [%s]", record.getFilename()), e);
        }
    }

    private Response sendRequest(Request request) throws Exception {
        log.debug("sending request [{}]", request);

        Response response = this.client.performRequest(request);

        log.debug("received response [{}]", response);

        return response;
    }
}
