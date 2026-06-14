# Multi-Platform Phase 1: Compatibility Test Harness

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write a comprehensive black-box compatibility test harness in Python that verifies Esque behavioral correctness, then run it against the existing JVM implementation (`esque-core/` in its current location) to establish a passing baseline before any restructuring occurs.

**Architecture:** The harness lives in `tests/` as a standalone uv project. Each implementation is invoked as a subprocess via a standardized CLI. Tests use pytest parametrized over implementations, testcontainers-python for a real Elasticsearch instance, and httpx for raw ES state queries. The JVM implementation requires a minimal CLI addition (Clikt + Gradle application plugin) to be invocable as a black box. No code is shared between the harness and any implementation.

**Tech Stack:** Python 3.12+, pytest, testcontainers-python, httpx, pyyaml, uv. JVM CLI: Clikt 4.4.0, Gradle application plugin.

**This plan establishes the baseline. Subsequent plans (repo restructuring, Python implementation) use this harness to verify nothing breaks.**

---

## Document Structure

ES documents use a `migration` wrapper object due to Jackson `@JsonTypeInfo(As.WRAPPER_OBJECT)`.
A migration record stored in ES looks like:
```json
{
  "_source": {
    "migration": {
      "migrationKey": "...",
      "order": 0,
      "filename": "V1.0.0__CreateFirstIndex.yml",
      "version": "1.0.0",
      "description": "CreateFirstIndex",
      "checksum": -123456789,
      "installedBy": null,
      "installedOn": "2026-06-13T12:00:00Z",
      "executionTime": 42
    }
  }
}
```

Query for records: `POST /.esque/_search` with body `{"query":{"bool":{"filter":[{"term":{"migration.migrationKey":"<key>"}}]}}}`.

---

## File Map

| Action | Path |
|--------|------|
| Create | `tests/pyproject.toml` |
| Create | `tests/implementations.yml` |
| Create | `tests/conftest.py` |
| Create | `tests/helpers.py` |
| Create | `tests/test_compatibility.py` |
| Create | `tests/fixtures/standard/V1.0.0__CreateFirstIndex.yml` |
| Create | `tests/fixtures/standard/V1.1.0__CreateSecondIndex.yml` |
| Create | `tests/fixtures/standard/V2.0.0__CreateThirdIndex.yml` |
| Create | `tests/fixtures/templated/V1.0.0__CreateFirstIndex.yml` |
| Create | `tests/fixtures/templated/V1.1.0__CreateSecondIndex.yml` |
| Create | `tests/fixtures/templated/V2.0.0__CreateThirdIndex.yml` |
| Create | `tests/fixtures/templated/V3.0.0__CreateTemplatedIndex.yml` |
| Create | `tests/fixtures/single/V1.0.0__CreateFirstIndex.yml` |
| Create | `tests/fixtures/ordering/V1.9.0__NinthMinor.yml` |
| Create | `tests/fixtures/ordering/V1.10.0__TenthMinor.yml` |
| Create | `tests/fixtures/integrity-modified/V1.0.0__CreateFirstIndex.yml` |
| Create | `tests/fixtures/integrity-modified/V1.1.0__CreateSecondIndex.yml` |
| Create | `tests/fixtures/integrity-modified/V2.0.0__CreateThirdIndex.yml` |
| Create | `tests/fixtures/integrity-missing/V1.0.0__CreateFirstIndex.yml` |
| Create | `tests/fixtures/integrity-missing/V1.1.0__CreateSecondIndex.yml` |
| Modify | `gradle/libs.versions.toml` |
| Modify | `esque-core/build.gradle.kts` |
| Create | `esque-core/src/main/kotlin/org/loesak/esque/core/cli/Main.kt` |

---

## Task 1: Add minimal CLI to esque-core

The harness invokes the JVM implementation as a subprocess via `./gradlew :esque-core:run --args="..."`. This requires Clikt for arg parsing and the Gradle `application` plugin for the `run` task. This is the only change to the existing JVM implementation in this phase.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `esque-core/build.gradle.kts`
- Create: `esque-core/src/main/kotlin/org/loesak/esque/core/cli/Main.kt`

- [ ] **Step 1: Add Clikt to the version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:
```toml
clikt = "4.4.0"
```

Add to `[libraries]`:
```toml
clikt = { module = "com.github.ajalt.clikt:clikt", version.ref = "clikt" }
```

- [ ] **Step 2: Update esque-core/build.gradle.kts**

Add `application` to the plugins block and `clikt` to dependencies. Final file:

```kotlin
plugins {
    alias(libs.plugins.vanniktech.publish)
    application
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.elasticsearch.rest.client)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.slf4j.api)
    implementation(libs.clikt)

    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("org.loesak.esque.core.cli.MainKt")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true) {
        signAllPublications()
    }

    coordinates(
        groupId = project.group.toString(),
        artifactId = project.name,
        version = project.version.toString(),
    )

    pom {
        name.set("esque")
        description.set("Resembles an Elasticsearch Stateful Query Executor")
        url.set("https://github.com/loesak/esque")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                name.set("Aaron Loes")
                email.set("aaron.loes@gmail.com")
                organization.set("Loesak")
                organizationUrl.set("https://github.com/loesak/esque")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/loesak/esque.git")
            developerConnection.set("scm:git:ssh://github.com:loesak/esque.git")
            url.set("https://github.com/loesak/esque")
        }
    }
}
```

- [ ] **Step 3: Create esque-core/src/main/kotlin/org/loesak/esque/core/cli/Main.kt**

```kotlin
package org.loesak.esque.core.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

class EsqueCli :
    CliktCommand(
        name = "esque",
        help = "Run Elasticsearch migrations",
    ) {

    private val esUrl by
        option("--es-url", help = "Elasticsearch URL (e.g. http://localhost:9200)").required()

    private val migrationsDir by
        option("--migrations-dir", help = "Absolute path to directory containing migration YAML files")
            .required()

    private val migrationKey by
        option("--migration-key", help = "Unique key scoping this migration set").required()

    private val migrationUser by
        option("--migration-user", help = "User to record on each migration record")

    private val lockTimeoutMinutes by
        option("--lock-timeout-minutes", help = "Lock acquisition timeout in minutes")
            .long()
            .default(5L)

    private val properties by
        option(
                "--property",
                help = "Template substitution property as key=value (repeatable)",
            )
            .multiple()

    override fun run() {
        val props =
            properties.associate { entry ->
                val parts = entry.split("=", limit = 2)
                check(parts.size == 2) { "Property must be in key=value format, got: $entry" }
                parts[0] to parts[1]
            }

        val host = HttpHost.create(esUrl)

        RestClient.builder(host).build().use { client ->
            Esque(
                    client = client,
                    configuration =
                        EsqueConfiguration(
                            migrationKey = migrationKey,
                            migrationUser = migrationUser,
                            migrationDirectory = "file:$migrationsDir",
                            lockTimeoutMinutes = lockTimeoutMinutes,
                        ),
                    properties = props,
                )
                .execute()
        }
    }
}

fun main(args: Array<String>) = EsqueCli().main(args)
```

- [ ] **Step 4: Format the new file**

```bash
./gradlew :esque-core:ktfmtFormat
```

- [ ] **Step 5: Verify the CLI compiles and --help works**

```bash
./gradlew :esque-core:run --args="--help"
```

Expected output includes:
```
Usage: esque [<options>]

  Run Elasticsearch migrations

Options:
  --es-url TEXT                    Elasticsearch URL...
  --migrations-dir TEXT            Absolute path to directory...
  --migration-key TEXT             Unique key scoping this migration set
  --migration-user TEXT            User to record on each migration record
  --lock-timeout-minutes INT       Lock acquisition timeout in minutes
  --property TEXT                  Template substitution property...
  -h, --help                       Show this message and exit
```

- [ ] **Step 6: Run existing tests to confirm nothing is broken**

```bash
./gradlew :esque-core:test
```

Expected: `BUILD SUCCESSFUL`. All existing tests pass.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml esque-core/build.gradle.kts esque-core/src/main/kotlin/org/loesak/esque/core/cli/Main.kt
git commit -m "Add Clikt CLI entrypoint to esque-core for compatibility test harness"
```

---

## Task 2: Set up the tests/ project

**Files:**
- Create: `tests/pyproject.toml`
- Create: `tests/implementations.yml`

- [ ] **Step 1: Create tests/pyproject.toml**

```toml
[project]
name = "esque-compatibility-tests"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "pytest>=8.3.0",
    "testcontainers[elasticsearch]>=4.8.0",
    "httpx>=0.27.0",
    "pyyaml>=6.0.0",
]
```

- [ ] **Step 2: Initialize uv project**

```bash
cd tests && uv lock
```

Expected: `tests/uv.lock` created.

- [ ] **Step 3: Create tests/implementations.yml**

```yaml
implementations:
  jvm:
    invocation: gradle
    task: ":esque-core:run"
```

This file is updated in later phases when the JVM moves to `implementations/jvm/` and when Python is added.

---

## Task 3: Create migration fixture files

These YAML files are the migration inputs for all test scenarios. They follow the same format as the existing `esque-core` test resources.

**Files:** All files under `tests/fixtures/`

- [ ] **Step 1: Create tests/fixtures/standard/ — 3 non-templated migrations**

`tests/fixtures/standard/V1.0.0__CreateFirstIndex.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v1"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/standard/V1.1.0__CreateSecondIndex.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v2"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/standard/V2.0.0__CreateThirdIndex.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v3"
    contentType: application/json; charset=utf-8
```

- [ ] **Step 2: Create tests/fixtures/templated/ — standard 3 plus one templated**

Copy the 3 standard files into `tests/fixtures/templated/` with identical content:

`tests/fixtures/templated/V1.0.0__CreateFirstIndex.yml`: (same as standard)
`tests/fixtures/templated/V1.1.0__CreateSecondIndex.yml`: (same as standard)
`tests/fixtures/templated/V2.0.0__CreateThirdIndex.yml`: (same as standard)

`tests/fixtures/templated/V3.0.0__CreateTemplatedIndex.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/#{indexName}"
    contentType: application/json; charset=utf-8
```

- [ ] **Step 3: Create tests/fixtures/single/ — one migration**

`tests/fixtures/single/V1.0.0__CreateFirstIndex.yml`: (same content as standard V1.0.0)
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v1"
    contentType: application/json; charset=utf-8
```

- [ ] **Step 4: Create tests/fixtures/ordering/ — numeric version ordering edge case**

`tests/fixtures/ordering/V1.9.0__NinthMinor.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-minor-9"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/ordering/V1.10.0__TenthMinor.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-minor-10"
    contentType: application/json; charset=utf-8
```

`V1.9.0` and `V1.10.0` are lexicographically ordered as `V1.10.0 < V1.9.0` (because `'1' < '9'`). The correct numeric order is `V1.9.0` first. This test catches implementations that sort versions as strings.

- [ ] **Step 5: Create tests/fixtures/integrity-modified/ — same filenames, V1.0.0 has different content**

`tests/fixtures/integrity-modified/V1.0.0__CreateFirstIndex.yml`:
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v1-modified"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/integrity-modified/V1.1.0__CreateSecondIndex.yml`: (identical to standard)
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v2"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/integrity-modified/V2.0.0__CreateThirdIndex.yml`: (identical to standard)
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v3"
    contentType: application/json; charset=utf-8
```

The `/test-index-v1-modified` path in V1.0.0 produces a different checksum than `/test-index-v1` in the standard fixture. Running standard first, then re-running with integrity-modified triggers a checksum mismatch failure.

- [ ] **Step 6: Create tests/fixtures/integrity-missing/ — only 2 of the 3 standard migrations**

`tests/fixtures/integrity-missing/V1.0.0__CreateFirstIndex.yml`: (identical to standard)
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v1"
    contentType: application/json; charset=utf-8
```

`tests/fixtures/integrity-missing/V1.1.0__CreateSecondIndex.yml`: (identical to standard)
```yaml
---
requests:
  - method: "PUT"
    path: "/test-index-v2"
    contentType: application/json; charset=utf-8
```

`V2.0.0` is intentionally absent. After applying all 3 standard migrations, re-running with this 2-file directory triggers "more records than files" failure.

---

## Task 4: Write conftest.py

**Files:**
- Create: `tests/conftest.py`

- [ ] **Step 1: Create tests/conftest.py**

```python
import pytest
import httpx
from testcontainers.elasticsearch import ElasticSearchContainer


ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.3.0"


@pytest.fixture(scope="session")
def es_url():
    with (
        ElasticSearchContainer(ES_IMAGE)
        .with_env("xpack.security.enabled", "false")
        .with_env("action.destructive_requires_name", "false")
    ) as es:
        yield es.get_url()


@pytest.fixture(autouse=True)
def clean_es(es_url):
    for pattern in ["/.esque", "/test-*"]:
        try:
            httpx.delete(f"{es_url}{pattern}", timeout=10)
        except Exception:
            pass
```

`scope="session"` starts one Elasticsearch container shared across all tests. `autouse=True` on `clean_es` ensures ES state is wiped before every test function without needing to call it explicitly. The `test-*` wildcard delete clears any indices created by migration fixtures.

---

## Task 5: Write helpers.py

**Files:**
- Create: `tests/helpers.py`

- [ ] **Step 1: Create tests/helpers.py**

```python
from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path

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
    config = yaml.safe_load(config_path.read_text())
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
) -> subprocess.CompletedProcess:
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


def get_records(es_url: str, key: str) -> list[dict]:
    try:
        response = httpx.post(
            f"{es_url}/.esque/_search",
            json={
                "query": {
                    "bool": {
                        "filter": [{"term": {"migration.migrationKey": key}}]
                    }
                }
            },
            timeout=10,
        )
        if response.status_code == 404:
            return []
        response.raise_for_status()
    except Exception:
        return []

    hits = response.json()["hits"]["hits"]
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
```

---

## Task 6: Write test_compatibility.py

**Files:**
- Create: `tests/test_compatibility.py`

Write the full test file in one step. Each test function is parametrized over `all_implementations()`. Test names include the implementation name so failures are immediately identifiable.

- [ ] **Step 1: Create tests/test_compatibility.py**

```python
import pytest
from helpers import (
    Implementation,
    INTEGRITY_MISSING_MIGRATIONS,
    INTEGRITY_MODIFIED_MIGRATIONS,
    ORDERING_MIGRATIONS,
    SINGLE_MIGRATION,
    STANDARD_MIGRATIONS,
    TEMPLATED_MIGRATIONS,
    all_implementations,
    assert_index_absent,
    assert_index_exists,
    get_records,
    run,
)


def implementations():
    return pytest.mark.parametrize(
        "impl",
        all_implementations(),
        ids=lambda i: i.name,
    )


# ---------------------------------------------------------------------------
# Initialization
# ---------------------------------------------------------------------------


@implementations()
def test_esque_management_index_created(impl: Implementation, es_url: str) -> None:
    result = run(impl, es_url, key="init-test", migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, ".esque")


# ---------------------------------------------------------------------------
# Execution
# ---------------------------------------------------------------------------


@implementations()
def test_all_migrations_run(impl: Implementation, es_url: str) -> None:
    result = run(impl, es_url, key="run-all-test", migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v1")
    assert_index_exists(es_url, "test-index-v2")
    assert_index_exists(es_url, "test-index-v3")


@implementations()
def test_idempotent_execution(impl: Implementation, es_url: str) -> None:
    key = "idempotent-test"

    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    first = get_records(es_url, key)
    assert len(first) == 3

    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"Second run failed:\n{result.stderr}"
    second = get_records(es_url, key)
    assert len(second) == 3

    for a, b in zip(first, second):
        assert a["checksum"] == b["checksum"], "Checksum changed between runs"
        assert a["installedOn"] == b["installedOn"], "installedOn changed between runs"


@implementations()
def test_different_migration_keys_are_independent(
    impl: Implementation, es_url: str
) -> None:
    key_a = "independent-key-a"
    key_b = "independent-key-b"

    result = run(impl, es_url, key=key_a, migrations_dir=SINGLE_MIGRATION)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    assert len(get_records(es_url, key_a)) == 1
    assert len(get_records(es_url, key_b)) == 0


# ---------------------------------------------------------------------------
# Migration history metadata
# ---------------------------------------------------------------------------


@implementations()
def test_migration_history_record_count(impl: Implementation, es_url: str) -> None:
    key = "history-count-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3


@implementations()
def test_migration_history_metadata(impl: Implementation, es_url: str) -> None:
    key = "history-metadata-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    records = get_records(es_url, key)
    assert len(records) == 3

    assert records[0]["order"] == 0
    assert records[0]["filename"] == "V1.0.0__CreateFirstIndex.yml"
    assert records[0]["version"] == "1.0.0"
    assert records[0]["description"] == "CreateFirstIndex"
    assert records[0]["migrationKey"] == key

    assert records[1]["order"] == 1
    assert records[1]["filename"] == "V1.1.0__CreateSecondIndex.yml"
    assert records[1]["version"] == "1.1.0"

    assert records[2]["order"] == 2
    assert records[2]["filename"] == "V2.0.0__CreateThirdIndex.yml"
    assert records[2]["version"] == "2.0.0"


@implementations()
def test_migration_history_checksum_is_present(impl: Implementation, es_url: str) -> None:
    key = "history-checksum-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("checksum") is not None, (
            f"checksum missing on record {record['filename']}"
        )


@implementations()
def test_migration_history_execution_time_is_non_negative(
    impl: Implementation, es_url: str
) -> None:
    key = "history-exectime-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record["executionTime"] >= 0, (
            f"executionTime is negative on record {record['filename']}"
        )


@implementations()
def test_migration_history_installed_on_is_present(
    impl: Implementation, es_url: str
) -> None:
    key = "history-installedon-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedOn") is not None, (
            f"installedOn missing on record {record['filename']}"
        )


@implementations()
def test_migration_user_recorded_when_provided(impl: Implementation, es_url: str) -> None:
    key = "user-test"
    result = run(
        impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS, user="test-user"
    )
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedBy") == "test-user", (
            f"Expected installedBy='test-user' on {record['filename']}, got {record.get('installedBy')!r}"
        )


@implementations()
def test_migration_user_null_when_not_provided(impl: Implementation, es_url: str) -> None:
    key = "no-user-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedBy") is None, (
            f"Expected installedBy=null on {record['filename']}, got {record.get('installedBy')!r}"
        )


# ---------------------------------------------------------------------------
# Template variable substitution
# ---------------------------------------------------------------------------


@implementations()
def test_template_substitution_creates_correct_index(
    impl: Implementation, es_url: str
) -> None:
    result = run(
        impl,
        es_url,
        key="template-test",
        migrations_dir=TEMPLATED_MIGRATIONS,
        properties={"indexName": "test-index-v4"},
    )
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v4")


@implementations()
def test_extra_template_properties_ignored(impl: Implementation, es_url: str) -> None:
    result = run(
        impl,
        es_url,
        key="extra-props-test",
        migrations_dir=STANDARD_MIGRATIONS,
        properties={"unused": "ignored", "alsoUnused": "alsoIgnored"},
    )
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v1")


@implementations()
def test_missing_template_property_fails_before_any_migration(
    impl: Implementation, es_url: str
) -> None:
    key = "missing-var-test"
    result = run(
        impl,
        es_url,
        key=key,
        migrations_dir=TEMPLATED_MIGRATIONS,
        # no properties — #{indexName} is unresolvable
    )
    assert result.returncode != 0, (
        f"Expected esque to fail with missing template variable, but it succeeded"
    )
    assert len(get_records(es_url, key)) == 0, (
        "No migration records should be written when template validation fails"
    )


# ---------------------------------------------------------------------------
# Integrity verification
# ---------------------------------------------------------------------------


@implementations()
def test_integrity_checksum_mismatch_causes_failure(
    impl: Implementation, es_url: str
) -> None:
    key = "checksum-mismatch-test"

    # First run: apply standard migrations
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3

    # Second run: same filenames, V1.0.0 has different content → checksum mismatch
    result = run(impl, es_url, key=key, migrations_dir=INTEGRITY_MODIFIED_MIGRATIONS)
    assert result.returncode != 0, (
        "Expected esque to fail due to checksum mismatch, but it succeeded"
    )


@implementations()
def test_integrity_fewer_files_than_records_causes_failure(
    impl: Implementation, es_url: str
) -> None:
    key = "fewer-files-test"

    # First run: apply all 3 standard migrations
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3

    # Second run: only 2 migration files — 3 records but 2 files → should fail
    result = run(impl, es_url, key=key, migrations_dir=INTEGRITY_MISSING_MIGRATIONS)
    assert result.returncode != 0, (
        "Expected esque to fail when migration records outnumber local files"
    )


# ---------------------------------------------------------------------------
# Version ordering
# ---------------------------------------------------------------------------


@implementations()
def test_version_ordering_is_numeric_not_lexicographic(
    impl: Implementation, es_url: str
) -> None:
    key = "ordering-test"
    result = run(impl, es_url, key=key, migrations_dir=ORDERING_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    records = get_records(es_url, key)
    assert len(records) == 2

    # V1.9.0 must come before V1.10.0 (numeric), not after (lexicographic)
    assert records[0]["version"] == "1.9.0", (
        f"Expected first record to be V1.9.0 but got V{records[0]['version']}"
    )
    assert records[1]["version"] == "1.10.0", (
        f"Expected second record to be V1.10.0 but got V{records[1]['version']}"
    )
```

---

## Task 7: Run the tests and verify all pass

- [ ] **Step 1: Run the full test suite**

From the repo root:
```bash
cd tests && uv run pytest -v
```

Or from the repo root:
```bash
uv run --project tests pytest tests/ -v
```

Expected: All tests pass. Output will show each test parametrized by implementation name, e.g.:
```
tests/test_compatibility.py::test_esque_management_index_created[jvm] PASSED
tests/test_compatibility.py::test_all_migrations_run[jvm] PASSED
...
15 passed in Xs
```

- [ ] **Step 2: If any test fails, diagnose before proceeding**

For unexpected failures, add `-s` to see subprocess output:
```bash
uv run --project tests pytest tests/ -v -s
```

The `result.stderr` is included in assertion messages. For Gradle run failures, the output is captured in `result.stderr` and printed on assertion failure.

- [ ] **Step 3: Commit once all tests pass**

```bash
git add tests/
git commit -m "Add comprehensive compatibility test harness

15 scenarios covering: initialization, execution, idempotency, key
independence, history metadata, user recording, template substitution,
integrity verification (checksum mismatch, too few files), and numeric
version ordering. All tests pass against the existing JVM implementation."
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered by |
|---|---|
| Creates .esque index | `test_esque_management_index_created` |
| Runs all migrations | `test_all_migrations_run` |
| Idempotent execution | `test_idempotent_execution` |
| Key independence | `test_different_migration_keys_are_independent` |
| Record count | `test_migration_history_record_count` |
| Record metadata (filename, version, description, order, key) | `test_migration_history_metadata` |
| Checksum present | `test_migration_history_checksum_is_present` |
| executionTime non-negative | `test_migration_history_execution_time_is_non_negative` |
| installedOn present | `test_migration_history_installed_on_is_present` |
| User recorded | `test_migration_user_recorded_when_provided` |
| Null user | `test_migration_user_null_when_not_provided` |
| Template substitution | `test_template_substitution_creates_correct_index` |
| Extra properties ignored | `test_extra_template_properties_ignored` |
| Missing template variable fails before migrations | `test_missing_template_property_fails_before_any_migration` |
| Checksum mismatch fails | `test_integrity_checksum_mismatch_causes_failure` |
| Fewer files than records fails | `test_integrity_fewer_files_than_records_causes_failure` |
| Numeric version ordering | `test_version_ordering_is_numeric_not_lexicographic` |

**Type consistency:** `Implementation` dataclass defined once in `helpers.py`, imported in `test_compatibility.py`. All path constants (`STANDARD_MIGRATIONS`, etc.) defined in `helpers.py` and imported by name. `all_implementations()` called in `implementations()` decorator helper, not redefined.

**ES document field paths:** Confirmed from `MigrationRecord.kt` with `@JsonTypeInfo(As.WRAPPER_OBJECT)` — all record fields are accessed as `record["migration"]["fieldName"]` in ES `_source`, and `migration.fieldName` in query filter terms. `get_records()` unwraps the `migration` wrapper before returning, so test code accesses `record["fieldName"]` directly.

**Container startup:** `scope="session"` on `es_url` starts one container per pytest session. `autouse=True` on `clean_es` cleans state before every test. The `clean_es` fixture depends on `es_url` (session-scoped) from a function-scoped fixture — pytest allows this.

**Gradle --args quoting:** All values passed to `--args` are controlled (no spaces in URLs, keys, or paths under the repo). The `=` form (`--es-url=http://...`) avoids any whitespace-split ambiguity in Gradle's arg parsing.
