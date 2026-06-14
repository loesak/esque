from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx
import yaml

ROOT_DIR = Path(__file__).parent.parent

STANDARD_MIGRATIONS = ROOT_DIR / "tests" / "fixtures" / "standard"
TEMPLATED_MIGRATIONS = ROOT_DIR / "tests" / "fixtures" / "templated"
SINGLE_MIGRATION = ROOT_DIR / "tests" / "fixtures" / "single"
ORDERING_MIGRATIONS = ROOT_DIR / "tests" / "fixtures" / "ordering"
INTEGRITY_MODIFIED_MIGRATIONS = ROOT_DIR / "tests" / "fixtures" / "integrity-modified"
INTEGRITY_MISSING_MIGRATIONS = ROOT_DIR / "tests" / "fixtures" / "integrity-missing"


@dataclass
class Implementation:
    name: str
    invocation: str
    task: str | None = None
    command: list[str] = field(default_factory=list)


def all_implementations() -> list[Implementation]:
    config_path = ROOT_DIR / "tests" / "implementations.yml"
    config: dict[str, Any] = yaml.safe_load(config_path.read_text())
    return [
        Implementation(name=name, **cfg)
        for name, cfg in config["implementations"].items()
    ]


def run(
    impl: Implementation,
    es_url: str,
    key: str,
    migrations_dir: Path,
    user: str | None = None,
    properties: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    esque_args = [
        f"--es-url={es_url}",
        f"--migrations-dir={migrations_dir}",
        f"--migration-key={key}",
    ]
    if user:
        esque_args.append(f"--migration-user={user}")
    if properties:
        for k, v in properties.items():
            esque_args.append(f"--property={k}={v}")

    if impl.invocation == "gradle":
        if impl.task is None:
            raise ValueError("Gradle implementation missing 'task' configuration")
        args_str = " ".join(esque_args)
        cmd = ["./gradlew", impl.task, f"--args={args_str}"]
    elif impl.invocation == "direct":
        cmd = [*impl.command, *esque_args]
    else:
        raise ValueError(f"Unknown invocation type: {impl.invocation}")

    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=ROOT_DIR,
        timeout=300,
    )


def get_records(es_url: str, key: str) -> list[dict[str, Any]]:
    try:
        response = httpx.post(
            f"{es_url}/.esque/_search",
            json={
                "query": {
                    "bool": {"filter": [{"term": {"migration.migrationKey": key}}]}
                }
            },
            timeout=10,
        )
        if response.status_code == 404:
            return []
        response.raise_for_status()
    except Exception:
        return []

    hits: list[dict[str, Any]] = response.json()["hits"]["hits"]
    records = [hit["_source"]["migration"] for hit in hits]
    return sorted(records, key=lambda r: r["order"])


def assert_index_exists(es_url: str, index: str) -> None:
    response = httpx.head(f"{es_url}/{index}", timeout=10)
    assert response.status_code == 200, (
        f"Expected index '{index}' to exist but got HTTP {response.status_code}"
    )


def assert_index_absent(es_url: str, index: str) -> None:
    response = httpx.head(f"{es_url}/{index}", timeout=10)
    assert response.status_code == 404, (
        f"Expected index '{index}' to be absent but got HTTP {response.status_code}"
    )
