
# Effective-Request Checksum

**Date:** 2026-06-09
**Status:** Approved

## Problem

The current checksum is computed from raw YAML file bytes. This creates two problems:

1. **Reformatting breaks integrity.** Adding a comment, changing whitespace, or adjusting YAML multiline string style changes the checksum even when the effective HTTP requests are identical.
2. **Template extraction breaks integrity.** Replacing a hardcoded value with a `#{variable}` placeholder that resolves to the same string changes the checksum, even though the migration will execute identically.

The intent of the integrity check is to catch meaningful changes to what was actually applied — reordered requests, inserted requests, changed paths/methods/bodies. It should not fail on cosmetic file edits.

## Goals

- Allow YAML reformatting (comments, whitespace, multiline style) without triggering integrity failures
- Allow extracting a hardcoded value into a `#{variable}` with the same effective value without triggering integrity failures
- Detect meaningful changes: reordered requests, inserted/deleted requests, changed method/path/params/body values
- Fail `load()` with all missing template variables listed at once, not just the first one encountered
- Keep `MigrationRecord.checksum: Int` — no ES schema change

## Non-Goals

- Storing the canonical representation for diff display (future enhancement; use YAML if added)
- Semantic body equivalency (e.g. JSON whitespace normalization inside a body string)
- Backward compatibility with existing `.esque` index records (no other users of this library)

## Design

### Checksum computation

Instead of hashing raw file bytes, the checksum is computed from the canonical YAML serialization of the **resolved** request list:

1. Parse the YAML file → `MigrationFileContents` (raw, with `#{...}` placeholders intact)
2. Substitute all `#{...}` placeholders in every request definition (path, body, params, contentType)
3. Serialize the resolved `MigrationFileContents` to YAML with sorted map keys (for params determinism)
4. MD5-hash the resulting YAML bytes → `Int`

This means two migration files that produce identical HTTP requests — regardless of formatting or how values are expressed — always produce the same checksum.

### Load two-pass approach

`MigrationFileLoader.load()` operates in two passes to ensure all missing variables are reported at once:

**Pass 1 — Parse:** Read and parse all YAML files into raw `MigrationFile` objects (no substitution yet). Collect the full list.

**Pass 2 — Validate, resolve, hash:** Scan all request definitions across all parsed files for `#{...}` references. Collect every variable name with no matching key in `properties`. If any are missing, throw `IllegalStateException` listing all missing names in a single message. If none are missing, substitute all placeholders, compute the canonical checksum, and return resolved `MigrationFile` objects.

Fail fast on missing variables happens as soon as all files are parsed — not per-file, not per-request.

### Changes to `MigrationFileLoader`

`MigrationFileLoader` is constructed with a `MigrationTemplateResolver`:

```kotlin
internal class MigrationFileLoader(private val templateResolver: MigrationTemplateResolver)
```

`load()` implements the two-pass approach above.

`calculateChecksum(path: Path)` is replaced by `calculateChecksum(contents: MigrationFileContents)`:

```kotlin
private fun calculateChecksum(contents: MigrationFileContents): Int {
    // serialize with sorted map keys for params determinism
    val yaml = YAML_MAPPER_SORTED.writeValueAsBytes(contents)
    MESSAGE_DIGEST.reset()
    MESSAGE_DIGEST.update(yaml)
    return ByteBuffer.wrap(MESSAGE_DIGEST.digest()).int
}
```

`YAML_MAPPER_SORTED` is a `YAMLMapper` configured to sort map keys alphabetically (the exact Jackson 3.x API call is left to the implementer).

`MigrationFile` objects returned from `load()` contain fully resolved request definitions — no `#{...}` placeholders remain.

### Changes to `MigrationTemplateResolver`

`validate(files: List<MigrationFile>)` and `resolve(definition)` remain. They become internal implementation details called by the loader rather than called directly by `Esque`. No public API change to the resolver's methods is required, but neither is called from `Esque.execute()` anymore.

`resolve(definition)` expands its substitution scope. Previously it only substituted `path` and `body`. It now also substitutes `contentType` and the values of `params`:

```kotlin
fun resolve(definition: MigrationFileRequestDefinition): MigrationFileRequestDefinition =
    // method is intentionally not substituted
    definition.copy(
        path = substitute(definition.path),
        contentType = definition.contentType?.let { substitute(it) },
        params = definition.params?.mapValues { (_, v) -> substitute(v) },
        body = definition.body?.let { substitute(it) },
    )
```

An additional method is added to resolve an entire file's contents at once:

```kotlin
internal fun resolveContents(contents: MigrationFileContents): MigrationFileContents =
    contents.copy(requests = contents.requests.map { resolve(it) })
```

### Changes to `Esque`

`MigrationFileLoader` is constructed with `templateResolver`:

```kotlin
private val migrationLoader = MigrationFileLoader(templateResolver)
```

`execute()` removes the explicit `templateResolver.validate(files)` call — validation now happens inside `load()`:

```kotlin
fun execute() {
    initialize()
    val files = migrationLoader.load()         // validates, resolves, and hashes
    val history = operations.getMigrationRecords()
    verifyStateIntegrity(files, history)
    runMigrations(files)
}
```

`runMigrationForFile()` removes the per-request `templateResolver.resolve(definition)` call — files are already fully resolved:

```kotlin
operations.executeMigrationDefinition(definition)  // definition already resolved
```

### `MigrationRecord.checksum: Int` — unchanged

The checksum stored in ES remains an `Int`. Only the content that produces it changes.

## Error Handling

| Scenario | Behavior |
|---|---|
| One or more `#{...}` variables missing from `properties` | `IllegalStateException` inside `load()`, all missing names listed in one message |
| `properties` empty, no `#{...}` in files | No-op substitution; checksum computed on canonical YAML of the unmodified request definitions |
| Migration file reformatted (whitespace, comments) | Same resolved requests → same canonical YAML → same checksum → no integrity failure |
| Hardcoded value extracted to `#{variable}` with same effective value | Same resolved requests → same checksum → no integrity failure |
| Request reordered within a file | Different canonical YAML → different checksum → integrity failure |
| Request inserted into a file | Different canonical YAML → different checksum → integrity failure |
| Method, path, params, or body changed | Different canonical YAML → different checksum → integrity failure |

## Testing

### Unit tests (`MigrationFileLoaderTest`)

1. Same requests expressed with different YAML whitespace/comments → same checksum
2. Hardcoded value in path extracted to `#{variable}` with same effective value → same checksum
3. Same requests with body whitespace changed → different checksum (no body-content normalization)
4. Requests reordered → different checksum
5. Request inserted → different checksum
6. Method changed → different checksum
7. `load()` with one missing variable → `IllegalStateException` naming that variable
8. `load()` with multiple missing variables → `IllegalStateException` naming all of them in one error

### Integration tests (`EsqueIT`)

Existing integration tests continue to pass — all migration files (including `V3.0.0__CreateTemplatedIndex.yml`) must supply required properties and execute correctly.

No new integration test scenarios are required beyond confirming existing tests pass with the new checksum strategy.