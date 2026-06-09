# Effective-Request Checksum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the raw-file-bytes checksum with a checksum computed on the canonical YAML serialization of resolved request definitions, so that YAML reformatting and template extraction with equivalent values no longer trigger integrity failures.

**Architecture:** `MigrationTemplateResolver.resolve()` is expanded to cover `contentType` and `params` values, and a new `resolveContents()` method is added. `MigrationFileLoader` gains a `MigrationTemplateResolver` constructor parameter and implements a two-pass `load()`: parse all files, validate all missing template variables at once (fail-fast with complete list), then resolve and compute the canonical checksum. `Esque` is wired to pass its resolver to the loader and drops the now-redundant explicit `validate()` and per-request `resolve()` calls.

**Tech Stack:** Kotlin 2.4.0, Jackson 3.1.4 (`tools.jackson`), JUnit 6, AssertJ

---

## File Map

| Action | File |
|--------|------|
| Modify | `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt` |
| Modify | `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt` |
| Modify | `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt` |
| Create | `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderTest.kt` |
| Modify | `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt` |
| Modify | `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt` |

---

## Task 1: Expand `MigrationTemplateResolver` — contentType, params substitution, and `resolveContents()`

The spec adds `contentType` and `params` values to the substitution scope (previously only `path` and `body`). `method` remains excluded. A new `resolveContents()` convenience method is added for use by the loader.

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt`
- Modify: `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt`

- [ ] **Step 1: Update `makeDefinition` helper in the test to include `params`**

In `MigrationTemplateResolverTest.kt`, replace the existing `makeDefinition` function:

```kotlin
private fun makeDefinition(
    path: String = "/index",
    body: String? = null,
    method: String = "PUT",
    contentType: String? = null,
    params: Map<String, String>? = null,
) =
    MigrationFile.MigrationFileRequestDefinition(
        method = method, path = path, body = body, contentType = contentType, params = params)
```

- [ ] **Step 2: Replace `resolve_doesNotSubstituteInContentType` with a test that asserts it now DOES substitute**

In `MigrationTemplateResolverTest.kt`, find and replace the test named `resolve_doesNotSubstituteInContentType`:

```kotlin
@Test
fun resolve_substitutesPlaceholderInContentType() {
  val resolved =
      MigrationTemplateResolver(mapOf("type" to "json"))
          .resolve(makeDefinition(contentType = "application/#{type}"))
  assertThat(resolved.contentType).isEqualTo("application/json")
}
```

- [ ] **Step 3: Add test for params value substitution**

Append to `MigrationTemplateResolverTest.kt`, inside the `// --- resolve ---` section:

```kotlin
@Test
fun resolve_substitutesPlaceholderInParamsValues() {
  val resolved =
      MigrationTemplateResolver(mapOf("size" to "100"))
          .resolve(makeDefinition(params = mapOf("size" to "#{size}", "format" to "json")))
  assertThat(resolved.params).containsEntry("size", "100")
  assertThat(resolved.params).containsEntry("format", "json")
}

@Test
fun resolve_doesNotSubstituteParamsKeys() {
  val resolved =
      MigrationTemplateResolver(mapOf("key" to "replaced"))
          .resolve(makeDefinition(params = mapOf("#{key}" to "value")))
  assertThat(resolved.params).containsKey("#{key}")
}
```

- [ ] **Step 4: Run the tests — expect the contentType and params tests to fail**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.MigrationTemplateResolverTest" -x ktfmtCheck -x detekt
```

Expected: `resolve_substitutesPlaceholderInContentType` and `resolve_substitutesPlaceholderInParamsValues` FAIL. (`resolve_doesNotSubstituteParamsKeys` may pass if params are simply copied.)

- [ ] **Step 5: Update `resolve()` in `MigrationTemplateResolver.kt` to cover contentType and params**

Replace the entire `resolve()` method:

```kotlin
fun resolve(
    definition: MigrationFile.MigrationFileRequestDefinition
): MigrationFile.MigrationFileRequestDefinition =
    // method is intentionally not substituted
    definition.copy(
        path = substitute(definition.path),
        contentType = definition.contentType?.let { substitute(it) },
        params = definition.params?.mapValues { (_, v) -> substitute(v) },
        body = definition.body?.let { substitute(it) },
    )
```

- [ ] **Step 6: Add `resolveContents()` method to `MigrationTemplateResolver.kt`**

Append after `resolve()`:

```kotlin
internal fun resolveContents(
    contents: MigrationFile.MigrationFileContents
): MigrationFile.MigrationFileContents =
    contents.copy(requests = contents.requests.map { resolve(it) })
```

- [ ] **Step 7: Run tests — expect all to pass**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.MigrationTemplateResolverTest" -x ktfmtCheck -x detekt
```

Expected: all 16 tests PASS.

- [ ] **Step 8: Format and commit**

```bash
./gradlew :esque-core:ktfmtFormat
git add esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt
git commit -m "Expand template substitution scope to contentType and params values"
```

---

## Task 2: Refactor `MigrationFileLoader` — canonical YAML checksum and two-pass load

Replace the raw-bytes checksum with a canonical YAML checksum computed on resolved request definitions. The loader gains a `MigrationTemplateResolver` constructor parameter (defaulted to an empty resolver so existing callers compile). `load()` becomes two-pass: parse all → validate all missing vars → resolve + hash. A new `MigrationFileLoaderTest` covers the checksum unit tests; `MigrationFileLoaderIT` is updated to supply the resolver.

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt`
- Create: `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderTest.kt`
- Modify: `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt`

- [ ] **Step 1: Create `MigrationFileLoaderTest.kt` with checksum unit tests**

Create `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderTest.kt`:

```kotlin
package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.loesak.esque.core.yaml.model.MigrationFile

class MigrationFileLoaderTest {

  private fun req(
      method: String = "PUT",
      path: String = "/index",
      contentType: String? = null,
      params: Map<String, String>? = null,
      body: String? = null,
  ) =
      MigrationFile.MigrationFileRequestDefinition(
          method = method, path = path, contentType = contentType, params = params, body = body)

  private fun contents(vararg requests: MigrationFile.MigrationFileRequestDefinition) =
      MigrationFile.MigrationFileContents(requests = requests.toList())

  @Test
  fun calculateChecksum_sameContents_sameChecksum() {
    val c = contents(req(path = "/index-v1", contentType = "application/json; charset=utf-8"))
    assertThat(MigrationFileLoader.calculateChecksum(c))
        .isEqualTo(MigrationFileLoader.calculateChecksum(c))
  }

  @Test
  fun calculateChecksum_resolvedTemplateMatchesHardcoded() {
    val hardcoded = contents(req(path = "/my-index-v1"))
    val withTemplate = contents(req(path = "/#{name}"))
    val resolved =
        MigrationTemplateResolver(mapOf("name" to "my-index-v1")).resolveContents(withTemplate)
    assertThat(MigrationFileLoader.calculateChecksum(hardcoded))
        .isEqualTo(MigrationFileLoader.calculateChecksum(resolved))
  }

  @Test
  fun calculateChecksum_requestsReordered_differentChecksum() {
    val a = req(path = "/path-a")
    val b = req(path = "/path-b")
    assertThat(MigrationFileLoader.calculateChecksum(contents(a, b)))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(b, a)))
  }

  @Test
  fun calculateChecksum_requestInserted_differentChecksum() {
    val a = req(path = "/path-a")
    val b = req(path = "/path-b")
    assertThat(MigrationFileLoader.calculateChecksum(contents(a)))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(a, b)))
  }

  @Test
  fun calculateChecksum_methodChanged_differentChecksum() {
    assertThat(MigrationFileLoader.calculateChecksum(contents(req(method = "PUT"))))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(req(method = "POST"))))
  }

  @Test
  fun calculateChecksum_pathChanged_differentChecksum() {
    assertThat(MigrationFileLoader.calculateChecksum(contents(req(path = "/index-v1"))))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(req(path = "/index-v2"))))
  }

  @Test
  fun calculateChecksum_bodyChanged_differentChecksum() {
    assertThat(
            MigrationFileLoader.calculateChecksum(contents(req(body = """{"replicas":1}"""))))
        .isNotEqualTo(
            MigrationFileLoader.calculateChecksum(contents(req(body = """{"replicas":2}"""))))
  }

  @Test
  fun calculateChecksum_paramsInDifferentInsertionOrder_sameChecksum() {
    val r1 = req(params = mapOf("b" to "2", "a" to "1"))
    val r2 = req(params = mapOf("a" to "1", "b" to "2"))
    assertThat(MigrationFileLoader.calculateChecksum(contents(r1)))
        .isEqualTo(MigrationFileLoader.calculateChecksum(contents(r2)))
  }
}
```

- [ ] **Step 2: Run the test file — expect compilation failure (calculateChecksum not yet internal)**

```bash
./gradlew :esque-core:compileTestKotlin 2>&1 | grep -A2 "error:"
```

Expected: compile error referencing `calculateChecksum` or `MigrationFileLoader.calculateChecksum` not accessible.

- [ ] **Step 3: Replace `MigrationFileLoader.kt` with the refactored implementation**

Replace the entire contents of `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt`:

```kotlin
package org.loesak.esque.core.yaml

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.loesak.esque.core.yaml.model.MigrationFile
import tools.jackson.databind.SerializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

private val log = KotlinLogging.logger {}

internal class MigrationFileLoader(
    private val templateResolver: MigrationTemplateResolver =
        MigrationTemplateResolver(emptyMap()),
) {

  fun load(): List<MigrationFile> {
    log.info { "Loading migration files from [$MIGRATION_DEFINITION_DIRECTORY]" }

    val rawFiles =
        Files.list(
                Paths.get(
                    checkNotNull(
                            javaClass.classLoader.getResource("$MIGRATION_DEFINITION_DIRECTORY/")) {
                              "Migration directory not found on classpath"
                            }
                        .toURI()))
            .filter(Files::isRegularFile)
            .filter { FILE_NAME_PATTERN.matches(it.toFile().name) }
            .map { readRaw(it) }
            .sorted()
            .toList()

    log.info { "Found [${rawFiles.size}] migration files" }

    templateResolver.validate(rawFiles)

    return rawFiles.map { file ->
      val resolvedContents = templateResolver.resolveContents(file.contents)
      file.copy(
          metadata = file.metadata.copy(checksum = calculateChecksum(resolvedContents)),
          contents = resolvedContents,
      )
    }
  }

  companion object {
    private const val MIGRATION_DEFINITION_DIRECTORY = "es.migration"
    private const val MIGRATION_DEFINITION_FILE_NAME_REGEX = "^V((\\d+\\.?)+)__(\\w+)\\.yml$"
    private val FILE_NAME_PATTERN = Regex(MIGRATION_DEFINITION_FILE_NAME_REGEX)

    private val YAML_MAPPER =
        YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val YAML_MAPPER_SORTED =
        YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build()
    private val MESSAGE_DIGEST: MessageDigest = MessageDigest.getInstance("MD5")

    private fun readRaw(path: Path): MigrationFile {
      val filename = path.toFile().name
      log.info { "Reading contents of migration file [$filename]" }

      val match =
          FILE_NAME_PATTERN.matchEntire(filename)
              ?: error("filename does not match expected pattern")

      return try {
        MigrationFile(
            metadata =
                MigrationFile.MigrationFileMetadata(
                    filename = filename,
                    version = match.groupValues[1],
                    description = match.groupValues[3],
                    checksum = 0,
                ),
            contents =
                YAML_MAPPER.readValue(
                    Files.newInputStream(path),
                    MigrationFile.MigrationFileContents::class.java,
                ),
        )
      } catch (e: Exception) {
        throw RuntimeException("failed to read the contents of migration file [$filename]", e)
      }
    }

    internal fun calculateChecksum(contents: MigrationFile.MigrationFileContents): Int {
      MESSAGE_DIGEST.reset()
      MESSAGE_DIGEST.update(YAML_MAPPER_SORTED.writeValueAsBytes(contents))
      return ByteBuffer.wrap(MESSAGE_DIGEST.digest()).int
    }
  }
}
```

Key changes from the original:
- Constructor now accepts `templateResolver` (defaults to empty resolver so `Esque.kt` still compiles until Task 3)
- `read()` renamed to `readRaw()`, checksum computation removed from it (checksum placeholder `0` used)
- `load()` adds two-pass logic: call `templateResolver.validate(rawFiles)` after parsing, then resolve + hash
- `calculateChecksum` now takes `MigrationFileContents` and is `internal`; uses `YAML_MAPPER_SORTED` for sorted-key serialization
- `YAML_MAPPER_SORTED` added to companion — a `YAMLMapper` with `ORDER_MAP_ENTRIES_BY_KEYS` enabled

- [ ] **Step 4: Update `MigrationFileLoaderIT.kt` to supply a resolver with the required `templatedIndexName` property, and add a missing-variable test**

Replace the entire contents of `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt`:

```kotlin
package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MigrationFileLoaderIT {

  private val resolver = MigrationTemplateResolver(mapOf("templatedIndexName" to "test-index-v4"))
  private val loader = MigrationFileLoader(resolver)

  @Test
  fun load_discoversAllMigrationFiles() {
    val files = loader.load()
    assertThat(files).hasSize(4)
  }

  @Test
  fun load_filesSortedByVersion() {
    val files = loader.load()
    assertThat(files[0].metadata.version).isEqualTo("1.0.0")
    assertThat(files[1].metadata.version).isEqualTo("1.1.0")
    assertThat(files[2].metadata.version).isEqualTo("2.0.0")
    assertThat(files[3].metadata.version).isEqualTo("3.0.0")
  }

  @Test
  fun load_parsesMetadataFromFilenames() {
    val files = loader.load()

    val first = files[0]
    assertThat(first.metadata.filename).isEqualTo("V1.0.0__CreateTestIndex.yml")
    assertThat(first.metadata.version).isEqualTo("1.0.0")
    assertThat(first.metadata.description).isEqualTo("CreateTestIndex")
    assertThat(first.metadata.checksum).isNotNull()

    val second = files[1]
    assertThat(second.metadata.filename).isEqualTo("V1.1.0__CreateSecondIndex.yml")
    assertThat(second.metadata.description).isEqualTo("CreateSecondIndex")

    val third = files[2]
    assertThat(third.metadata.filename).isEqualTo("V2.0.0__CreateThirdIndex.yml")
    assertThat(third.metadata.description).isEqualTo("CreateThirdIndex")

    val fourth = files[3]
    assertThat(fourth.metadata.filename).isEqualTo("V3.0.0__CreateTemplatedIndex.yml")
    assertThat(fourth.metadata.description).isEqualTo("CreateTemplatedIndex")
  }

  @Test
  fun load_calculatesChecksums() {
    val files = loader.load()
    for (file in files) {
      assertThat(file.metadata.checksum).isNotNull()
    }
    assertThat(files[0].metadata.checksum).isNotEqualTo(files[1].metadata.checksum)
  }

  @Test
  fun load_parsesRequestDefinitions() {
    val files = loader.load()

    val first = files[0]
    assertThat(first.contents.requests).hasSize(1)

    val request = first.contents.requests[0]
    assertThat(request.method).isEqualTo("PUT")
    assertThat(request.path).isEqualTo("/test-index-v1")
    assertThat(request.contentType).isEqualTo("application/json; charset=utf-8")
  }

  @Test
  fun load_checksumsAreStable() {
    val first = loader.load()
    val second = loader.load()
    for (i in first.indices) {
      assertThat(first[i].metadata.checksum).isEqualTo(second[i].metadata.checksum)
    }
  }

  @Test
  fun load_templateVariablesResolvedInReturnedFiles() {
    val files = loader.load()
    val templatedFile = files[3]
    assertThat(templatedFile.contents.requests[0].path).isEqualTo("/test-index-v4")
  }

  @Test
  fun load_withMissingTemplateVariable_throwsListingAllMissingNames() {
    val loaderWithEmptyProps = MigrationFileLoader(MigrationTemplateResolver(emptyMap()))
    assertThatThrownBy { loaderWithEmptyProps.load() }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("templatedIndexName")
  }
}
```

- [ ] **Step 5: Run all non-integration tests — expect all to pass**

```bash
./gradlew :esque-core:test -x ktfmtCheck -x detekt
```

Expected: `MigrationFileLoaderTest` (8 tests), `MigrationFileLoaderIT` (8 tests), `MigrationTemplateResolverTest` (16 tests) — all PASS.

Note: `EsqueIT` and other Docker-dependent tests are also run here. If Docker is available they should pass; if not they're expected to be skipped.

- [ ] **Step 6: Format and commit**

```bash
./gradlew :esque-core:ktfmtFormat
git add esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderTest.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt
git commit -m "Refactor MigrationFileLoader: canonical YAML checksum on resolved requests, two-pass load"
```

---

## Task 3: Update `Esque` — wire resolver to loader, remove redundant calls

With the loader now owning validation and resolution, `Esque.execute()` no longer needs to call `templateResolver.validate()` or `templateResolver.resolve()` directly. Wire the resolver into the loader constructor and clean up the dead code.

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt`

- [ ] **Step 1: Update `MigrationFileLoader` construction in `Esque.kt`**

Change line 28 (the `migrationLoader` field):

```kotlin
private val migrationLoader = MigrationFileLoader(templateResolver)
```

- [ ] **Step 2: Remove `templateResolver.validate(files)` from `execute()`**

In `execute()`, remove the line:

```kotlin
templateResolver.validate(files)
```

The full updated `execute()` method should look like:

```kotlin
fun execute() {
  log.info { "Starting esque execution" }
  try {
    initialize()

    val files = migrationLoader.load()

    val history = operations.getMigrationRecords()

    verifyStateIntegrity(files, history)
    runMigrations(files)

    log.info { "Completed esque execution" }
  } catch (e: Exception) {
    throw RuntimeException("Failed to run esque execution", e)
  }
}
```

- [ ] **Step 3: Remove `templateResolver.resolve(definition)` from `runMigrationForFile()`**

In `runMigrationForFile()`, replace:

```kotlin
val resolved = templateResolver.resolve(definition)
operations.executeMigrationDefinition(resolved)
```

with:

```kotlin
operations.executeMigrationDefinition(definition)
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew :esque-core:test -x ktfmtCheck -x detekt
```

Expected: all tests PASS.

- [ ] **Step 5: Format and commit**

```bash
./gradlew :esque-core:ktfmtFormat
git add esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt
git commit -m "Wire MigrationTemplateResolver into MigrationFileLoader; remove redundant validate/resolve calls from Esque"
```