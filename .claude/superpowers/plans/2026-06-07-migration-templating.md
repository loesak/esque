# Migration File Templating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `Esque` users to pass a `Map<String, String>` of named properties at construction time; placeholders of the form `#{variableName}` in migration file `path` and `body` fields are substituted before execution, with a fail-fast validation that catches any unresolved variable before any migration runs.

**Architecture:** A new internal `MigrationTemplateResolver` class handles validation (scanning all loaded files for `#{...}` references and checking each against the provided properties map) and resolution (substituting values just before each request is dispatched). `Esque` gains one new optional `properties` constructor parameter and delegates both operations to the resolver. `MigrationFileLoader` and checksum computation are untouched — checksums remain on raw template bytes, keeping integrity verification environment-agnostic.

**Tech Stack:** Kotlin 2.4.0, JUnit 5, AssertJ. No new dependencies.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| **Create** | `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt` | Validates that all `#{...}` references in loaded files have matching properties; substitutes values at execution time |
| **Create** | `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt` | Unit tests for the resolver (no ES, no YAML loading) |
| **Modify** | `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt` | Add `properties` constructor parameter; instantiate and use `MigrationTemplateResolver` |
| **Modify** | `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt` | Add regression IT: properties param is accepted and ignored when no `#{...}` appear in files |

---

### Task 1: `MigrationTemplateResolver` — tests then implementation

**Files:**
- Create: `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt`
- Create: `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt`

- [ ] **Step 1: Write the failing unit tests**

Create `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt`:

```kotlin
package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.loesak.esque.core.yaml.model.MigrationFile

class MigrationTemplateResolverTest {

    private fun makeDefinition(
        path: String = "/index",
        body: String? = null,
        method: String = "PUT",
        contentType: String? = null,
    ) = MigrationFile.MigrationFileRequestDefinition(method = method, path = path, body = body, contentType = contentType)

    private fun makeFile(vararg definitions: MigrationFile.MigrationFileRequestDefinition): MigrationFile =
        MigrationFile(
            metadata = MigrationFile.MigrationFileMetadata(
                filename = "V1.0.0__Test.yml",
                version = "1.0.0",
                description = "Test",
                checksum = 0,
            ),
            contents = MigrationFile.MigrationFileContents(requests = definitions.toList()),
        )

    // --- validate ---

    @Test
    fun validate_noPlaceholders_emptyProperties_succeeds() {
        MigrationTemplateResolver(emptyMap()).validate(listOf(makeFile(makeDefinition("/plain-index"))))
    }

    @Test
    fun validate_allPlaceholdersResolved_succeeds() {
        val resolver = MigrationTemplateResolver(mapOf("suffix" to "v1", "replicas" to "3"))
        resolver.validate(listOf(makeFile(makeDefinition("/test-#{suffix}", """{"replicas":#{replicas}}"""))))
    }

    @Test
    fun validate_missingVariable_throwsWithVariableName() {
        assertThatThrownBy {
            MigrationTemplateResolver(emptyMap()).validate(listOf(makeFile(makeDefinition("/#{suffix}"))))
        }.isInstanceOf(IllegalStateException::class.java)
         .hasMessageContaining("suffix")
    }

    @Test
    fun validate_multipleMissingVariables_listsAllInSingleMessage() {
        assertThatThrownBy {
            MigrationTemplateResolver(emptyMap()).validate(
                listOf(makeFile(makeDefinition("/#{prefix}-index", """{"replicas":#{replicas}}""")))
            )
        }.isInstanceOf(IllegalStateException::class.java)
         .hasMessageContaining("prefix")
         .hasMessageContaining("replicas")
    }

    @Test
    fun validate_missingVariableSpanningMultipleFiles_listsAll() {
        val resolver = MigrationTemplateResolver(mapOf("a" to "1"))
        assertThatThrownBy {
            resolver.validate(listOf(makeFile(makeDefinition("/#{a}")), makeFile(makeDefinition("/#{b}"))))
        }.isInstanceOf(IllegalStateException::class.java)
         .hasMessageContaining("b")
    }

    @Test
    fun validate_extraPropertiesNotReferencedInFiles_succeeds() {
        val resolver = MigrationTemplateResolver(mapOf("unused" to "value"))
        resolver.validate(listOf(makeFile(makeDefinition("/plain"))))
    }

    // --- resolve ---

    @Test
    fun resolve_substitutesPlaceholderInPath() {
        val resolved = MigrationTemplateResolver(mapOf("suffix" to "v1"))
            .resolve(makeDefinition("/test-#{suffix}"))
        assertThat(resolved.path).isEqualTo("/test-v1")
    }

    @Test
    fun resolve_substitutesPlaceholderInBody() {
        val resolved = MigrationTemplateResolver(mapOf("replicas" to "5"))
            .resolve(makeDefinition(body = """{"number_of_replicas":#{replicas}}"""))
        assertThat(resolved.body).isEqualTo("""{"number_of_replicas":5}""")
    }

    @Test
    fun resolve_substitutesMultiplePlaceholdersInSameField() {
        val resolved = MigrationTemplateResolver(mapOf("prefix" to "prod", "suffix" to "v1"))
            .resolve(makeDefinition("/#{prefix}-index-#{suffix}"))
        assertThat(resolved.path).isEqualTo("/prod-index-v1")
    }

    @Test
    fun resolve_nullBody_remainsNull() {
        val resolved = MigrationTemplateResolver(mapOf("x" to "y")).resolve(makeDefinition(body = null))
        assertThat(resolved.body).isNull()
    }

    @Test
    fun resolve_doesNotSubstituteInMethod() {
        val resolved = MigrationTemplateResolver(mapOf("x" to "replaced")).resolve(makeDefinition(method = "#{x}"))
        assertThat(resolved.method).isEqualTo("#{x}")
    }

    @Test
    fun resolve_doesNotSubstituteInContentType() {
        val resolved = MigrationTemplateResolver(mapOf("x" to "replaced"))
            .resolve(makeDefinition(contentType = "application/#{x}"))
        assertThat(resolved.contentType).isEqualTo("application/#{x}")
    }

    @Test
    fun resolve_emptyStringValue_substitutesToEmptyString() {
        val resolved = MigrationTemplateResolver(mapOf("empty" to "")).resolve(makeDefinition("/#{empty}-index"))
        assertThat(resolved.path).isEqualTo("/-index")
    }

    @Test
    fun resolve_variableNameWithDotAndHyphen_substitutes() {
        val resolved = MigrationTemplateResolver(mapOf("cluster.index-suffix" to "prod"))
            .resolve(makeDefinition("/#{cluster.index-suffix}"))
        assertThat(resolved.path).isEqualTo("/prod")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.MigrationTemplateResolverTest" 2>&1 | tail -20
```

Expected: compilation error — `MigrationTemplateResolver` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt`:

```kotlin
package org.loesak.esque.core.yaml

import org.loesak.esque.core.yaml.model.MigrationFile

internal class MigrationTemplateResolver(private val properties: Map<String, String>) {

    fun validate(files: List<MigrationFile>) {
        val missing =
            files
                .flatMap { it.contents.requests }
                .flatMap { definition ->
                    buildList {
                        addAll(PLACEHOLDER_PATTERN.findAll(definition.path).map { it.groupValues[1] })
                        definition.body?.let {
                            addAll(PLACEHOLDER_PATTERN.findAll(it).map { m -> m.groupValues[1] })
                        }
                    }
                }
                .filter { !properties.containsKey(it) }
                .toSet()

        check(missing.isEmpty()) {
            "migration files reference template variables with no matching properties: $missing"
        }
    }

    fun resolve(
        definition: MigrationFile.MigrationFileRequestDefinition
    ): MigrationFile.MigrationFileRequestDefinition =
        definition.copy(
            path = substitute(definition.path),
            body = definition.body?.let { substitute(it) },
        )

    private fun substitute(text: String): String =
        PLACEHOLDER_PATTERN.replace(text) { properties.getValue(it.groupValues[1]) }

    companion object {
        private val PLACEHOLDER_PATTERN = Regex("""#\{([a-zA-Z0-9._\-]+)}""")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.MigrationTemplateResolverTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all 13 tests passing.

- [ ] **Step 5: Commit**

```bash
git add esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolver.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationTemplateResolverTest.kt
git commit -m "feat: add MigrationTemplateResolver for #{} placeholder substitution"
```

---

### Task 2: Wire `MigrationTemplateResolver` into `Esque`

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt`
- Modify: `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt`

- [ ] **Step 1: Write the failing regression IT test**

Add to `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt` (inside the `EsqueIT` class, after the last existing test):

```kotlin
@Test
fun execute_withPropertiesAndNoPlaceholdersInFiles_succeedsAndIgnoresExtraProperties() {
    createRestClient().use { client ->
        Esque(client, "extra-properties-test", null, mapOf("unused" to "value")).execute()
        assertIndexExists(client, "/test-index-v1")
    }
}

@Test
fun execute_withEmptyPropertiesAndNoPlaceholdersInFiles_behavesIdenticallyToNoPropertiesArg() {
    val migrationKey = "empty-properties-test"
    createRestClient().use { client ->
        Esque(client, migrationKey, null, emptyMap()).execute()
        val records = RestClientOperations(client, migrationKey).getMigrationRecords()
        assertThat(records).hasSize(3)
    }
}
```

- [ ] **Step 2: Run the new IT tests to verify they fail**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.EsqueIT.execute_withPropertiesAndNoPlaceholdersInFiles_succeedsAndIgnoresExtraProperties" 2>&1 | tail -20
```

Expected: compilation error — `Esque` constructor does not have a `properties` parameter yet.

- [ ] **Step 3: Add `properties` parameter and wire resolver into `Esque`**

Replace the full contents of `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt`:

```kotlin
package org.loesak.esque.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.concurrent.ElasticsearchDocumentLock
import org.loesak.esque.core.elasticsearch.RestClientOperations
import org.loesak.esque.core.elasticsearch.documents.MigrationRecord
import org.loesak.esque.core.yaml.MigrationFileLoader
import org.loesak.esque.core.yaml.MigrationTemplateResolver
import org.loesak.esque.core.yaml.model.MigrationFile

private val log = KotlinLogging.logger {}

class Esque
@JvmOverloads
constructor(
    client: RestClient,
    private val migrationKey: String,
    private val migrationUser: String? = null,
    private val properties: Map<String, String> = emptyMap(),
) : Closeable {

  private val migrationLoader = MigrationFileLoader()
  private val templateResolver = MigrationTemplateResolver(properties)
  private val operations = RestClientOperations(client, migrationKey)
  private val lock: Lock = ElasticsearchDocumentLock(operations)

  override fun close() {
    try {
      lock.unlock()
    } catch (e: IllegalMonitorStateException) {
      // intentionally left blank. means lock is already unlocked
    } catch (e: Exception) {
      log.warn(e) {
        "failed to release a execution lock. you may need to manually delete the lock document yourself"
      }
    }

    try {
      operations.close()
    } catch (e: Exception) {
      log.warn(e) { "failed to close rest client. this is likely not an issue" }
    }
  }

  fun execute() {
    log.info { "Starting esque execution" }
    try {
      initialize()

      val files = migrationLoader.load()
      templateResolver.validate(files)

      val history = operations.getMigrationRecords()

      verifyStateIntegrity(files, history)
      runMigrations(files)

      log.info { "Completed esque execution" }
    } catch (e: Exception) {
      throw RuntimeException("Failed to run esque execution", e)
    }
  }

  private fun initialize() {
    log.info { "Initializing esque as needed" }
    if (!operations.checkMigrationIndexExists()) {
      operations.createMigrationIndex()
    }
  }

  private fun verifyStateIntegrity(files: List<MigrationFile>, history: List<MigrationRecord>) {
    log.info { "Verifying integrity of migration state as compared to found migration files" }

    check(history.size <= files.size) {
      "the migration records are showing more migrations than the local system defines. did you refactor your files or use an incorrect migration key?"
    }

    check(history.isEmpty() || history.size == history.last().order + 1) {
      "the migration records seem to be corrupt as some records appear to be missing."
    }

    history.forEach { record -> verifyRecordIntegrity(record, files) }

    log.info { "Integrity checks passed" }
  }

  private fun verifyRecordIntegrity(record: MigrationRecord, files: List<MigrationFile>) {
    val companion =
        files.firstOrNull { it.metadata.filename == record.filename }
            ?: error(
                "could not find migration file matching migration history record by filename [${record.filename}]")

    check(
        record.order == files.indexOf(companion) &&
            record.version == companion.metadata.version &&
            record.description == companion.metadata.description &&
            record.checksum == companion.metadata.checksum &&
            record.migrationKey == migrationKey) {
          "could not verify integrity of migration history record for filename [${record.filename}]. did you refactor your migration scripts after a previous execution?"
        }
  }

  private fun runMigrations(files: List<MigrationFile>) {
    try {
      files.forEach { file ->
        try {
          log.info { "Attempting to acquire lock for execution" }

          if (lock.tryLock(LOCK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            log.info {
              "Lock acquired. Executing queries defined in migration file [${file.metadata.filename}]"
            }

            if (operations.getMigrationRecordForMigrationFile(file, migrationKey) != null) {
              log.info {
                "Migration for migration file [${file.metadata.filename}] and migration key [$migrationKey] appears to already have been executed. Skipping"
              }
            } else {
              val start = Instant.now()
              runMigrationForFile(file)
              val end = Instant.now()
              val duration = Duration.between(start, end).toMillis()

              log.info {
                "Execution complete for migration file [${file.metadata.filename}]. Took [$duration] milliseconds"
              }

              operations.createMigrationRecord(
                  MigrationRecord(
                      migrationKey = migrationKey,
                      order = files.indexOf(file),
                      filename = file.metadata.filename,
                      version = file.metadata.version,
                      description = file.metadata.description,
                      checksum = file.metadata.checksum,
                      installedBy = migrationUser,
                      installedOn = end,
                      executionTime = duration,
                  ))
            }
          } else {
            // TODO: this could happen for long running queries. need to look for something a bit
            //   smarter or allow to be configurable
            log.error {
              "Failed to acquire lock in the allotted time period. Did a lock not get cleared as part of a previous execution?"
            }
            error("failed to acquire lock")
          }
        } catch (e: Exception) {
          throw RuntimeException(
              "Failed to execute queries in migration file [${file.metadata.filename}]", e)
          // TODO: should we write a "FAILED" migration record?
        } finally {
          log.info { "Releasing execution lock" }
          lock.unlock()
        }
      }
    } catch (e: Exception) {
      throw IllegalStateException("failed to run migrations", e)
    }
  }

  private fun runMigrationForFile(file: MigrationFile) {
    log.info { "Executing queries defined in migration file [${file.metadata.filename}]" }

    val requests = file.contents.requests
    requests.forEachIndexed { position, definition ->
      try {
        log.info {
          "Executing query in position [$position] defined in migration file [${file.metadata.filename}]"
        }
        val resolved = templateResolver.resolve(definition)
        operations.executeMigrationDefinition(resolved)
        log.info {
          "Query in position [$position] defined in migration file [${file.metadata.filename}] executed successfully"
        }
      } catch (e: Exception) {
        throw IllegalStateException(
            "Failed to execute query in position [$position] defined in migration file [${file.metadata.filename}]",
            e)
      }
    }

    log.info {
      "Execution complete for queries defined in migration file [${file.metadata.filename}]"
    }
  }

  companion object {
    private const val LOCK_TIMEOUT_MINUTES = 5L
  }
}
```

- [ ] **Step 4: Run format and lint checks**

```bash
./gradlew :esque-core:ktfmtFormat :esque-core:detekt 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. If detekt reports new violations, investigate — do not update the baseline blindly.

- [ ] **Step 5: Run all tests**

```bash
./gradlew :esque-core:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` — all existing IT tests plus the two new IT tests pass.

- [ ] **Step 6: Commit**

```bash
git add esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt
git commit -m "feat: wire MigrationTemplateResolver into Esque for #{} variable substitution"
```