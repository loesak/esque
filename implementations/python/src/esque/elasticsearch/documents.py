from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

MIGRATION_INDEX = ".esque"
LOCK_ID_PREFIX = "lock"

INDEX_DEFINITION: dict[str, Any] = {
    "settings": {
        "index": {
            "number_of_shards": "1",
            "auto_expand_replicas": "0-all",
            "refresh_interval": "1s",
        }
    },
    "mappings": {
        "properties": {
            "lock": {"properties": {"date": {"type": "date"}}},
            "migration": {
                "properties": {
                    "checksum": {"type": "long"},
                    "description": {"type": "keyword"},
                    "executionTime": {"type": "long"},
                    "filename": {"type": "keyword"},
                    "installedOn": {"type": "date"},
                    "migrationKey": {"type": "keyword"},
                    "order": {"type": "long"},
                    "version": {"type": "keyword"},
                }
            },
        }
    },
}


@dataclass
class MigrationRecord:
    migration_key: str
    order: int
    filename: str
    version: str
    description: str
    checksum: int
    installed_by: str | None
    installed_on: datetime
    execution_time: int

    def to_document(self) -> dict[str, Any]:
        doc: dict[str, Any] = {
            "migrationKey": self.migration_key,
            "order": self.order,
            "filename": self.filename,
            "version": self.version,
            "description": self.description,
            "checksum": self.checksum,
            "installedOn": self.installed_on.isoformat(),
            "executionTime": self.execution_time,
        }
        if self.installed_by is not None:
            doc["installedBy"] = self.installed_by
        return {"migration": doc}

    @classmethod
    def from_document(cls, source: dict[str, Any]) -> MigrationRecord:
        raw: dict[str, Any] = source["migration"]
        return cls(
            migration_key=str(raw["migrationKey"]),
            order=int(raw["order"]),
            filename=str(raw["filename"]),
            version=str(raw["version"]),
            description=str(raw["description"]),
            checksum=int(raw["checksum"]),
            installed_by=str(raw["installedBy"]) if raw.get("installedBy") is not None else None,
            installed_on=datetime.fromisoformat(str(raw["installedOn"])),
            execution_time=int(raw["executionTime"]),
        )


@dataclass
class MigrationLock:
    date: datetime

    def to_document(self) -> dict[str, Any]:
        return {"lock": {"date": self.date.isoformat()}}
