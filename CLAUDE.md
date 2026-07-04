# CLAUDE.md

## Design Docs and Implementation Plans

Specs live in `.claude/superpowers/specs/` named `YYYY-MM-DD-<topic>-design.md`. Implementation plans live in `.claude/superpowers/plans/` named `YYYY-MM-DD-<topic>.md`.

## Project Overview

**Esque** (**E**lasticsearch **S**tateful **Qu**ery **E**xecutor) is a migration management library for Elasticsearch, similar to Flyway but for ES clusters. It executes pre-defined queries in order, tracks which have been applied, validates integrity, and supports distributed locking for safe concurrent execution.

- **License:** Apache 2.0
- **Implementations:** JVM (Kotlin 2.4.0, Java 21) · Python 3.14 · TypeScript 5.x (Node 22+)
- **Target:** Elasticsearch 9+ (ES 9.4.x REST API)
- **JVM published to:** Maven Central as `org.loesak.esque:esque`
- **Python published to:** PyPI as `esque-py`
- **TypeScript published to:** npm as `esque-ts`

## Repository Structure

```
esque/
├── setup-hooks.sh                   # One-time dev setup: activates git pre-commit hook
├── .githooks/
│   └── pre-commit                   # [1] JVM checks [2] Python checks [3] TypeScript checks [4] compat tests
├── .github/
│   ├── version_jvm.sh               # Git-tag-based version for JVM (X.Y.Z or X.Y.Z-...-SNAPSHOT)
│   ├── version_python.sh            # PEP 440 version for Python (X.Y.Z or X.Y.Z.devN)
│   ├── version_typescript.sh        # SemVer version for TypeScript (X.Y.Z or X.Y.Z-dev.N)
│   └── workflows/
│       └── ci.yml                   # lint + build + publish + compatibility-tests
├── .devcontainer/                   # Dev container (Ubuntu, Zulu JDK 21)
├── implementations/
│   ├── jvm/                         # JVM/Kotlin implementation
│   │   ├── build.gradle.kts         # Single-project Gradle build (merged root + core)
│   │   ├── settings.gradle.kts      # rootProject.name = "esque"
│   │   ├── gradle/
│   │   │   ├── libs.versions.toml   # Gradle version catalog
│   │   │   └── wrapper/             # Gradle wrapper (9.5.1)
│   │   ├── gradlew / gradlew.bat    # Gradle wrapper scripts
│   │   ├── gradle.properties        # Gradle daemon/cache/parallel settings
│   │   ├── detekt.yml               # Detekt rule configuration
│   │   └── src/main/kotlin/org/loesak/esque/core/
│   │       ├── Esque.kt             # Main orchestrator
│   │       ├── EsqueConfiguration.kt
│   │       ├── cli/Main.kt          # Clikt CLI entrypoint
│   │       ├── concurrent/
│   │       │   └── ElasticsearchDocumentLock.kt
│   │       ├── elasticsearch/
│   │       │   ├── RestClientOperations.kt
│   │       │   └── documents/
│   │       │       ├── MigrationRecord.kt
│   │       │       └── MigrationLock.kt
│   │       └── yaml/
│   │           ├── MigrationFileLoader.kt
│   │           ├── MigrationTemplateResolver.kt
│   │           └── model/MigrationFile.kt
│   ├── python/                      # Python implementation
│   │   ├── pyproject.toml           # uv project: click, elasticsearch, pyyaml; hatchling build
│   │   ├── src/esque/
│   │   │   ├── configuration.py     # EsqueConfiguration dataclass
│   │   │   ├── esque.py             # Main orchestrator + verify_integrity
│   │   │   ├── cli.py               # Click CLI entrypoint
│   │   │   ├── __main__.py          # python -m esque shim
│   │   │   ├── elasticsearch/
│   │   │   │   ├── documents.py     # INDEX_DEFINITION, constants
│   │   │   │   ├── operations.py    # ES REST calls
│   │   │   │   └── lock.py          # Distributed lock (op_type=create polling)
│   │   │   └── migration/
│   │   │       ├── model.py         # MigrationRequest, MigrationFile
│   │   │       ├── template.py      # #{varName} validation and substitution
│   │   │       └── loader.py        # File discovery, parsing, checksum
│   │   └── tests/
│   │       ├── test_model.py        # Version ordering
│   │       ├── test_checksum.py     # Canonical checksum algorithm
│   │       ├── test_template.py     # Template validation and substitution
│   │       └── test_integrity.py    # verify_integrity error scenarios
│   └── typescript/                  # TypeScript implementation
│       ├── package.json             # npm project: commander, @elastic/elasticsearch, yaml; tsc build
│       ├── src/
│       │   ├── configuration.ts     # EsqueConfiguration type
│       │   ├── esque.ts             # Main orchestrator + verifyIntegrity
│       │   ├── cli.ts               # commander CLI entrypoint
│       │   ├── elasticsearch/
│       │   │   ├── documents.ts     # INDEX_DEFINITION, constants
│       │   │   ├── operations.ts    # ES REST calls
│       │   │   └── lock.ts          # Distributed lock (op_type=create polling)
│       │   └── migration/
│       │       ├── model.ts         # MigrationRequest, MigrationFile
│       │       ├── template.ts      # #{varName} validation and substitution
│       │       └── loader.ts        # File discovery, parsing, checksum
│       └── tests/
│           ├── model.test.ts        # Version ordering
│           ├── checksum.test.ts     # Canonical checksum algorithm
│           ├── template.test.ts     # Template validation and substitution
│           ├── integrity.test.ts    # verifyIntegrity error scenarios
│           ├── loader.test.ts       # File discovery and parsing
│           ├── lock.test.ts         # Distributed lock behavior
│           └── documents.test.ts    # ES document (de)serialization
└── tests/                           # Black-box compatibility test harness
    ├── pyproject.toml               # uv project: pytest, testcontainers, httpx, pyyaml
    ├── implementations.yml          # Registered implementations with invocation config
    ├── conftest.py                  # Session-scoped ES testcontainer + per-test cleanup
    ├── helpers.py                   # run(), get_records(), assert_index_exists()
    ├── test_compatibility.py        # 17 scenarios parametrized over all implementations
    └── fixtures/                    # Migration YAML files per test scenario
        ├── standard/                # 3 migrations (V1.0.0, V1.1.0, V2.0.0)
        ├── templated/               # standard + V3.0.0 with #{indexName}
        ├── single/                  # V1.0.0 only
        ├── ordering/                # V1.9.0 and V1.10.0 (numeric ordering edge case)
        ├── integrity-modified/      # V1.0.0 has different content → checksum mismatch
        └── integrity-missing/       # Only V1.0.0 and V1.1.0 (V2.0.0 absent)
```

## Build and Development

### Prerequisites

- Java 21 (Zulu distribution recommended)
- uv (Python package manager — `curl -LsSf https://astral.sh/uv/install.sh | sh`)
- Node.js 22+ (24 recommended) and npm
- Docker (for integration tests and compatibility tests via testcontainers)

### First-time setup

After cloning, activate the pre-commit hook:

```bash
./setup-hooks.sh
```

This sets `core.hooksPath = .githooks` in your local git config.

### JVM Commands (run from `implementations/jvm/`)

```bash
cd implementations/jvm

# compile
./gradlew compileKotlin

# build (compile + test)
./gradlew build

# pass version explicitly (mirrors version.sh output)
./gradlew -PprojectVersion=1.0.0-SNAPSHOT build

# format code
./gradlew ktfmtFormat

# check formatting and lint (without building)
./gradlew ktfmtCheck detekt

# run the CLI
./gradlew run --args="--help"
```

### Python Commands (run from `implementations/python/`)

```bash
cd implementations/python

# install dependencies
uv sync

# format code
uv run ruff format esque/

# check formatting and lint
uv run ruff check esque/ && uv run ruff format --check esque/

# typecheck
uv run pyright esque/

# run the CLI
uv run esque --help
```

### TypeScript Commands (run from `implementations/typescript/`)

```bash
cd implementations/typescript

# install dependencies
npm install

# format code
npm run format

# check formatting and lint
npm run lint

# typecheck
npm run typecheck

# run unit tests
npm test

# build (emits dist/)
npm run build

# run the CLI
npx tsx src/cli.ts --help
```

### Compatibility Tests (run from `tests/`)

```bash
cd tests

# run all tests against all registered implementations
uv run pytest . -v

# run against a specific implementation only
uv run pytest . -v -k "jvm"
uv run pytest . -v -k "python"
```

### GPG Signing (JVM)

Signing uses in-memory PGP keys via vanniktech's `signingInMemoryKey` / `signingInMemoryKeyPassword` Gradle properties, supplied as environment variables in CI. No GPG keyring import needed.

### Versioning (JVM)

Version is derived from git tags via `version.sh`:
- If the tag matches `X.Y.Z` exactly, that version is used as-is
- Otherwise, the git describe output gets `-SNAPSHOT` appended

`-PprojectVersion=$(../../.github/version_jvm.sh)` is passed on the command line in CI from `implementations/jvm/`.

### JVM Code Style and Linting

- **Formatting**: [ktfmt](https://github.com/facebook/ktfmt) via `com.ncorti.ktfmt.gradle`. Run `./gradlew ktfmtFormat` to auto-format. CI fails on unformatted code.
- **Linting**: [detekt](https://detekt.dev/) via `io.gitlab.arturbosch.detekt`. Config in `implementations/jvm/detekt.yml`. Run `./gradlew detektBaseline` to regenerate the baseline after intentionally accepting violations.
- Disabled rules: `MaxLineLength` (ktfmt owns this), `TooGenericExceptionCaught` (too strict at boundary layers), `ForbiddenComment` (informational TODOs are tracked as known issues).

### Python Code Style and Typing

- **Formatting/Linting**: [ruff](https://docs.astral.sh/ruff/) — Black-compatible, line-length 120. Run `uv run ruff format esque/` to auto-format.
- **Type checking**: [pyright](https://github.com/microsoft/pyright) in `strict` mode + [ty](https://github.com/astral-sh/ty) with all warn-level rules escalated to errors. All code must be fully annotated.

### TypeScript Code Style and Typing

- **Formatting/Linting**: [Biome](https://biomejs.dev/) — one tool for both, analogous to ruff. Run `npm run format` to auto-format, `npm run lint` to check (requires `npm install` once beforehand).
- **Type checking**: TypeScript in `strict` mode with `noUncheckedIndexedAccess`. Run `npm run typecheck`.

### CI/CD

A single **`ci.yml`** handles everything — checks, publishing, and compatibility tests:

- **Triggers**: push to `master` · PRs to `master` · published GitHub releases
- **`jvm`**: ktfmtCheck + detekt + build + publish on every build. vanniktech plugin routes automatically — `*-SNAPSHOT` versions go to OSSRH snapshots, release versions go to Maven Central staging.
- **`python`**: ruff + pyright + build + publish on every build. Version is computed by `.github/version_python.sh` (PEP 440: `X.Y.Z` on exact tag, `X.Y.Z.devN` otherwise) and patched into `pyproject.toml` before building. Non-release builds publish to TestPyPI (`TEST_PYPI_TOKEN`); release builds publish to PyPI (`PYPI_TOKEN`).
- **`typescript`**: Biome + tsc + node:test + build + a compiled dist/cli.js smoke test, then publish on every build. Version from `.github/version_typescript.sh` (SemVer: `X.Y.Z` on exact tag, `X.Y.Z-dev.N` otherwise) patched into `package.json` before building. Non-release builds publish to npm under the `dev` dist-tag (`NPM_TOKEN` secret); release builds publish to `latest`.
- **`compatibility-tests`**: needs `jvm` + `python` + `typescript`; runs 52 pytest scenarios (17 × 3 implementations + 1 cross-implementation equivalency test) via testcontainers.

## Architecture

### Execution Flow

All three implementations perform the same sequence:

1. **Initialize** — Create the `.esque` index in ES if it doesn't exist
2. **Load** — Discover and parse YAML migration files from the migrations directory
3. **Validate templates** — Fail fast if any `#{varName}` references a missing property
4. **Resolve templates** — Substitute `#{varName}` → `properties[varName]` in all request fields (path, contentType, params values, body; NOT method)
5. **Calculate checksums** — JSON canonical (sorted keys, nulls omitted, compact UTF-8 → MD5 → first 4 bytes big-endian signed int)
6. **Load history** — Fetch existing migration records from ES for the given migration key
7. **Verify integrity** — Records ≤ files; no gaps; each record's checksum/version/description/order matches its companion file
8. **Execute migrations** — For each unapplied file:
   - Acquire distributed lock (`op_type=create`, 100ms poll, configurable timeout)
   - Skip if already applied (idempotent in distributed environments)
   - Execute each HTTP request defined in the file sequentially
   - Record execution metadata (user, timestamp, duration, checksum)
   - Release lock

### Key Classes (JVM)

| Class | Purpose |
|-------|---------|
| `Esque` | Main orchestrator |
| `EsqueConfiguration` | Configuration data class |
| `RestClientOperations` | ES REST client abstraction |
| `MigrationFileLoader` | File discovery, parsing, template resolution, checksum |
| `MigrationTemplateResolver` | `#{varName}` substitution and validation |
| `MigrationFile` | Domain model with version-based `Comparable` ordering |
| `ElasticsearchDocumentLock` | Distributed lock via ES `op_type=create` |
| `MigrationRecord` | Applied migration history record |
| `MigrationLock` | Lock document model |

### Checksum Algorithm

All three implementations must produce identical checksums for the same resolved migration content:

1. Serialize the resolved request list as JSON: `{"requests": [{...}, ...]}` with keys sorted alphabetically and null fields omitted
2. Encode as UTF-8
3. Compute MD5 digest
4. Take the first 4 bytes interpreted as a big-endian signed 32-bit integer

This is the canonical algorithm since Phase 3. The JVM uses `JSON_MAPPER_CANONICAL` (Jackson with `ORDER_MAP_ENTRIES_BY_KEYS` + `NON_NULL`). Python uses `json.dumps(sort_keys=True, separators=(',', ':'))` after recursively removing None values. TypeScript uses a hand-written `canonicalJson` serializer (sorted keys, null/undefined object values dropped, compact separators) before MD5-hashing.

### ES Document Structure

Migration records are stored in the hidden `.esque` index with a `migration` wrapper object (due to Jackson `@JsonTypeInfo(As.WRAPPER_OBJECT)` in the JVM):

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

Lock documents use the same wrapper pattern: `{"lock": {"date": "..."}}` with doc ID `lock:<migrationKey>`.

Query for records: `POST /.esque/_search` with body `{"query":{"bool":{"filter":[{"term":{"migration.migrationKey":"<key>"}}]}}}`.

### Migration File Format

```
V{VERSION}__{DESCRIPTION}.yml
```

- **VERSION**: Dot-separated numeric segments (e.g., `1.0.0`, `2.1`). Sorted numerically per segment — `1.9.0` < `1.10.0`.
- **DESCRIPTION**: Alphanumeric with underscores (`\w+`)
- **Pattern**: `^V((\d+\.?)+)__(\w+)\.yml$`

### Distributed Locking

Uses ES `op_type=create` for cross-process atomicity. The JVM also wraps this with a local `ReentrantLock` for thread safety. Python polls at 100ms intervals. TypeScript polls at 100ms intervals like Python, using a boolean held-flag instead of a real mutex since Node is single-threaded. All three default to a 5-minute timeout.

## JVM Code Conventions

### Style

- **Indentation**: 2 spaces (ktfmt manages this)
- **Encoding**: UTF-8
- **Class naming**: PascalCase
- **Method naming**: camelCase
- **Constants**: UPPER_SNAKE_CASE
- **Packages**: lowercase under `org.loesak.esque.core`

### Patterns and Libraries

- **Kotlin data classes**: All immutable domain models
- **Jackson**: JSON and YAML serialization via `jackson-bom`; `@JsonTypeInfo` / `@JsonTypeName` for type-wrapped ES documents; `jackson-module-kotlin` for data class deserialization
- **kotlin-logging**: `val log = KotlinLogging.logger {}` at top level
- **Clikt 4.4.0**: CLI parsing (`--es-url`, `--migrations-dir`, `--migration-key`, `--migration-user`, `--lock-timeout-minutes`, `--property` repeatable)

## Python Code Conventions

- **Package layout**: `src/esque/` with modules mirroring the JVM structure — `esque.py` (orchestrator), `configuration.py`, `cli.py`, `elasticsearch/` (documents, operations, lock), `migration/` (model, template, loader)
- **Entry point**: `esque.cli:main`; `__main__.py` is a thin shim for `python -m esque`
- **Strict typing**: all functions annotated; `cast()` used where isinstance-narrowing produces Unknown; `field(default_factory=lambda: [])` instead of `field(default_factory=list)` to satisfy pyright strict
- **elasticsearch**: official Python ES client (>=9) — handles auth mechanisms, retries, and typed responses
- **PyYAML**: migration file parsing
- **Click**: CLI with the same option names as the JVM Clikt interface

## TypeScript Code Conventions

- **Package layout**: `src/` mirrors the module structure used by JVM/Python — `esque.ts` (orchestrator), `configuration.ts`, `cli.ts`, `elasticsearch/` (documents, operations, lock), `migration/` (model, template, loader)
- **Module system**: ESM-only (`"type": "module"` in package.json), Node.js 22+
- **@elastic/elasticsearch**: official TypeScript ES client (same choice as Python and JVM — needed for auth mechanisms, retries, and typed responses; a plain HTTP client was considered and rejected for the same reasons Python rejected it)
- **commander**: CLI framework with the same option names as the Python Click / JVM Clikt interfaces
- **yaml**: migration file parsing

## Testing

### JVM Integration Tests

Live in `implementations/jvm/src/test/kotlin/` and are named `*IT`. Use JUnit 5, AssertJ, and testcontainers-elasticsearch. Run via `./gradlew test` (requires Docker).

### Python Unit Tests

Live in `implementations/python/tests/`. Pure unit tests (no ES), covering the most complex logic:
- `test_model.py` — numeric version ordering (`1.9.0 < 1.10.0`)
- `test_checksum.py` — canonical checksum algorithm properties
- `test_template.py` — `#{varName}` validation and substitution across all request fields
- `test_integrity.py` — all `verify_integrity` error scenarios

Run via `uv run pytest` from `implementations/python/`.

### TypeScript Unit Tests

Live in `implementations/typescript/tests/`. Pure unit tests (no ES), covering the most complex logic:
- `model.test.ts` — numeric version ordering (`1.9.0 < 1.10.0`)
- `template.test.ts` — `#{varName}` validation and substitution across all request fields
- `checksum.test.ts` — canonical checksum algorithm properties, including a pinned cross-implementation reference vector
- `loader.test.ts` — migration file discovery, ordering, and fail-loud validation of malformed YAML
- `lock.test.ts` — distributed lock acquisition/release/timeout behavior
- `documents.test.ts` — ES document (de)serialization, including fail-loud validation of malformed records
- `integrity.test.ts` — all `verifyStateIntegrity` error scenarios

Run via `npm test` from `implementations/typescript/` (uses `node:test` via `tsx`, no build step required).

### Compatibility Test Harness

Lives in `tests/` as a standalone uv project. Each test invokes an implementation as a subprocess via its CLI, then queries ES directly via httpx to verify state.

- **Fixture**: one session-scoped ES container (`ElasticSearchContainer`), cleaned between tests with `DELETE /.esque` and `DELETE /test-*`
- **Parametrized**: every test function is parametrized over `all_implementations()` which reads `tests/implementations.yml`
- **Scenario count**: 17 parametrized scenarios × 3 implementations + 1 cross-implementation equivalency test = 52 pytest cases
- **Adding a new implementation**: add an entry to `implementations.yml` with `invocation: direct` and a `command` list; tests run automatically. TypeScript runs via `tsx` directly against `src/cli.ts` with no build step required (only `npm ci` beforehand needed), analogous to how `uv run` auto-syncs for Python.

### Registered Implementations (`tests/implementations.yml`)

```yaml
implementations:
  jvm:
    invocation: gradle
    gradle_dir: "implementations/jvm"
    task: "run"
  python:
    invocation: direct
    command: ["uv", "run", "--project", "implementations/python", "esque"]
  typescript:
    invocation: direct
    command: ["npm", "exec", "--prefix", "implementations/typescript", "--", "tsx", "implementations/typescript/src/cli.ts"]
```

## Known TODOs in Code

- Differentiate lock creation failure vs. lock-already-exists (present in all three implementations: JVM `RestClientOperations`, Python `lock.py`, TypeScript `lock.ts`)
- Configurable lock timeout for long-running queries (`Esque.kt`)
- Consider writing "FAILED" migration records (`Esque.kt`)
- Rollback/undo capability
- Elasticsearch security / AWS auth support
