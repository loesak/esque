# CLAUDE.md

## Project Overview

**Esque** (**E**lasticsearch **S**tateful **Qu**ery **E**xecutor) is a migration management library for Elasticsearch, similar to Flyway but for ES clusters. It executes pre-defined queries in order, tracks which have been applied, validates integrity, and supports distributed locking for safe concurrent execution.

- **License:** Apache 2.0
- **Language:** Java 11
- **Build:** Maven 3.9+
- **Target:** Elasticsearch 7+ (client version 7.17.x)
- **Published to:** Maven Central via OSSRH

## Repository Structure

```
esque/
├── pom.xml                          # Parent POM (multi-module)
├── version.sh                       # Git-tag-based version calculation
├── .github/workflows/
│   └── maven-deploy.yml             # CI/CD: build + deploy to Maven Central
├── .devcontainer/                   # Dev container (Ubuntu, Zulu JDK 11, Maven 3.9)
├── esque-core/                      # Core library (the published artifact)
│   ├── pom.xml
│   └── src/main/java/org/loesak/esque/core/
│       ├── Esque.java               # Main entry point / orchestrator
│       ├── concurrent/
│       │   └── ElasticsearchDocumentLock.java  # Distributed lock via ES docs
│       ├── elasticsearch/
│       │   ├── RestClientOperations.java       # ES REST client abstraction
│       │   ├── documents/
│       │   │   ├── MigrationRecord.java        # Applied migration record model
│       │   │   └── MigrationLock.java          # Lock document model
│       │   └── compatibility/                  # Reserved for multi-ES-version support
│       └── yaml/
│           ├── MigrationFileLoader.java        # YAML file discovery and parsing
│           └── model/
│               └── MigrationFile.java          # Migration file domain model
└── esque-examples/                  # Example applications (not published)
    ├── esque-example-core-simple/   # Basic usage, no auth
    ├── esque-example-core-es-auth/  # Elasticsearch basic auth
    └── esque-example-core-aws-auth/ # AWS auth (placeholder, not implemented)
```

## Build and Development

### Prerequisites

- Java 11 (Zulu distribution recommended)
- Maven 3.9+

### Common Commands

```bash
# Compile the project
mvn clean compile

# Package (skip GPG signing for local dev)
mvn clean package -Dgpg.skip=true

# Install to local repo (skip GPG signing)
mvn clean install -Dgpg.skip=true

# Resolve all dependencies (used by devcontainer on attach)
find . -type f -name "pom.xml" -execdir mvn dependency:resolve \;
```

### GPG Signing

All artifacts are GPG-signed for Maven Central deployment. For local development, always pass `-Dgpg.skip=true` to skip signing. CI handles signing automatically via secrets.

### Versioning

Version is derived from git tags via `version.sh`:
- If the tag matches `X.Y.Z` exactly, that version is used as-is
- Otherwise, the git describe output gets `-SNAPSHOT` appended

CI sets the version with `mvn versions:set -DnewVersion=$(./version.sh)` before building.

### CI/CD

GitHub Actions workflow (`.github/workflows/maven-deploy.yml`) triggers on:
- Push to `master`
- Pull requests to `master`
- GitHub releases (published)

It builds with Java 11 (Zulu) and deploys to OSSRH (Maven Central staging).

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
| `MigrationFile` | Domain model for migration files with version-based ordering |
| `ElasticsearchDocumentLock` | Distributed lock using ES `op_type=create` for atomicity |
| `MigrationRecord` | Domain model for applied migration history records |
| `MigrationLock` | Domain model for lock documents |

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

- **Lombok**: Used extensively throughout
  - `@Value` for immutable data classes (all domain models)
  - `@Slf4j` for logging
  - `@NonNull` for null validation
- **Jackson**: Full suite for JSON and YAML serialization
  - `@JsonTypeInfo` / `@JsonTypeName` for type-wrapped serialization on document models
  - `@ConstructorProperties` for deserialization hints
- **Java Streams**: Preferred for collection transformations (filter-map-collect)
- **Immutable collections**: `Collectors.toUnmodifiableList()` used for result sets
- **Resource management**: `Closeable` interface with try-with-resources
- **Logging**: SLF4J via Lombok `@Slf4j`; info for flow, debug for request/response detail, warn/error for failures

### Design Principles

- Domain models are immutable (`@Value`)
- All ES operations are centralized in `RestClientOperations`
- Migration ordering is deterministic via `Comparable<MigrationFile>` (version parts, then lexical)
- Exceptions wrap lower-level errors with context messages using `String.format`
- No rollback on failure (documented limitation)

## Testing

There are no unit tests in the codebase. Integration testing is done via the example applications in `esque-examples/`. When adding tests, use the `logback-classic` dependency already declared in test scope.

## Module Notes

- **esque-core**: The published library artifact. Contains all core logic.
- **esque-examples**: Aggregator POM with example applications. Configured to skip `install` and `deploy` phases. Not published to Maven Central.
- **esque-example-core-aws-auth**: Placeholder only (POM exists but no implementation).

## Known TODOs in Code

- Build separate artifact versions for ES 7.x and 8.x (`esque-core/pom.xml`)
- Differentiate lock creation failure vs. lock-already-exists (`RestClientOperations:126`)
- Configurable lock timeout for long-running queries (`Esque.java:162`)
- Consider writing "FAILED" migration records (`Esque.java:169`)
- Rollback/undo capability (mentioned in README)
- Elasticsearch security / AWS ES security support (README)
