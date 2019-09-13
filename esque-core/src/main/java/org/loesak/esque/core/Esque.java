package org.loesak.esque.core;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.loesak.esque.core.concurrent.ElasticsearchDocumentLock;
import org.loesak.esque.core.elasticsearch.RestClientOperations;
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord;
import org.loesak.esque.core.yaml.MigrationFileLoader;
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

    private final RestClientOperations operations;
    private final String migrationKey;
    private final Lock lock;

    private final MigrationFileLoader migrationLoader = new MigrationFileLoader();

    public Esque(
            @NonNull final RestClient client,
            @NonNull final String migrationKey) {
        this.operations = new RestClientOperations(client, migrationKey);
        this.migrationKey = migrationKey;
        this.lock = new ElasticsearchDocumentLock(operations);
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

            // everything should look good at this point so lets run new migrations
            files.subList(history.size(), files.size())
                 .forEach(file -> {
                     try {
                         log.info("Attempting to acquire lock for execution");

                         if (this.lock.tryLock(5, TimeUnit.MINUTES)) {
                             log.info("Lock acquired. Executing queries defined in migration file [{}]", file.getMetadata().getFilename());

                             final Instant start = Instant.now();
                             this.operations.executeMigrationFileQueries(file);
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
                                     null, // TODO: user if any
                                     end,
                                     duration);

                             this.operations.createMigrationRecord(record);

                         } else {
                             // TODO: this could happen for long running queries
                             log.error("Failed to acquire lock in the allotted time period. Did a lock not get cleared as part of a previous execution?");
                             throw new IllegalStateException("failed to acquire lock");
                         }
                     } catch (Exception e) {
                         throw new RuntimeException(String.format("Failed to execute queries in migration file [%s]", file.getMetadata().getFilename()), e);
                     } finally {
                         log.info("Releasing execution lock");
                         this.lock.unlock();
                     }
                 });

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
    }

}
