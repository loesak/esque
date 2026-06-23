from __future__ import annotations

from dataclasses import dataclass


@dataclass
class EsqueConfiguration:
    migration_key: str
    migration_user: str | None = None
    migration_directory: str = "file:es.migration"
    lock_timeout_minutes: int = 5
