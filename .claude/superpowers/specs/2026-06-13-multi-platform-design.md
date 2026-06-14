# Multi-Platform Esque Design

**Date:** 2026-06-13
**Status:** Approved
**Scope:** Add Python as a second platform implementation, establish repository structure and compatibility test harness to support future additional platforms.

---

## Goal

Extend Esque to support multiple language platforms (starting with Python) within a single repository. Each implementation must behave identically given the same inputs. A shared compatibility test harness verifies behavioral equivalence across all implementations. Python (using uv) is the first additional implementation and proves out the pattern before committing to further languages.

---

## Repository Structure

The repository is reorganized around a top-level `implementations/` directory. The current JVM-centric layout is flattened — `esque-examples/` is dropped, `esque-core/` relocates to `implementations/jvm/`.

```
esque/
├── implementations/
│   ├── jvm/                        # Kotlin library (relocated from esque-core/)
│   │   ├── build.gradle.kts
│   │   └── src/
│   └── python/                     # New Python library
│       ├── pyproject.toml          # uv-managed
│       └── src/esque/
├── tests/
│   ├── fixtures/
│   │   ├── standard/               # V1.0.0, V1.1.0, V2.0.0, V3.0.0 migration YAMLs
│   │   ├── templated/              # Migrations containing #{} placeholders
│   │   └── single/                 # One migration, for isolated tests
│   ├── conftest.py                 # Elasticsearch testcontainer, all_implementations()
│   ├── helpers.py                  # run(), get_records(), assert_index_exists(), etc.
│   ├── test_compatibility.py       # pytest parametrized over scenarios × implementations
│   └── pyproject.toml              # uv-managed, completely separate from implementations/python/
├── build.gradle.kts                # Root Gradle build (JVM modules only)
├── settings.gradle.kts
└── ...
```

`tests/` is an entirely separate uv project from `implementations/python/`. They share no code, no `pyproject.toml`, no dependencies.

---

## CLI Contract

Every implementation exposes a standardized CLI. The test harness invokes each implementation as a subprocess using this interface. The harness does not know or care what language is running.

### Arguments

```
esque \
  --es-url <url>                     # required
  --migrations-dir <path>            # required; file: scheme only (harness always provides an absolute path)
  --migration-key <key>              # required
  [--migration-user <user>]          # optional
  [--lock-timeout-minutes <n>]       # optional, default 5
  [--property <key=value>] ...       # repeatable; for #{} template substitution
```

### Exit Codes

- `0` — execution completed successfully
- Non-zero — execution failed for any reason; error detail written to stderr

### Invocation per Implementation

**JVM:**
```
./gradlew -p implementations/jvm run --args="--es-url <url> --migration-key <key> ..."
```
CLI is implemented using [Clikt](https://ajalt.github.io/clikt/) directly in `implementations/jvm/`. Clikt becomes a transitive dependency of the published library artifact; this is acceptable and revisable later. The Gradle `application` plugin provides the `run` task.

**Python:**
```
uv run --project implementations/python esque --es-url <url> --migration-key <key> ...
```
CLI is implemented using [Click](https://click.palletsprojects.com/) as a standard `entry_points` script in `pyproject.toml`. The CLI is a product feature of the Python library, not test infrastructure.

### Implementation Registry

The harness discovers implementations from `tests/implementations.yml`:

```yaml
implementations:
  jvm:
    command: ["./gradlew", "-p", "implementations/jvm", "run", "--args"]
  python:
    command: ["uv", "run", "--project", "implementations/python", "esque"]
```

Adding a new language = one entry here and the full test suite runs against it automatically.

---

## Compatibility Test Harness

### Philosophy

Tests are written as real Python code using pytest. Scenarios are expressed as test functions, not declarative YAML/JSON files. Helper functions handle the common operations — invoking the CLI, querying ES, asserting state — so test functions stay focused on behavior.

The harness shares nothing with either implementation. It treats each as a black box: invoke the CLI, observe ES state, assert.

### Structure

```python
# tests/test_compatibility.py

@pytest.mark.parametrize("impl", all_implementations())
def test_basic_execution(impl, es):
    run(impl, es, key="basic-test", migrations=STANDARD_MIGRATIONS)
    assert_index_exists(es, "test-index-v1")
    assert_index_exists(es, "test-index-v2")
    assert_index_exists(es, "test-index-v3")

@pytest.mark.parametrize("impl", all_implementations())
def test_idempotent_execution(impl, es):
    run(impl, es, key="idempotent-test", migrations=STANDARD_MIGRATIONS)
    first = get_records(es, "idempotent-test")

    run(impl, es, key="idempotent-test", migrations=STANDARD_MIGRATIONS)
    second = get_records(es, "idempotent-test")

    assert len(second) == len(first)
    for a, b in zip(first, second):
        assert a["checksum"] == b["checksum"]
        assert a["installedOn"] == b["installedOn"]

@pytest.mark.parametrize("impl", all_implementations())
def test_missing_template_variable_fails_before_any_migration(impl, es):
    result = run(impl, es, key="missing-var-test", migrations=TEMPLATED_MIGRATIONS)
    assert result.returncode != 0
    assert len(get_records(es, "missing-var-test")) == 0
```

### Helper API (`tests/helpers.py`)

```python
def run(
    impl: Implementation,
    es_url: str,
    key: str,
    migrations: Path,
    user: str | None = None,
    properties: dict[str, str] | None = None,
) -> subprocess.CompletedProcess: ...

def get_records(es_url: str, key: str) -> list[dict]: ...

def assert_index_exists(es_url: str, index: str) -> None: ...

def assert_index_absent(es_url: str, index: str) -> None: ...

def all_implementations() -> list[Implementation]: ...
```

### Fixtures (`tests/conftest.py`)

- `es` fixture: Elasticsearch testcontainer, scoped per test function, provides the URL
- `all_implementations()`: loads `tests/implementations.yml`, returns list of `Implementation` dataclasses

### Test Dependencies

```toml
# tests/pyproject.toml
[project]
dependencies = [
    "pytest",
    "testcontainers[elasticsearch]",
    "httpx",       # for direct ES queries in helpers
    "pyyaml",      # for loading implementations.yml
]
```

---

## JVM Implementation Changes

### Relocation

`esque-core/` moves to `implementations/jvm/`. All source files, tests, and resources are preserved unchanged. The published Maven artifact coordinates remain the same: `org.loesak:esque-core`.

`esque-examples/` is removed entirely.

`settings.gradle.kts` and root `build.gradle.kts` are updated to reflect the new module path.

### CLI Addition

A CLI entrypoint is added to `implementations/jvm/src/main/kotlin/org/loesak/esque/core/cli/Main.kt` using Clikt. It parses the standardized arguments and delegates to `Esque(client, configuration, properties).execute()`.

The Gradle `application` plugin is added to `implementations/jvm/build.gradle.kts`:

```kotlin
plugins {
    application
}

application {
    mainClass.set("org.loesak.esque.core.cli.MainKt")
}
```

### Checksum Algorithm Change

The current YAML-based checksum is replaced with the cross-language canonical algorithm (see Checksum Specification below). This is a **breaking change** — existing migration records in ES will fail integrity verification after upgrading. Acceptable at pre-1.0 given the active restructuring.

---

## Python Implementation

### Package Structure

```
implementations/python/
  pyproject.toml
  src/esque/
    __init__.py
    esque.py              # main orchestrator — mirrors Esque.kt
    configuration.py      # EsqueConfiguration dataclass
    cli.py                # Click CLI entrypoint
    elasticsearch/
      operations.py       # ES REST calls — mirrors RestClientOperations.kt
      documents.py        # MigrationRecord, MigrationLock dataclasses
      lock.py             # distributed lock — mirrors ElasticsearchDocumentLock.kt
    migration/
      loader.py           # file discovery and parsing — mirrors MigrationFileLoader.kt
      template.py         # #{} substitution — mirrors MigrationTemplateResolver.kt
      model.py            # MigrationFile dataclass
```

### Dependencies

```toml
[project]
dependencies = [
    "elasticsearch",   # official ES Python client, low-level transport for all ES operations
    "pyyaml",          # YAML parsing for migration files
    "click",           # CLI
]
```

No high-level ES client abstractions are used. Raw `perform_request()` calls mirror the JVM low-level REST client approach.

### Execution Flow

Identical to JVM:
1. Initialize — create `.esque` index if it doesn't exist
2. Load — discover and parse YAML migration files from the configured directory
3. Template validation — fail fast if any `#{}` placeholder is unresolvable
4. Load history — fetch existing migration records from `.esque` index for the given key
5. Verify integrity — checksums, ordering, filenames match history
6. Execute migrations — for each unapplied file: acquire lock, execute requests, record history, release lock

### Migration File Discovery

Python uses `file:` scheme only (no `classpath:` — that's a JVM concept). The harness always passes an absolute path. For application use, users pass the directory path directly.

File naming convention is identical: `^V((\d+\.?)+)__(\w+)\.yml$`

Version ordering is numeric per segment (e.g., `1.9.0` < `1.10.0`).

---

## Checksum Specification

### Algorithm

The checksum is computed on the **post-template-resolution** migration file contents.

1. For each request definition, construct a JSON object with **keys sorted alphabetically** and **null fields omitted**:
   ```json
   {"body":"...","contentType":"application/json","method":"PUT","params":{"key":"val"},"path":"/index"}
   ```
2. Construct a JSON array of all request objects in their defined order, **compact** (no whitespace):
   ```json
   [{"method":"PUT","path":"/index-v1"},{"method":"POST","path":"/_aliases","body":"..."}]
   ```
3. Encode the JSON string as **UTF-8 bytes**
4. Compute the **MD5** hash of those bytes
5. Take the **first 4 bytes** of the digest as a **signed 32-bit big-endian integer**

### Cross-Language Compatibility

Implementations make a **best-effort** to produce identical checksums given identical migration file contents. The JSON-based algorithm is well-specified and should produce identical output across languages for the vast majority of real-world migration files.

**Switching between implementations is not supported.** If a migration history was created with the JVM implementation, switching to Python for the same `migrationKey` will likely result in integrity verification failures due to checksum differences. A future checksum regeneration tool may address this, but it is not a current design goal.

Each implementation is self-consistent: checksums are stable across runs of the same implementation against the same files.

---

## Cross-Implementation Compatibility

**Different language implementations are not interchangeable for the same `migrationKey`.** This is a documented limitation, not a bug.

The typical multi-language scenario — a JVM service and a Python service each running migrations against the same ES cluster — is handled by `migrationKey`. Each service uses a distinct key and maintains its own independent migration history. Keys never share records across implementations.

The scenario where interchangeability matters (rewriting an application from one language to another and continuing the same migration history) requires either:
- Starting fresh with the new implementation
- A future checksum regeneration tool (not in scope for this design)

---

## Versioning

All implementations share a single version number. The same git tag drives the version for JVM and Python simultaneously. Version `1.2.0` of `esque-core` (Maven Central) and `esque` (PyPI) represent the same behavioral spec and pass the same compatibility test suite.

Version is derived from git tags via the existing `version.sh`:
- Exact tag match (e.g., `1.2.0`) → release version
- Otherwise → `<describe>-SNAPSHOT` / `<describe>.dev0` (language-appropriate pre-release suffix)

For Python, the version is set dynamically at publish time (`uv version $(./version.sh)`) rather than stored statically in `pyproject.toml`. The `pyproject.toml` carries a placeholder version (`0.0.0`) that is only ever overwritten in CI.

---

## CI Pipeline

The existing `gradle-deploy.yml` is replaced with a multi-job workflow. All jobs run on every push to `master` and on every pull request targeting `master`.

### Workflow: `ci.yml`

```
┌─────────────┐   ┌──────────────┐
│  build-jvm  │   │ build-python │
│             │   │              │
│ ktfmtCheck  │   │ ruff format  │
│ detekt      │   │ ruff check   │
│ gradle test │   │ pyright      │
└──────┬──────┘   └──────┬───────┘
       │                 │
       └────────┬────────┘
                ▼
     ┌─────────────────────┐
     │ compatibility-tests │
     │                     │
     │ pytest (parametrized│
     │ over jvm + python)  │
     └─────────────────────┘
```

**`build-jvm` job:**
```yaml
- uses: actions/setup-java@v4
  with: { distribution: zulu, java-version: 21 }
- run: ./gradlew -PprojectVersion=$(./version.sh) ktfmtCheck detekt test
  working-directory: implementations/jvm
```

**`build-python` job:**
```yaml
- uses: astral-sh/setup-uv@v5
- run: uv run ruff format --check .
  working-directory: implementations/python
- run: uv run ruff check .
  working-directory: implementations/python
- run: uv run pyright
  working-directory: implementations/python
- run: uv run pytest
  working-directory: implementations/python
```

**`compatibility-tests` job** (depends on both build jobs):
```yaml
- uses: actions/setup-java@v4
  with: { distribution: zulu, java-version: 21 }
- uses: astral-sh/setup-uv@v5
- run: uv run pytest
  working-directory: tests
```

Testcontainers requires Docker. GitHub-hosted `ubuntu-latest` runners have Docker available by default — no additional setup needed.

### Python Tooling

| Concern            | Tool                   |
|--------------------|------------------------|
| Formatting         | `ruff format`          |
| Linting            | `ruff check`           |
| Type checking      | `pyright` (strict mode)|
| Testing            | `pytest`               |
| Package management | `uv`                   |

These are dev dependencies in `implementations/python/pyproject.toml` under `[dependency-groups]`.

---

## Release / Publishing

Releases are triggered by publishing a GitHub Release (same trigger as today). A single `release.yml` workflow publishes all implementations to their respective registries.

### Workflow: `release.yml`

Trigger: `release: published`

**`publish-jvm` job:**
```yaml
- uses: actions/setup-java@v4
  with: { distribution: zulu, java-version: 21 }
- run: ./gradlew -PprojectVersion=$(./version.sh) publish -x test
  working-directory: implementations/jvm
  env:
    ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.OSSRH_USERNAME }}
    ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.OSSRH_PASSWORD }}
    ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.OSSRH_GPG_SECRET_KEY }}
    ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.OSSRH_GPG_SECRET_KEY_PASSWORD }}
```

**`publish-python` job:**
```yaml
- uses: astral-sh/setup-uv@v5
- run: uv version $(./version.sh)
  working-directory: implementations/python
- run: uv build
  working-directory: implementations/python
- run: uv publish
  working-directory: implementations/python
  env:
    UV_PUBLISH_TOKEN: ${{ secrets.PYPI_TOKEN }}
```

### Required Secrets

| Secret                              | Used By        | Purpose                    |
|-------------------------------------|----------------|----------------------------|
| `OSSRH_USERNAME`                    | JVM publish    | Maven Central username     |
| `OSSRH_PASSWORD`                    | JVM publish    | Maven Central password     |
| `OSSRH_GPG_SECRET_KEY`              | JVM publish    | In-memory PGP signing key  |
| `OSSRH_GPG_SECRET_KEY_PASSWORD`     | JVM publish    | PGP key password           |
| `PYPI_TOKEN`                        | Python publish | PyPI API token             |

`PYPI_TOKEN` must be added to the GitHub repository secrets before the first Python release.

### Publishing Targets

| Implementation | Registry       | Artifact                |
|----------------|----------------|-------------------------|
| JVM            | Maven Central  | `org.loesak:esque-core` |
| Python         | PyPI           | `esque`                 |

---

## Future Considerations

- **Effective YAML**: Store the post-template-resolution YAML in each migration record. On checksum failure, present a human-readable diff of the stored effective YAML vs the current effective YAML so users can see exactly what changed.
- **Checksum regeneration**: A tool to recalculate and update stored checksums when switching implementations or after algorithm changes.
- **Additional platforms**: TypeScript, Go, etc. Adding a platform requires: implementing the CLI contract, adding one entry to `tests/implementations.yml`. The full test suite runs automatically.
- **CLI fat JAR / standalone distribution**: Distribute the JVM CLI as a self-contained executable (shaded JAR) for users who want CLI access without a JVM project setup. Mirrors Flyway/Liquibase distribution model.
- **esque-cli module separation**: If Clikt as a transitive dependency becomes a concern for library consumers, split into `esque-core` (no CLI deps) and a separate `esque-cli` module.
