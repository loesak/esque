
# Migration File Templating

**Date:** 2026-06-07
**Status:** Approved

## Problem

Migration files contain values that vary by environment (e.g., replica counts for production vs. development vs. local). There is currently no way to parameterize these values without maintaining separate migration file sets per environment.

## Goals

- Accept a map of named properties at `Esque` construction time
- Substitute matching `#{variableName}` placeholders in migration file `path` and `body` fields before execution
- Fail fast (at startup, before any migration runs) if a referenced variable has no matching property
- Leave checksums and integrity checking unaffected — checksum is always computed on the raw template file bytes

## Non-Goals

- Full templating language (conditionals, loops, filters)
- Substitution in `method`, `contentType`, or `params` fields
- Dynamic/runtime variable resolution (properties are fixed at construction time)

## Template Syntax

Placeholders use `#{variableName}` syntax:

```yaml
requests:
  - method: "PUT"
    path: "/#{indexPrefix}-my-index-v1"
    contentType: application/json; charset=utf-8
    body: >
      {
        "settings": {
          "number_of_replicas": #{replicas}
        }
      }
```

**Regex:** `#\{([a-zA-Z0-9._\-]+)}`

Valid variable name characters: letters, digits, dots, underscores, hyphens. This permits names like `replicas`, `cluster.replicas`, `index-prefix`.

**Why `#{}`:** `{{` is used by Elasticsearch's own Mustache search template syntax. `${}` can appear in Painless scripts embedded in migration bodies. `#{}` does not appear in valid Elasticsearch JSON and is not used by any ES subsystem.

## API

One new optional constructor parameter on `Esque`:

```kotlin
class Esque @JvmOverloads constructor(
    client: RestClient,
    private val migrationKey: String,
    private val migrationUser: String? = null,
    private val properties: Map<String, String> = emptyMap(),
) : Closeable
```

Usage:

```kotlin
Esque(
    client = restClient,
    migrationKey = "my-service",
    properties = mapOf(
        "replicas" to "5",
        "indexPrefix" to "prod",
    )
).use { it.execute() }
```

## Architecture

### New class: `MigrationTemplateResolver`

Location: `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt`

```kotlin
internal class MigrationTemplateResolver(private val properties: Map<String, String>) {

    fun validate(files: List<MigrationFile>)

    fun resolve(definition: MigrationFile.MigrationFileRequestDefinition)
        : MigrationFile.MigrationFileRequestDefinition

    companion object {
        private val PLACEHOLDER_PATTERN = Regex("""#\{([a-zA-Z0-9._\-]+)}""")
    }
}
```

**`validate(files)`** — scans the `path` and `body` of every request definition across all loaded files. Collects all `#{...}` variable names that have no matching key in `properties`. If any are missing, throws `IllegalStateException` listing all unresolved names in a single message.

**`resolve(definition)`** — returns a copy of the request definition with all `#{...}` placeholders in `path` and `body` substituted with their values from `properties`. No error paths — `validate()` guarantees all referenced variables are present.

### Changes to `Esque`

`Esque` constructs a `MigrationTemplateResolver` from its `properties` field. In `execute()`, `validate()` is called immediately after `load()` and before `getMigrationRecords()`:

```
initialize()
val files = migrationLoader.load()
templateResolver.validate(files)           // fail fast
val history = operations.getMigrationRecords()
verifyStateIntegrity(files, history)
runMigrations(files)
```

In `runMigrationForFile()`, each request definition is resolved before dispatch:

```kotlin
val resolved = templateResolver.resolve(definition)
operations.executeMigrationDefinition(resolved)
```

### No changes to `MigrationFileLoader`

`MigrationFileLoader.calculateChecksum()` continues to operate on raw file bytes. The `#{...}` placeholders are part of the checksum input. This keeps integrity verification environment-agnostic: the same migration file produces the same checksum regardless of what property values are substituted.

## Error Handling

| Scenario | Behavior |
|---|---|
| Referenced variable not in `properties` | `IllegalStateException` at startup listing all missing names |
| `properties` empty, no `#{...}` in files | Works as today, zero overhead |
| Property value is empty string `""` | Valid — substitutes to empty string |
| `#{...}` in `method`, `contentType`, or `params` | Not substituted — treated as literal text |

## Testing

Integration tests follow the existing `*IT` pattern using Testcontainers. Test cases to cover:

1. Migration with no placeholders and empty `properties` — executes unchanged (regression)
2. Migration with placeholders, all properties provided — correct values substituted in `path` and `body`
3. Migration with placeholders, one or more missing from `properties` — `IllegalStateException` thrown before any migration runs, lists all missing names
4. Checksum of a template file is identical regardless of which `properties` are provided
5. Multiple files, some with placeholders and some without — validation catches missing vars across all files

Unit tests for `MigrationTemplateResolver` in isolation (no ES, no YAML loading needed).