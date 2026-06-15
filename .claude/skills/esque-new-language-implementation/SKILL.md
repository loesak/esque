---
name: esque-new-language-implementation
description: Use when adding a new language implementation (Go, TypeScript, Rust, etc.) to the esque monorepo — covers required class structure, ES document serialization, checksum algorithm, CLI interface, packaging, and compatibility test registration.
version: 1.0.0
---

# Esque: New Language Implementation Guide

## Overview

Each language port must produce identical observable behavior to the JVM reference implementation: same ES document structure, same checksum algorithm, same CLI interface, same error semantics. The black-box compatibility test harness (`tests/`) validates this automatically.

## Repository Placement

```
implementations/<lang>/    # e.g. implementations/go/, implementations/ts/
├── <build config>         # go.mod, package.json, etc.
└── src/
    ├── configuration.<ext>
    ├── esque.<ext>
    ├── cli.<ext>
    ├── migration/
    │   ├── model.<ext>
    │   ├── template.<ext>
    │   └── loader.<ext>
    └── elasticsearch/
        ├── documents.<ext>
        ├── operations.<ext>
        └── lock.<ext>
```

## Required Classes / Modules

Mirror the JVM structure exactly. These are the nine building blocks:

### 1. `EsqueConfiguration`
Data-only object. Fields:
- `migrationKey: string` — scopes all records in ES
- `migrationUser: string | null`
- `migrationDirectory: string` — `"file:<path>"` scheme only
- `lockTimeoutMinutes: int` (default 5)

**No `properties` field** — properties are passed separately to `Esque`.

### 2. `MigrationFileRequestDefinition`
Represents one HTTP request in a migration YAML file. Fields:
- `method: string` (never template-substituted)
- `path: string`
- `contentType: string | null`
- `params: map<string,string> | null`
- `body: string | null`

Must implement `toCanonicalDict()` that returns only non-null fields with camelCase keys (`contentType`, not `content_type`). This feeds the checksum.

### 3. `MigrationFile` (nested model)
```
MigrationFile
  metadata: MigrationFileMetadata
    filename: string      # "V1.0.0__CreateIndex.yml"
    version: string       # "1.0.0"
    description: string   # "CreateIndex"
    checksum: int         # computed after template resolution
  contents: MigrationFileContents
    requests: [MigrationFileRequestDefinition]
```
Files must sort by version numerically per segment (`1.9.0 < 1.10.0`). Pad shorter versions with zeros.

### 4. `MigrationRecord` / `MigrationLock`
Serialized to/from ES with a **wrapper object** — mirrors JVM's `@JsonTypeInfo(As.WRAPPER_OBJECT)`:

```json
// MigrationRecord
{"migration": {"migrationKey": "...", "order": 0, "filename": "...",
               "version": "...", "description": "...", "checksum": -12345,
               "installedBy": null, "installedOn": "2026-01-01T00:00:00Z",
               "executionTime": 42}}

// MigrationLock
{"lock": {"date": "2026-01-01T00:00:00Z"}}
```

- All field names in camelCase in ES (even if language convention differs)
- `installedOn` / `date` as ISO-8601 UTC
- Lock doc ID: `lock:<migrationKey>` in `.esque` index
- Query records: `POST /.esque/_search` with `{"query":{"bool":{"filter":[{"term":{"migration.migrationKey":"<key>"}}]}}}`

### 5. `RestClientOperations`
Wraps the ES client. Key methods:
- `checkMigrationIndexExists() → bool` — catch 404
- `createMigrationIndex()` — catch 409 (already exists, safe)
- `createLockRecord()` — use `op_type=create`; raises ConflictError if locked
- `deleteLockRecord()`
- `getMigrationRecords() → [MigrationRecord]` — sorted by `order`
- `getMigrationRecordForMigrationFile(file) → MigrationRecord | null`
- `executeMigrationDefinition(def)` — issue arbitrary HTTP request to ES; params go in URL query string
- `createMigrationRecord(record)` — with `refresh=true`

**Use the official ES client** for the target language (avoid HTTP-only clients — type support, retry, auth are free).

### 6. `ElasticsearchDocumentLock`
- Thread-local reentrant lock + ES `op_type=create` polling
- `tryLock(timeoutMinutes) → bool` — polls every 100ms until deadline
- `unlock()` — deletes ES doc, releases local lock in `finally`
- `_doLock() → bool` — calls `createLockRecord`, returns false on any error (don't differentiate ConflictError yet)

### 7. `MigrationTemplateResolver`
- Constructor takes `properties: map<string,string>`
- `validate(files)` — collect ALL missing vars before raising
- `resolve(definition) → definition` — substitutes `#{varName}` in path, contentType, params values, body (NOT method)
- Pattern: `#{[a-zA-Z0-9._-]+}`

### 8. `MigrationFileLoader`
- Constructor: `(migrationDirectory: string, resolver: MigrationTemplateResolver)`
- `load() → [MigrationFile]` — discovers, sorts, validates templates, resolves, computes checksums
- `migrationDirectory` supports `file:` scheme only
- Filename pattern: `^V((\d+\.?)+)__(\w+)\.yml$`

### 9. `Esque` (orchestrator)
Constructor: `Esque(client, configuration, properties = {})` — instantiates all collaborators internally.

Implement as a context manager / `Closeable`. `close()` should: try unlock → swallow "not held" errors, warn on others; then close client.

`execute()` sequence:
1. Initialize (create `.esque` index if absent)
2. Load migration files
3. Fetch migration history
4. `_verifyStateIntegrity(files, history)` — private method on the class
5. For each file: tryLock → skip if already applied → run requests → record → unlock

`_verifyStateIntegrity` is a **private class method** that delegates per-record checks to `_verifyRecordIntegrity`. Both use `configuration.migrationKey` directly rather than taking it as a parameter. Checks:
- `len(history) > len(files)` → error
- Gap check: `len(history) != history[-1].order + 1`
- Per-record: filename match, then order/version/description/checksum/migrationKey all match

Unit tests call `_verifyStateIntegrity` directly on an instance constructed with a mock ES client (constructor only stores references, no ES calls happen at init time).

## Checksum Algorithm (CRITICAL — must be identical across all implementations)

```
1. Build: {"requests": [toCanonicalDict() for each resolved request]}
2. Remove all null/None values recursively
3. JSON serialize: keys sorted alphabetically, no spaces (compact)
4. Encode as UTF-8
5. MD5 digest
6. First 4 bytes as big-endian signed 32-bit integer
```

Reference test vectors — these checksums must match across all implementations:
- `[{method:"PUT", path:"/test-index"}]` → deterministic signed int
- Null fields excluded: `{method, path, body=null}` === `{method, path}`
- Key sort: `body` < `contentType` < `method` < `params` < `path`

## CLI Interface

All implementations share identical option names (kebab-case):
```
--es-url TEXT             required
--migrations-dir TEXT     required
--migration-key TEXT      required
--migration-user TEXT     optional
--lock-timeout-minutes N  default 5
--property key=value      repeatable; parse as "key=value" split on first "="
```

`migrations-dir` is passed as-is; CLI should prepend `"file:"` before constructing `EsqueConfiguration.migrationDirectory`.

Exit 1 on any error; print message to stderr.

## Packaging & CI

1. Add entry to `tests/implementations.yml`:
```yaml
implementations:
  go:
    invocation: direct
    command: ["go", "run", "./cmd/esque"]
```

2. Add a job to `.github/workflows/ci.yml` following the python job pattern:
   - lint + typecheck + build
   - version from `.github/version_<lang>.sh`
   - publish snapshot/pre-release on every build; release on GitHub release event

3. Create `.github/version_<lang>.sh` following `version_python.sh` pattern.

## ES Index Setup

The `.esque` index requires:
```json
{
  "settings": {"number_of_shards": 1, "number_of_replicas": 0},
  "mappings": {
    "dynamic": "false",
    "properties": {
      "migration": {
        "properties": {
          "migrationKey": {"type": "keyword"},
          "filename":     {"type": "keyword"},
          "version":      {"type": "keyword"},
          "description":  {"type": "keyword"},
          "checksum":     {"type": "integer"},
          "order":        {"type": "integer"},
          "installedBy":  {"type": "keyword"},
          "installedOn":  {"type": "date"},
          "executionTime":{"type": "long"}
        }
      }
    }
  }
}
```

## Integration Test Memory Limits

When running testcontainers for integration tests, cap ES memory to avoid OOM:
```
ES_JAVA_OPTS=-Xms512m -Xmx512m
xpack.ml.enabled=false
node.store.allow_mmap=false
xpack.security.enabled=false
action.destructive_requires_name=false
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using field names in snake_case in ES docs | Always camelCase in ES (`migrationKey`, `installedOn`) |
| Forgetting wrapper object pattern | `{"migration": {...}}` not `{...}` flat |
| Checksum before template resolution | Resolve first, then checksum |
| `op_type=create` for migration records | Only for lock docs — records use normal index |
| Template substituting `method` field | Only substitute path, contentType, params values, body |
| Lock ID missing prefix | Lock ID is `lock:<migrationKey>` |
| Missing `refresh=true` on record create | Without it, reads immediately after won't see the record |
