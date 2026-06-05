# CLAUDE.md

## Project Overview

**Esque** (**E**lasticsearch **S**tateful **Qu**ery **E**xecutor) is a migration management library for Elasticsearch, similar to Flyway but for ES clusters. It executes pre-defined queries in order, tracks which have been applied, validates integrity, and supports distributed locking for safe concurrent execution.

- **License:** Apache 2.0
- **Language:** Kotlin 2.4.0 (JVM 21)
- **Build:** Maven 3.9+ and Gradle 9.5.1 (both supported)
- **Target:** Elasticsearch 9+ (client version 9.4.x low-level REST client)
- **Published to:** Maven Central via Central Portal

## Repository Structure

```
esque/
├── pom.xml                          # Parent POM (multi-module Maven build)
├── build.gradle.kts                 # Root Gradle build
├── settings.gradle.kts              # Gradle settings (module declarations)
├── gradle/
│   ├── libs.versions.toml           # Gradle version catalog
│   └── wrapper/                     # Gradle wrapper (9.5.1)
├── gradlew / gradlew.bat            # Gradle wrapper scripts
├── version.sh                       # Git-tag-based version calculation
├── .github/workflows/
│   ├── maven-deploy.yml             # CI/CD: Maven build + deploy to Maven Central
│   └── gradle-deploy.yml            # CI/CD: Gradle build + deploy to Maven Central
├── .devcontainer/                   # Dev container (Ubuntu, Zulu JDK 21, Maven 3.9)
├── esque-core/                      # Core library (the published artifact)
│   ├── pom.xml
│   ├── build.gradle.kts
│   └── src/main/kotlin/org/loesak/esque/core/
│       ├── Esque.kt                 # Main entry point / orchestrator
│       ├── concurrent/
│       │   └── ElasticsearchDocumentLock.kt  # Distributed lock via ES docs
│       ├── elasticsearch/
│       │   ├── RestClientOperations.kt       # ES REST client abstraction
│       │   └── documents/
│       │       ├── MigrationRecord.kt        # Applied migration record model
│       │       └── MigrationLock.kt          # Lock document model
│       └── yaml/
│           ├── MigrationFileLoader.kt        # YAML file discovery and parsing
│           └── model/
│               └── MigrationFile.kt          # Migration file domain model
└── esque-examples/                  # Example applications (not published)
    ├── esque-example-core-simple/   # Basic usage, no auth
    ├── esque-example-core-es-auth/  # Elasticsearch basic auth
    └── esque-example-core-aws-auth/ # AWS auth (placeholder, not implemented)
```

## Build and Development

### Prerequisites

- Java 21 (Zulu distribution recommended)
- Maven 3.9+ or Gradle (wrapper included — no install needed)

### Common Commands

```bash
# Maven — compile
mvn clean compile

# Maven — package (skip GPG signing for local dev)
mvn clean package -Dgpg.skip=true

# Maven — install to local repo
mvn clean install -Dgpg.skip=true

# Gradle — compile
./gradlew compileKotlin

# Gradle — build (compile + test)
./gradlew build

# Gradle — pass version explicitly (mirrors version.sh output)
./gradlew -PprojectVersion=1.0.0-SNAPSHOT build
```

### GPG Signing

**Maven:** All artifacts are GPG-signed for Maven Central deployment. Pass `-Dgpg.skip=true` for local development. CI handles signing automatically via secrets.

**Gradle:** Signing uses in-memory PGP keys via vanniktech's `signingInPlaceKey` / `signingInPlaceKeyPassword` Gradle properties, supplied as environment variables in CI. No GPG keyring import needed.

### Versioning

Version is derived from git tags via `version.sh`:
- If the tag matches `X.Y.Z` exactly, that version is used as-is
- Otherwise, the git describe output gets `-SNAPSHOT` appended

**Maven CI:** `mvn versions:set -DnewVersion=$(./version.sh)` before building.
**Gradle CI:** `-PprojectVersion=$(./version.sh)` passed on the command line.

### CI/CD

Two GitHub Actions workflows trigger on push to `master`, pull requests to `master`, and GitHub releases:

- **`maven-deploy.yml`** — runs `mvn clean deploy`; signs with GPG via `maven-gpg-plugin`
- **`gradle-deploy.yml`** — runs `./gradlew build` then `./gradlew publish -x test`; signs in-memory via vanniktech

Both workflows attempt SNAPSHOT publishing on every run. SNAPSHOT publishing requires the namespace to have snapshots enabled at central.sonatype.com.

## Architecture

### Execution Flow

`Esque.execute()` performs:
1. **Initialize** - Create the `.esque` index in ES if it doesn't exist
2. **Load** - Discover and parse YAML migration files from classpath (`es.migration/` directory)
3. **Load history** - Fetch existing migration records from ES for the given migration key
4. **Verify integrity** - Validate files match history (checksums, ordering, versions)
5. **Execute migrations** - For each unapplied file:
   - Acquire distributed lock (5 min timeout)
   - Skip if already applied (idempotent in distributed environments)
   - Execute each HTTP request defined in the file sequentially
   - Record execution metadata (user, timestamp, duration, checksum)
   - Release lock

### Key Classes

| Class | Purpose |
|-------|---------|
| `Esque` | Main orchestrator - coordinates the full migration lifecycle |
| `RestClientOperations` | ES REST client abstraction for all index/document operations |
| `MigrationFileLoader` | Discovers and parses YAML files from classpath |
| `MigrationFile` | Domain model (data class) for migration files with version-based ordering |
| `ElasticsearchDocumentLock` | Distributed lock using ES `op_type=create` for atomicity |
| `MigrationRecord` | Domain model (data class) for applied migration history records |
| `MigrationLock` | Domain model (data class) for lock documents |

### Distributed Locking

`ElasticsearchDocumentLock` implements `java.util.concurrent.locks.Lock` using a hybrid approach:
- Local `ReentrantLock` for in-process thread safety
- Remote ES document creation (`op_type=create`) for cross-process/cross-node safety
- Inspired by Spring Integration lock implementations (JDBC, Zookeeper, Redis)
- Configurable polling interval (default 100ms)

### Migration File Format

Files must be placed in `src/main/resources/es.migration/` and follow the naming convention:

```
V{VERSION}__{DESCRIPTION}.yml
```

- **VERSION**: Dot-separated numeric segments (e.g., `1.0.0`, `2.1`)
- **DESCRIPTION**: Alphanumeric with underscores (word characters only)
- **Pattern**: `^V((\d+\.?)+)__(\w+)\.yml$`

Example: `V1.0.0__InitialIndexAndAlias.yml`

File contents use YAML format:

```yaml
---
requests:
  - method: "PUT"
    path: "/my-index-v1"
    contentType: application/json; charset=utf-8

  - method: "POST"
    path: "/_aliases"
    contentType: application/json; charset=utf-8
    body: >
      {
        "actions": [
          { "add": { "index": "my-index-v1", "alias": "my-index" } }
        ]
      }
```

Each request supports: `method` (required), `path` (required), `contentType`, `params` (key-value map), `body`.

### State Tracking

Esque uses a hidden ES index `.esque` with two document types:
- **lock**: Contains a `date` field (used for distributed locking)
- **migration**: Contains `migrationKey`, `order`, `filename`, `version`, `description`, `checksum`, `installedBy`, `installedOn`, `executionTime`

Integrity is verified by matching file checksums (MD5) against stored records.

## Code Conventions

### Style

- **Indentation**: 4 spaces
- **Encoding**: UTF-8
- **Class naming**: PascalCase
- **Method naming**: camelCase, descriptive (e.g., `checkMigrationIndexExists`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MIGRATION_DOCUMENT_INDEX`)
- **Packages**: lowercase dot-separated under `org.loesak.esque.core`

### Patterns and Libraries

- **Kotlin data classes**: Used for all immutable domain models (`MigrationRecord`, `MigrationLock`, `MigrationFile` and its nested types). Kotlin null safety enforces non-null constraints.
- **Jackson**: Full suite for JSON and YAML serialization, versions managed via `jackson-bom`
  - `@JsonTypeInfo` / `@JsonTypeName` for type-wrapped serialization on document models
  - `jackson-module-kotlin` for Kotlin data class deserialization
- **kotlin-logging** (`io.github.oshai.kotlinlogging.KotlinLogging`): top-level `val log = KotlinLogging.logger {}`
- **Kotlin idioms**: extension functions, `use {}` for resource management, `filter`/`map`/`toList()` for collections
- **Logging**: SLF4J via kotlin-logging; info for flow, debug for request/response detail, warn/error for failures

### Design Principles

- Domain models are immutable (Kotlin data classes)
- All ES operations are centralized in `RestClientOperations`
- Migration ordering is deterministic via `Comparable<MigrationFile>` (version parts, then lexical)
- Exceptions wrap lower-level errors with context messages
- No rollback on failure (documented limitation)

## Testing

Integration tests live in `esque-core/src/test/kotlin/` and are named `*IT`. They use:
- **JUnit 5** (`junit-jupiter`) as the test framework
- **AssertJ** for assertions
- **Testcontainers** (`testcontainers-elasticsearch`) to spin up a real ES instance via Docker
- **Logback** as the SLF4J implementation (test scope only)

**Maven:** Tests run via `maven-failsafe-plugin` during the `integration-test` phase (`mvn verify`).
**Gradle:** Tests run via the standard `test` task (`./gradlew test`).

Docker must be available for integration tests to run.

## Module Notes

- **esque-core**: The published library artifact. Contains all core logic.
- **esque-examples**: Aggregator with example applications. Not published to Maven Central.
- **esque-example-core-aws-auth**: Placeholder only (POM exists but no implementation).

## Known TODOs in Code

- Differentiate lock creation failure vs. lock-already-exists (`RestClientOperations`)
- Configurable lock timeout for long-running queries (`Esque.kt`)
- Consider writing "FAILED" migration records (`Esque.kt`)
- Rollback/undo capability (mentioned in README)
- Elasticsearch security / AWS ES security support (README)
