# Configuration Design

**Date:** 2026-06-10
**Branch:** feature/migration-templating

## Overview

Introduce an `EsqueConfiguration` data class as the single home for all behavioral settings, replacing the current flat constructor parameters on `Esque`. This resolves two immediate user needs — configurable migration file location and configurable index settings — and establishes a clean extension point for future settings.

## Goals

- Allow users to specify the migration file directory (classpath or filesystem)
- Allow users to configure the lock timeout
- Consolidate `migrationKey` and `migrationUser` into configuration
- Be Spring `@ConfigurationProperties` friendly out of the box
- Update the `.esque` index definition to use `auto_expand_replicas`

## Non-Goals

- Configurable number of shards (hardcoded to 1)
- Configurable number of replicas (handled automatically via `auto_expand_replicas`)
- Configurable ES index name
- Rollback or undo support

---

## Section 1: Public API

### `EsqueConfiguration`

New public data class in `org.loesak.esque.core`:

```kotlin
data class EsqueConfiguration(
    val migrationKey: String,
    val migrationUser: String? = null,
    val migrationDirectory: String = "classpath:es.migration",
    val lockTimeoutMinutes: Long = 5,
)
```

All fields have defaults except `migrationKey`, which is required. This mirrors the current constructor where `migrationKey` was the only required positional argument.

### `Esque` Constructor

```kotlin
class Esque @JvmOverloads constructor(
    client: RestClient,
    private val configuration: EsqueConfiguration,
    properties: Map<String, String> = emptyMap(),
) : Closeable
```

- `client` and `properties` are constructor-only parameters (not stored as fields) — used solely to initialize `operations` and `migrationLoader` respectively
- `configuration` is stored as a `private val` — referenced throughout methods
- This is a breaking change to the constructor signature; existing callers must be updated

### Spring Integration Example

```kotlin
@ConfigurationProperties("esque")
data class EsqueConfiguration(...)  // bound automatically from application.yml

@Bean
fun esque(client: RestClient, configuration: EsqueConfiguration): Esque =
    Esque(client, configuration)
```

```yaml
esque:
  migration-key: my-app
  migration-user: my-app-service
  migration-directory: classpath:es.migration
  lock-timeout-minutes: 5
```

---

## Section 2: Migration File Loading

`MigrationFileLoader` gains a `migrationDirectory: String` constructor parameter. Path resolution is based on URI scheme prefix:

| Prefix | Resolution |
|--------|-----------|
| `classpath:` | `classLoader.getResource(path)` — current behavior |
| `file:` | `Paths.get(path)` — absolute or relative to JVM working directory |
| anything else | Fail fast at load time with a clear error message |

- The scheme prefix is stripped before the path is passed to the resolver
- The `MIGRATION_DEFINITION_DIRECTORY` companion constant is removed; the value comes from `migrationDirectory`
- URI scheme parsing lives in a small private helper method inside `MigrationFileLoader`
- `MigrationFileLoader` remains `internal`

---

## Section 3: Index Definition

`esque-index-defintion.json` is updated:

- `"number_of_shards": "1"` — unchanged
- `"number_of_replicas"` — removed
- `"auto_expand_replicas": "0-all"` — added

This means the `.esque` index automatically has 0 replicas on single-node clusters and expands to all nodes on multi-node clusters. No user configuration needed.

`RestClientOperations` is unchanged — it continues to load this file as a classpath resource verbatim.

---

## Section 4: Internal Wiring

| Current | New |
|---------|-----|
| `migrationKey` constructor param on `Esque` | `configuration.migrationKey` |
| `migrationUser` constructor param on `Esque` | `configuration.migrationUser` |
| `LOCK_TIMEOUT_MINUTES` companion constant | `configuration.lockTimeoutMinutes` |
| `MigrationFileLoader()` (no directory arg) | `MigrationFileLoader(configuration.migrationDirectory, ...)` |

- `RestClientOperations` receives `configuration.migrationKey` the same way it received `migrationKey` today — no internal changes
- `ElasticsearchDocumentLock` is unchanged
- The `Esque` companion object is removed entirely (previously only held `LOCK_TIMEOUT_MINUTES`)

---

## Files Changed

| File | Change |
|------|--------|
| `esque-core/.../Esque.kt` | New constructor signature; use `configuration.*` throughout |
| `esque-core/.../EsqueConfiguration.kt` | New file — public data class |
| `esque-core/.../yaml/MigrationFileLoader.kt` | Add `migrationDirectory` param; add URI scheme resolution |
| `esque-core/.../elasticsearch/esque-index-defintion.json` | Add `auto_expand_replicas`; remove `number_of_replicas` |
| `esque-examples/.../Application.kt` (×2) | Update constructor callsites |
