from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any, cast
from urllib.parse import urlencode

from elasticsearch import ConflictError, Elasticsearch

from esque.elasticsearch.documents import (
    INDEX_DEFINITION,
    LOCK_ID_PREFIX,
    MIGRATION_INDEX,
    MigrationLock,
    MigrationRecord,
)
from esque.migration.model import MigrationFile, MigrationFileRequestDefinition

log = logging.getLogger(__name__)


class RestClientOperations:
    def __init__(self, client: Elasticsearch, migration_key: str) -> None:
        self._client = client
        self._migration_key = migration_key

    def close(self) -> None:
        self._client.close()

    def check_migration_index_exists(self) -> bool:
        log.info("Checking if migration index [%s] exists", MIGRATION_INDEX)
        exists = bool(self._client.indices.exists(index=MIGRATION_INDEX))
        log.info("Migration index [%s] %s", MIGRATION_INDEX, "exists" if exists else "does not exist")
        return exists

    def create_migration_index(self) -> None:
        log.info("Creating migration index [%s]", MIGRATION_INDEX)
        try:
            self._client.indices.create(
                index=MIGRATION_INDEX,
                settings=cast(dict[str, Any], INDEX_DEFINITION["settings"]),
                mappings=cast(dict[str, Any], INDEX_DEFINITION["mappings"]),
            )
            log.info("Migration index [%s] created", MIGRATION_INDEX)
        except ConflictError:
            log.info("Migration index [%s] already exists — another process created it first", MIGRATION_INDEX)

    def create_lock_record(self) -> None:
        log.info("Creating lock document for migration key [%s]", self._migration_key)
        lock_id = f"{LOCK_ID_PREFIX}:{self._migration_key}"
        self._client.index(
            index=MIGRATION_INDEX,
            id=lock_id,
            document=MigrationLock(date=datetime.now(UTC)).to_document(),
            op_type="create",
        )
        log.info("Lock document for migration key [%s] created", self._migration_key)

    def delete_lock_record(self) -> None:
        log.info("Deleting lock document for migration key [%s]", self._migration_key)
        lock_id = f"{LOCK_ID_PREFIX}:{self._migration_key}"
        self._client.delete(index=MIGRATION_INDEX, id=lock_id)
        log.info("Lock document for migration key [%s] deleted", self._migration_key)

    def get_migration_records(self) -> list[MigrationRecord]:
        log.info("Getting migration records for migration key [%s]", self._migration_key)
        resp = self._client.search(
            index=MIGRATION_INDEX,
            query={"bool": {"filter": [{"term": {"migration.migrationKey": self._migration_key}}]}},
            size=10000,
        )
        hits = cast(list[dict[str, Any]], resp["hits"]["hits"])
        records = [MigrationRecord.from_document(hit["_source"]) for hit in hits]
        records.sort(key=lambda r: r.order)
        log.info("Found [%d] migration records", len(records))
        return records

    def get_migration_record_for_migration_file(self, file: MigrationFile) -> MigrationRecord | None:
        log.info(
            "Getting migration record for file [%s] and migration key [%s]",
            file.metadata.filename,
            self._migration_key,
        )
        resp = self._client.search(
            index=MIGRATION_INDEX,
            query={
                "bool": {
                    "filter": [
                        {"term": {"migration.migrationKey": self._migration_key}},
                        {"term": {"migration.filename": file.metadata.filename}},
                    ]
                }
            },
        )
        hits = cast(list[dict[str, Any]], resp["hits"]["hits"])
        if len(hits) > 1:
            raise RuntimeError(
                f"found more than one migration record for file [{file.metadata.filename}]"
                f" and migration key [{self._migration_key}]"
            )
        if len(hits) == 1:
            log.info("Found existing migration record for file [%s]", file.metadata.filename)
            return MigrationRecord.from_document(hits[0]["_source"])
        log.info("No existing migration record found for file [%s]", file.metadata.filename)
        return None

    def execute_migration_definition(self, definition: MigrationFileRequestDefinition) -> None:
        log.info("Executing migration query definition")
        target = definition.path
        if definition.params:
            target += "?" + urlencode(definition.params)
        headers: dict[str, str] = {}
        if definition.content_type:
            headers["content-type"] = definition.content_type
        body: bytes | None = definition.body.encode("utf-8") if definition.body else None
        self._client.perform_request(
            definition.method,
            target,
            headers=headers,
            body=body,
        )
        log.info("Migration query definition executed successfully")

    def create_migration_record(self, record: MigrationRecord) -> None:
        if record.migration_key != self._migration_key:
            raise ValueError("migration record migration key must match operational migration key")
        log.info("Creating migration record for file [%s]", record.filename)
        self._client.index(
            index=MIGRATION_INDEX,
            document=record.to_document(),
            refresh=True,  # type: ignore[arg-type]
        )
        log.info("Migration record for file [%s] created", record.filename)
