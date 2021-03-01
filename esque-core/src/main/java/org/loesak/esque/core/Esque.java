package org.loesak.esque.core;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.loesak.esque.core.concurrent.ElasticsearchDocumentLock;
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord;
import org.loesak.esque.core.yaml.MigrationFileLoader;
import org.loesak.esque.core.elasticsearch.RestClientOperations;
import org.loesak.esque.core.yaml.model.MigrationFile;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

@Slf4j
public class Esque implements Closeable {
    private final MigrationFileLoader migrationLoader = new MigrationFileLoader();

    private final RestClientOperations operations;
    private final Lock lock;

    private final String migrationKey;
    private final String migrationUser;

    public Esque(
            @NonNull final RestClient client,
            @NonNull final String migrationKey) {
        this(client, migrationKey, null);
    }

    public Esque(
            @NonNull final RestClient client,
            @NonNull final String migrationKey,
            final String migrationUser) {
        this.operations = new RestClientOperations(client, migrationKey);
        this.lock = new ElasticsearchDocumentLock(operations);
        this.migrationKey = migrationKey;
        this.migrationUser = migrationUser;
    }

    @Override
    public void close() throws IOException {
        try {
            this.lock.unlock();
        } catch (IllegalMonitorStateException e) {
            // intentionally left blank. means lock is already unlocked
        } catch (Exception e) {
            log.warn("failed to release a execution lock. you may need to manually delete the lock document yourself", e);
        }

        try {
            this.operations.close();
        } catch (Exception e) {
            log.warn("failed to close rest client. this is likely not an issue", e);
        }
    }

    public void execute() {
        log.info("Starting esque execution");
        try {
            this.initialize();

            final List<MigrationFile> files = this.migrationLoader.load();
            final List<MigrationRecord> history = this.operations.getMigrationRecords();

            this.verifyStateIntegrity(files, history);

            this.runMigrations(files);

            log.info("Completed esque execution");
        } catch (Exception e) {
            throw new RuntimeException("Failed to run esque execution", e);
        }
    }

    private void initialize() throws Exception {
        log.info("Initializing esque as needed");
        if (!this.operations.checkMigrationIndexExists()) {
            this.operations.createMigrationIndex();
        }
    }

    private void verifyStateIntegrity(final List<MigrationFile> files, final List<MigrationRecord> history) {
        try {
            log.info("Verifying integrity of migration state as compared to found migration files");

            // there shouldn't be more migration files than migration records
            if (history.size() > files.size()) {
                throw new IllegalStateException("the migration records are showing more migrations than the local system defines. did you refactor your files or use an incorrect migraiton key?");
            }

            // a migration record was deleted from the index?
            if (history.size() > 0 && history.size() != history.get(history.size() - 1).getOrder() + 1) {
                throw new IllegalStateException("the migration records seem to be corrupt as some records appear to be missing.");
            }

            // each migration record should match the information about the file that generated it. meaning the file wasnt modified
            history.forEach(record -> {
                final MigrationFile companion = files.stream()
                                                     .filter(file -> file.getMetadata().getFilename().equals(record.getFilename()))
                                                     .findFirst()
                                                     .orElseThrow(() -> new IllegalStateException(
                                                             String.format(
                                                                     "could not find migration file matching migration history record by filename [%s]",
                                                                     record.getFilename())));

                if (record.getOrder() != files.indexOf(companion)
                        || !record.getVersion().equals(companion.getMetadata().getVersion())
                        || !record.getDescription().equals(companion.getMetadata().getDescription())
                        || !record.getChecksum().equals(companion.getMetadata().getChecksum())
                        || !record.getMigrationKey().equals(this.migrationKey)) {
                    throw new IllegalStateException(String.format("could not verify integrity of migration history record for filename [%s]. did you refactor your migration scripts after a previous execution?", record.getFilename()));
                }
            });

            log.info("Integrity checks passed");
        } catch (Exception e) {
            throw new IllegalStateException("state integrity checks failed", e);
        }
    }

    private void runMigrations(final List<MigrationFile> files) {
        try {
            files.forEach(file -> {
                try {
                    log.info("Attempting to acquire lock for execution");

                    if (this.lock.tryLock(5, TimeUnit.MINUTES)) {
                        log.info("Lock acquired. Executing queries defined in migration file [{}]", file.getMetadata().getFilename());

                        // check to see if migration has already ran. entirely possible in a distributed system
                        if (this.operations.getMigrationRecordForMigrationFile(file, this.migrationKey) != null) {
                            log.info("Migration for migration file [{}] and migration key [{}] appears to already have been executed. Skipping", file.getMetadata().getFilename(), this.migrationKey);
                        } else {
                            final Instant start = Instant.now();
                            this.runMigrationForFile(file);
                            final Instant end = Instant.now();

                            final Long duration = Duration.between(start, end).toMillis();

                            log.info("Execution complete for migration file [{}]. Took [{}] milliseconds", file.getMetadata().getFilename(), duration);

                            final MigrationRecord record = new MigrationRecord(
                                    this.migrationKey,
                                    files.indexOf(file),
                                    file.getMetadata().getFilename(),
                                    file.getMetadata().getVersion(),
                                    file.getMetadata().getDescription(),
                                    file.getMetadata().getChecksum(),
                                    this.migrationUser,
                                    end,
                                    duration);

                            this.operations.createMigrationRecord(record);
                        }
                    } else {
                        // TODO: this could happen for long running queries. need to look for something a bit smarter or allow to be configurable
                        log.error("Failed to acquire lock in the allotted time period. Did a lock not get cleared as part of a previous execution?");
                        throw new IllegalStateException("failed to acquire lock");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(String.format("Failed to execute queries in migration file [%s]", file.getMetadata().getFilename()), e);

                    // TODO: should we write a "FAILED" migration record?
                } finally {
                    log.info("Releasing execution lock");
                    this.lock.unlock();
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("failed to run migrations", e);
        }
    }

    private void runMigrationForFile(final MigrationFile file) {
        log.info("Executing queries defined in migration file [{}]", file.getMetadata().getFilename());

        List<MigrationFile.MigrationFileRequestDefinition> requests = file.getContents().getRequests();
        for (MigrationFile.MigrationFileRequestDefinition definition : requests) {
            final Integer position = requests.indexOf(definition);

            try {
                log.info("Executing query in position [{}] defined in migration file [{}]", position, file.getMetadata().getFilename());

                this.operations.executeMigrationDefinition(definition);

                log.info("Query in position [{}] defined in migration file [{}] executed successfully", position, file.getMetadata().getFilename());
            } catch (Exception e) {
                throw new IllegalStateException(
                        String.format(
                                "Failed to execute query in position [%d] defined in migration file [%s]",
                                position,
                                file.getMetadata().getFilename()),
                        e);
            }

        }

        log.info("Execution complete for queries defined in migration file [{}]", file.getMetadata().getFilename());
    }

}
