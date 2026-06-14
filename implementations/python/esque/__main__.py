"""Esque — Python implementation of Elasticsearch migration management."""

from __future__ import annotations

import hashlib
import json
import logging
import re
import struct
import sys
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

import click
import httpx
import yaml

log = logging.getLogger(__name__)

# ─── Constants ───────────────────────────────────────────────────────────────

MIGRATION_INDEX = "/.esque"
LOCK_ID_PREFIX = "lock"
FILE_NAME_PATTERN = re.compile(r"^V((\d+\.?)+)__(\w+)\.yml$")
TEMPLATE_VAR_PATTERN = re.compile(r"#\{([a-zA-Z0-9._\-]+)}")
LOCK_POLL_INTERVAL = 0.1

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

# ─── Domain models ───────────────────────────────────────────────────────────


@dataclass
class MigrationRequest:
    method: str
    path: str
    content_type: str | None = None
    params: dict[str, str] | None = None
    body: str | None = None

    def to_canonical_dict(self) -> dict[str, Any]:
        """Return dict with only present (non-null) fields, using camelCase keys to match JVM."""
        d: dict[str, Any] = {"method": self.method, "path": self.path}
        if self.body is not None:
            d["body"] = self.body
        if self.content_type is not None:
            d["contentType"] = self.content_type
        if self.params is not None:
            d["params"] = self.params
        return d


@dataclass
class MigrationFile:
    filename: str
    version: str
    description: str
    requests: list[MigrationRequest] = field(default_factory=lambda: [])
    checksum: int = 0

    def _version_tuple(self) -> tuple[int, ...]:
        return tuple(int(x) for x in self.version.split("."))

    def __lt__(self, other: MigrationFile) -> bool:
        a = self._version_tuple()
        b = other._version_tuple()
        max_len = max(len(a), len(b))
        a_p = a + (0,) * (max_len - len(a))
        b_p = b + (0,) * (max_len - len(b))
        return a_p < b_p if a_p != b_p else self.description < other.description

    def __le__(self, other: MigrationFile) -> bool:
        return self == other or self < other

    def __gt__(self, other: MigrationFile) -> bool:
        return not self <= other

    def __ge__(self, other: MigrationFile) -> bool:
        return not self < other


# ─── Checksum ────────────────────────────────────────────────────────────────


def _remove_nulls(obj: Any) -> Any:
    if isinstance(obj, dict):
        d = cast(dict[str, Any], obj)
        return {k: _remove_nulls(v) for k, v in d.items() if v is not None}
    if isinstance(obj, list):
        lst = cast(list[Any], obj)
        return [_remove_nulls(item) for item in lst]
    return obj


def calculate_checksum(requests: list[MigrationRequest]) -> int:
    contents = {"requests": [r.to_canonical_dict() for r in requests]}
    canonical = json.dumps(
        _remove_nulls(contents),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    digest = hashlib.md5(canonical, usedforsecurity=False).digest()
    return struct.unpack(">i", digest[:4])[0]


# ─── Template resolution ─────────────────────────────────────────────────────


def _validate_templates(files: list[MigrationFile], properties: dict[str, str]) -> None:
    missing: set[str] = set()
    for file in files:
        for req in file.requests:
            texts = [req.path, req.content_type or "", req.body or "", *(req.params or {}).values()]
            for text in texts:
                for match in TEMPLATE_VAR_PATTERN.finditer(text):
                    key = match.group(1)
                    if key not in properties:
                        missing.add(key)
    if missing:
        raise ValueError(f"migration files reference template variables with no matching properties: {missing}")


def _substitute(text: str, properties: dict[str, str]) -> str:
    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in properties:
            raise ValueError(f"unresolved template variable '#{{{key}}}'")
        return properties[key]

    return TEMPLATE_VAR_PATTERN.sub(replace, text)


def _resolve_request(req: MigrationRequest, properties: dict[str, str]) -> MigrationRequest:
    return MigrationRequest(
        method=req.method,
        path=_substitute(req.path, properties),
        content_type=_substitute(req.content_type, properties) if req.content_type else None,
        params={k: _substitute(v, properties) for k, v in req.params.items()} if req.params else None,
        body=_substitute(req.body, properties) if req.body else None,
    )


# ─── File loading ─────────────────────────────────────────────────────────────


def _parse_request(raw: dict[str, Any]) -> MigrationRequest:
    return MigrationRequest(
        method=str(raw["method"]),
        path=str(raw["path"]),
        content_type=str(raw["contentType"]) if "contentType" in raw else None,
        params={str(k): str(v) for k, v in raw["params"].items()} if "params" in raw else None,
        body=str(raw["body"]) if "body" in raw else None,
    )


def load_migration_files(migrations_dir: Path, properties: dict[str, str]) -> list[MigrationFile]:
    raw_files: list[MigrationFile] = []
    for path in migrations_dir.iterdir():
        if not path.is_file():
            continue
        match = FILE_NAME_PATTERN.match(path.name)
        if not match:
            continue
        version = match.group(1)
        description = match.group(3)
        data: dict[str, Any] = yaml.safe_load(path.read_text())
        requests = [_parse_request(r) for r in data["requests"]]
        raw_files.append(MigrationFile(filename=path.name, version=version, description=description, requests=requests))

    raw_files.sort()

    _validate_templates(raw_files, properties)

    resolved: list[MigrationFile] = []
    for file in raw_files:
        resolved_requests = [_resolve_request(r, properties) for r in file.requests]
        resolved.append(
            MigrationFile(
                filename=file.filename,
                version=file.version,
                description=file.description,
                requests=resolved_requests,
                checksum=calculate_checksum(resolved_requests),
            )
        )
    return resolved


# ─── ES operations ────────────────────────────────────────────────────────────


def check_index_exists(client: httpx.Client) -> bool:
    return client.head(MIGRATION_INDEX).status_code == 200


def create_index(client: httpx.Client) -> None:
    response = client.put(MIGRATION_INDEX, json=INDEX_DEFINITION)
    if response.status_code in (200, 201):
        return
    body: dict[str, Any] = response.json()
    error = body.get("error", {})
    if isinstance(error, dict) and cast(dict[str, Any], error).get("type") == "resource_already_exists_exception":
        log.info("Migration index already exists — another process created it first")
        return
    response.raise_for_status()


def get_migration_records(client: httpx.Client, migration_key: str) -> list[dict[str, Any]]:
    response = client.post(
        f"{MIGRATION_INDEX}/_search",
        json={"query": {"bool": {"filter": [{"term": {"migration.migrationKey": migration_key}}]}}},
    )
    if response.status_code == 404:
        return []
    response.raise_for_status()
    hits: list[dict[str, Any]] = response.json()["hits"]["hits"]
    records = [hit["_source"]["migration"] for hit in hits]
    return sorted(records, key=lambda r: int(r["order"]))


def get_record_for_file(client: httpx.Client, migration_key: str, filename: str) -> dict[str, Any] | None:
    response = client.post(
        f"{MIGRATION_INDEX}/_search",
        json={
            "query": {
                "bool": {
                    "filter": [
                        {"term": {"migration.migrationKey": migration_key}},
                        {"term": {"migration.filename": filename}},
                    ]
                }
            }
        },
    )
    response.raise_for_status()
    hits: list[dict[str, Any]] = response.json()["hits"]["hits"]
    if not hits:
        return None
    if len(hits) > 1:
        raise RuntimeError(f"Found multiple records for file {filename}")
    result: dict[str, Any] = hits[0]["_source"]["migration"]
    return result


def create_migration_record(
    client: httpx.Client,
    migration_key: str,
    order: int,
    file: MigrationFile,
    installed_by: str | None,
    installed_on: str,
    execution_time_ms: int,
) -> None:
    record: dict[str, Any] = {
        "migrationKey": migration_key,
        "order": order,
        "filename": file.filename,
        "version": file.version,
        "description": file.description,
        "checksum": file.checksum,
        "installedOn": installed_on,
        "executionTime": execution_time_ms,
    }
    if installed_by is not None:
        record["installedBy"] = installed_by
    response = client.post(
        f"{MIGRATION_INDEX}/_doc",
        params={"refresh": "true"},
        json={"migration": record},
    )
    response.raise_for_status()


def try_acquire_lock(client: httpx.Client, migration_key: str) -> bool:
    lock_id = f"{LOCK_ID_PREFIX}:{migration_key}"
    response = client.put(
        f"{MIGRATION_INDEX}/_doc/{lock_id}",
        params={"op_type": "create"},
        json={"lock": {"date": datetime.now(UTC).isoformat()}},
    )
    return response.status_code in (200, 201)


def acquire_lock(client: httpx.Client, migration_key: str, timeout_minutes: int) -> bool:
    deadline = time.monotonic() + timeout_minutes * 60
    while True:
        try:
            if try_acquire_lock(client, migration_key):
                return True
        except Exception:
            pass
        if time.monotonic() >= deadline:
            return False
        time.sleep(LOCK_POLL_INTERVAL)


def release_lock(client: httpx.Client, migration_key: str) -> None:
    lock_id = f"{LOCK_ID_PREFIX}:{migration_key}"
    response = client.delete(f"{MIGRATION_INDEX}/_doc/{lock_id}")
    if response.status_code not in (200, 404):
        response.raise_for_status()


def execute_request(client: httpx.Client, req: MigrationRequest) -> None:
    headers: dict[str, str] = {}
    if req.content_type:
        headers["Content-Type"] = req.content_type
    content: bytes | None = req.body.encode("utf-8") if req.body else None
    response = client.request(
        method=req.method,
        url=req.path,
        params=req.params or {},
        headers=headers,
        content=content,
    )
    response.raise_for_status()


# ─── Integrity verification ───────────────────────────────────────────────────


def verify_integrity(files: list[MigrationFile], history: list[dict[str, Any]], migration_key: str) -> None:
    if len(history) > len(files):
        raise RuntimeError(
            "The migration records are showing more migrations than the local system defines. "
            "Did you refactor your files or use an incorrect migration key?"
        )
    if history and len(history) != int(history[-1]["order"]) + 1:
        raise RuntimeError("The migration records seem to be corrupt as some records appear to be missing.")

    for record in history:
        filename = str(record["filename"])
        companion = next((f for f in files if f.filename == filename), None)
        if companion is None:
            raise RuntimeError(f"Could not find migration file matching record for filename [{filename}]")
        expected_order = files.index(companion)
        if (
            int(record["order"]) != expected_order
            or str(record["version"]) != companion.version
            or str(record["description"]) != companion.description
            or int(record["checksum"]) != companion.checksum
            or str(record["migrationKey"]) != migration_key
        ):
            raise RuntimeError(
                f"Could not verify integrity of migration history record for filename [{filename}]. "
                "Did you refactor your migration scripts after a previous execution?"
            )


# ─── Main execution ───────────────────────────────────────────────────────────


def execute(
    es_url: str,
    migrations_dir: Path,
    migration_key: str,
    migration_user: str | None,
    lock_timeout_minutes: int,
    properties: dict[str, str],
) -> None:
    with httpx.Client(base_url=es_url, timeout=30) as client:
        if not check_index_exists(client):
            create_index(client)

        files = load_migration_files(migrations_dir, properties)
        history = get_migration_records(client, migration_key)
        verify_integrity(files, history, migration_key)

        for i, file in enumerate(files):
            if not acquire_lock(client, migration_key, lock_timeout_minutes):
                raise RuntimeError(
                    f"Failed to acquire lock in the allotted time for file [{file.filename}]. "
                    "Did a lock not get cleared as part of a previous execution?"
                )
            try:
                existing = get_record_for_file(client, migration_key, file.filename)
                if existing is not None:
                    log.info("Migration [%s] already applied, skipping", file.filename)
                    continue

                start = time.monotonic()
                for req in file.requests:
                    execute_request(client, req)
                elapsed_ms = int((time.monotonic() - start) * 1000)

                installed_on = datetime.now(UTC).isoformat()
                create_migration_record(client, migration_key, i, file, migration_user, installed_on, elapsed_ms)
                log.info("Applied [%s] in %dms", file.filename, elapsed_ms)
            finally:
                try:
                    release_lock(client, migration_key)
                except Exception:
                    log.warning("Failed to release lock for [%s] — may need manual cleanup", file.filename)


# ─── CLI ─────────────────────────────────────────────────────────────────────


@click.command(name="esque", help="Run Elasticsearch migrations.")
@click.option("--es-url", required=True, help="Elasticsearch URL (e.g. http://localhost:9200)")
@click.option("--migrations-dir", required=True, help="Absolute path to directory containing migration YAML files")
@click.option("--migration-key", required=True, help="Unique key scoping this migration set")
@click.option("--migration-user", default=None, help="User to record on each migration record")
@click.option("--lock-timeout-minutes", default=5, type=int, help="Lock acquisition timeout in minutes")
@click.option(
    "--property",
    "properties",
    multiple=True,
    help="Template substitution property as key=value (repeatable)",
)
def main(
    es_url: str,
    migrations_dir: str,
    migration_key: str,
    migration_user: str | None,
    lock_timeout_minutes: int,
    properties: tuple[str, ...],
) -> None:
    props: dict[str, str] = {}
    for p in properties:
        parts = p.split("=", 1)
        if len(parts) != 2 or not parts[0]:
            raise click.BadParameter(f"must be in key=value format, got: {p!r}", param_hint="--property")
        props[parts[0]] = parts[1]

    try:
        execute(
            es_url=es_url,
            migrations_dir=Path(migrations_dir),
            migration_key=migration_key,
            migration_user=migration_user,
            lock_timeout_minutes=lock_timeout_minutes,
            properties=props,
        )
    except Exception as exc:
        click.echo(f"Error: {exc}", err=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
