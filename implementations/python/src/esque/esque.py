from __future__ import annotations

import logging
import time
from datetime import UTC, datetime
from types import TracebackType

from elasticsearch import Elasticsearch

from esque.configuration import EsqueConfiguration
from esque.elasticsearch.documents import MigrationRecord
from esque.elasticsearch.lock import ElasticsearchDocumentLock
from esque.elasticsearch.operations import RestClientOperations
from esque.migration.loader import MigrationFileLoader
from esque.migration.model import MigrationFile
from esque.migration.template import MigrationTemplateResolver

log = logging.getLogger(__name__)


class Esque:
    def __init__(
        self,
        client: Elasticsearch,
        configuration: EsqueConfiguration,
        properties: dict[str, str] | None = None,
    ) -> None:
        self._configuration = configuration
        self._migration_loader = MigrationFileLoader(
            configuration.migration_directory,
            MigrationTemplateResolver(properties or {}),
        )
        self._operations = RestClientOperations(client, configuration.migration_key)
        self._lock = ElasticsearchDocumentLock(self._operations)

    def __enter__(self) -> Esque:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_val: BaseException | None,
        exc_tb: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        try:
            self._lock.unlock()
        except RuntimeError:
            pass  # lock was not held — expected at end of a clean run
        except Exception:
            log.warning(
                "failed to release a execution lock. you may need to manually delete the lock document yourself"
            )

        try:
            self._operations.close()
        except Exception:
            log.warning("failed to close client. this is likely not an issue")

    def execute(self) -> None:
        log.info("Starting esque execution")
        try:
            self._initialize()
            files = self._migration_loader.load()
            history = self._operations.get_migration_records()
            self._verify_state_integrity(files, history)
            self._run_migrations(files)
            log.info("Completed esque execution")
        except Exception as e:
            raise RuntimeError("Failed to run esque execution") from e

    def _initialize(self) -> None:
        log.info("Initializing esque as needed")
        if not self._operations.check_migration_index_exists():
            self._operations.create_migration_index()

    def _verify_state_integrity(self, files: list[MigrationFile], history: list[MigrationRecord]) -> None:
        log.info("Verifying integrity of migration state as compared to found migration files")
        if len(history) > len(files):
            raise RuntimeError(
                "the migration records are showing more migrations than the local system defines. "
                "did you refactor your files or use an incorrect migration key?"
            )
        if history and len(history) != history[-1].order + 1:
            raise RuntimeError("the migration records seem to be corrupt as some records appear to be missing.")
        for record in history:
            self._verify_record_integrity(record, files)
        log.info("Integrity checks passed")

    def _verify_record_integrity(self, record: MigrationRecord, files: list[MigrationFile]) -> None:
        companion = next((f for f in files if f.metadata.filename == record.filename), None)
        if companion is None:
            raise RuntimeError(
                f"could not find migration file matching migration history record by filename [{record.filename}]"
            )
        if (
            record.order != files.index(companion)
            or record.version != companion.metadata.version
            or record.description != companion.metadata.description
            or record.checksum != companion.metadata.checksum
            or record.migration_key != self._configuration.migration_key
        ):
            raise RuntimeError(
                f"could not verify integrity of migration history record for filename [{record.filename}]. "
                "did you refactor your migration scripts after a previous execution?"
            )

    def _run_migrations(self, files: list[MigrationFile]) -> None:
        try:
            for file in files:
                try:
                    log.info("Attempting to acquire lock for execution")
                    if self._lock.try_lock(self._configuration.lock_timeout_minutes):
                        log.info("Lock acquired. Executing queries in migration file [%s]", file.metadata.filename)
                        if self._operations.get_migration_record_for_migration_file(file) is not None:
                            log.info(
                                "Migration for file [%s] and key [%s] already executed. Skipping",
                                file.metadata.filename,
                                self._configuration.migration_key,
                            )
                        else:
                            start = time.monotonic()
                            self._run_migration_for_file(file)
                            elapsed_ms = int((time.monotonic() - start) * 1000)
                            log.info(
                                "Execution complete for migration file [%s]. Took [%d] milliseconds",
                                file.metadata.filename,
                                elapsed_ms,
                            )
                            self._operations.create_migration_record(
                                MigrationRecord(
                                    migration_key=self._configuration.migration_key,
                                    order=files.index(file),
                                    filename=file.metadata.filename,
                                    version=file.metadata.version,
                                    description=file.metadata.description,
                                    checksum=file.metadata.checksum,
                                    installed_by=self._configuration.migration_user,
                                    installed_on=datetime.now(UTC),
                                    execution_time=elapsed_ms,
                                )
                            )
                    else:
                        log.error("Failed to acquire lock in the allotted time. Did a lock not get cleared?")
                        raise RuntimeError("failed to acquire lock")
                except Exception as e:
                    raise RuntimeError(f"Failed to execute queries in migration file [{file.metadata.filename}]") from e
                finally:
                    log.info("Releasing execution lock")
                    self._lock.unlock()
        except Exception as e:
            raise RuntimeError("failed to run migrations") from e

    def _run_migration_for_file(self, file: MigrationFile) -> None:
        log.info("Executing queries defined in migration file [%s]", file.metadata.filename)
        for position, definition in enumerate(file.contents.requests):
            try:
                log.info(
                    "Executing query in position [%d] in migration file [%s]",
                    position,
                    file.metadata.filename,
                )
                self._operations.execute_migration_definition(definition)
                log.info(
                    "Query in position [%d] in migration file [%s] executed successfully",
                    position,
                    file.metadata.filename,
                )
            except Exception as e:
                raise RuntimeError(
                    f"Failed to execute query in position [{position}] in migration file [{file.metadata.filename}]"
                ) from e
        log.info("Execution complete for queries in migration file [%s]", file.metadata.filename)
