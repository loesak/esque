# Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `EsqueConfiguration` as the single home for all behavioral settings, allowing users to specify the migration file directory (classpath or filesystem), and update the `.esque` index definition to use `auto_expand_replicas`.

**Architecture:** A new public `EsqueConfiguration` data class replaces the flat constructor parameters on `Esque`. `MigrationFileLoader` gains a `migrationDirectory: String` parameter with URI scheme-based path resolution (`classpath:` vs `file:`). The `.esque` index JSON is updated to use `auto_expand_replicas: "0-all"`. All existing callsites in integration tests and example apps are updated.

**Tech Stack:** Kotlin 2.4.0, JUnit 5, AssertJ, Gradle 9.5.1

---

## File Map

| Action | File |
|--------|------|
| Create | `esque-core/src/main/kotlin/org/loesak/esque/core/EsqueConfiguration.kt` |
| Modify | `esque-core/src/main/resources/org/loesak/esque/core/elasticsearch/esque-index-defintion.json` |
| Modify | `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt` |
| Modify | `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt` |
| Modify | `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt` |
| Modify | `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt` |
| Modify | `esque-examples/esque-example-core-simple/src/main/kotlin/org/loesak/esque/examples/simple/Application.kt` |
| Modify | `esque-examples/esque-example-core-es-auth/src/main/kotlin/org/loesak/esque/examples/simpleesauth/Application.kt` |

---

## Task 1: Create `EsqueConfiguration` data class

**Files:**
- Create: `esque-core/src/main/kotlin/org/loesak/esque/core/EsqueConfiguration.kt`

- [ ] **Step 1: Create the file**

```kotlin
package org.loesak.esque.core

data class EsqueConfiguration(
    val migrationKey: String,
    val migrationUser: String? = null,
    val migrationDirectory: String = "classpath:es.migration",
    val lockTimeoutMinutes: Long = 5,
)
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :esque-core:compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add esque-core/src/main/kotlin/org/loesak/esque/core/EsqueConfiguration.kt
git commit -m "Add EsqueConfiguration data class"
```

---

## Task 2: Update `.esque` index definition with `auto_expand_replicas`

**Files:**
- Modify: `esque-core/src/main/resources/org/loesak/esque/core/elasticsearch/esque-index-defintion.json`

- [ ] **Step 1: Add `auto_expand_replicas` to the index settings**

Replace the entire contents of `esque-index-defintion.json`:

```json
{
  "settings" : {
    "index" : {
      "number_of_shards" : "1",
      "auto_expand_replicas" : "0-all",
      "refresh_interval" : "1s"
    }
  },
  "mappings" : {
    "properties" : {
      "lock" : {
        "properties" : {
          "date" : {
            "type" : "date"
          }
        }
      },
      "migration" : {
        "properties" : {
          "checksum" : {
            "type" : "long"
          },
          "description" : {
            "type" : "keyword"
          },
          "executionTime" : {
            "type" : "long"
          },
          "filename" : {
            "type" : "keyword"
          },
          "installedOn" : {
            "type" : "date"
          },
          "migrationKey" : {
            "type" : "keyword"
          },
          "order" : {
            "type" : "long"
          },
          "version" : {
            "type" : "keyword"
          }
        }
      }
    }
  }
}
```

- [ ] **Step 2: Verify it compiles (resource is loaded at runtime, just check build)**

```bash
./gradlew :esque-core:processResources
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add esque-core/src/main/resources/org/loesak/esque/core/elasticsearch/esque-index-defintion.json
git commit -m "Update .esque index definition: add auto_expand_replicas"
```

---

## Task 3: Update `MigrationFileLoader` — URI scheme-based directory resolution

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt`
- Modify: `esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt`

- [ ] **Step 1: Add new tests to `MigrationFileLoaderIT` that use the new constructor signature**

Append these three test methods to `MigrationFileLoaderIT.kt`, inside the class body after the existing tests:

```kotlin
@Test
fun load_withExplicitClasspathScheme_loadsFiles() {
  val files = MigrationFileLoader("classpath:es.migration", resolver).load()
  assertThat(files).hasSize(4)
}

@Test
fun load_withFileScheme_loadsFiles() {
  val dirPath =
      Paths.get(javaClass.classLoader.getResource("es.migration/")!!.toURI()).toString()
  val files = MigrationFileLoader("file:$dirPath", resolver).load()
  assertThat(files).hasSize(4)
}

@Test
fun load_withUnknownScheme_failsFast() {
  assertThatThrownBy { MigrationFileLoader("unknown:es.migration", resolver).load() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("Unsupported")
}
```

Also add the `Paths` import at the top of the file:

```kotlin
import java.nio.file.Paths
```

- [ ] **Step 2: Run the tests — expect compile failure because `MigrationFileLoader` constructor does not yet accept a `String` first parameter**

```bash
./gradlew :esque-core:compileTestKotlin 2>&1 | grep -A2 "error:"
```

Expected: compile error — `MigrationFileLoader` constructor mismatch.

- [ ] **Step 3: Replace `MigrationFileLoader.kt` with the new implementation**

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
    private val migrationDirectory: String = "classpath:es.migration",
    private val templateResolver: MigrationTemplateResolver = MigrationTemplateResolver(emptyMap()),
) {

  fun load(): List<MigrationFile> {
    log.info { "Loading migration files from [$migrationDirectory]" }

    val rawFiles =
        Files.list(resolvePath(migrationDirectory))
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

  private fun resolvePath(directory: String): Path =
      when {
        directory.startsWith("classpath:") -> {
          val path = directory.removePrefix("classpath:")
          Paths.get(
              checkNotNull(javaClass.classLoader.getResource("$path/")) {
                "Migration directory not found on classpath: $path"
              }.toURI())
        }
        directory.startsWith("file:") -> Paths.get(directory.removePrefix("file:"))
        else ->
            error(
                "Unsupported migration directory scheme in '$directory'. Supported schemes: 'classpath:', 'file:'")
      }

  companion object {
    private const val MIGRATION_DEFINITION_FILE_NAME_REGEX = "^V((\\d+\\.?)+)__(\\w+)\\.yml$"
    private val FILE_NAME_PATTERN = Regex(MIGRATION_DEFINITION_FILE_NAME_REGEX)

    private val YAML_MAPPER = YAMLMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val YAML_MAPPER_SORTED =
        YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build()

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
                Files.newInputStream(path).use { stream ->
                  YAML_MAPPER.readValue(stream, MigrationFile.MigrationFileContents::class.java)
                },
        )
      } catch (e: Exception) {
        throw RuntimeException("failed to read the contents of migration file [$filename]", e)
      }
    }

    internal fun calculateChecksum(contents: MigrationFile.MigrationFileContents): Int {
      val digest = MessageDigest.getInstance("MD5")
      digest.update(YAML_MAPPER_SORTED.writeValueAsBytes(contents))
      return ByteBuffer.wrap(digest.digest()).int
    }
  }
}
```

Key changes from the original:
- Constructor takes `migrationDirectory: String` (first param, default `"classpath:es.migration"`) and `templateResolver` (second param, default empty)
- `MIGRATION_DEFINITION_DIRECTORY` constant removed; directory comes from the constructor
- `load()` calls `resolvePath(migrationDirectory)` instead of inline classpath lookup
- New private `resolvePath()` method handles `classpath:`, `file:`, and fails fast on unknown schemes

- [ ] **Step 4: Fix the existing `MigrationFileLoader(resolver)` call in `MigrationFileLoaderIT` — it now passes a `MigrationTemplateResolver` where a `String` is expected**

In `MigrationFileLoaderIT.kt`, update the `loader` field declaration and the missing-variable test:

```kotlin
private val resolver = MigrationTemplateResolver(mapOf("templatedIndexName" to "test-index-v4"))
private val loader = MigrationFileLoader("classpath:es.migration", resolver)
```

Also update the inline loader in `load_withMissingTemplateVariable_throwsListingAllMissingNames`:

```kotlin
@Test
fun load_withMissingTemplateVariable_throwsListingAllMissingNames() {
  val loaderWithEmptyProps =
      MigrationFileLoader("classpath:es.migration", MigrationTemplateResolver(emptyMap()))
  assertThatThrownBy { loaderWithEmptyProps.load() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("templatedIndexName")
}
```

- [ ] **Step 5: Run all non-Docker tests — expect all to pass**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.*" -x ktfmtCheck -x detekt
```

Expected: all `MigrationFileLoaderIT` tests (11 tests including the 3 new ones), `MigrationFileLoaderTest`, and `MigrationTemplateResolverTest` — all PASS.

- [ ] **Step 6: Format and commit**

```bash
./gradlew :esque-core:ktfmtFormat
git add esque-core/src/main/kotlin/org/loesak/esque/core/yaml/MigrationFileLoader.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/yaml/MigrationFileLoaderIT.kt
git commit -m "Add migrationDirectory param to MigrationFileLoader with classpath:/file: scheme resolution"
```

---

## Task 4: Wire `EsqueConfiguration` into `Esque.kt` and update all callsites

**Files:**
- Modify: `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt`
- Modify: `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt`
- Modify: `esque-examples/esque-example-core-simple/src/main/kotlin/org/loesak/esque/examples/simple/Application.kt`
- Modify: `esque-examples/esque-example-core-es-auth/src/main/kotlin/org/loesak/esque/examples/simpleesauth/Application.kt`

- [ ] **Step 1: Update `EsqueIT.kt` to use the new `EsqueConfiguration`-based constructor**

Replace the entire contents of `esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt`:

```kotlin
package org.loesak.esque.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.elasticsearch.client.Request
import org.junit.jupiter.api.Test
import org.loesak.esque.core.elasticsearch.RestClientOperations

class EsqueIT : AbstractElasticsearchIT() {

  private val templateProperties = mapOf("templatedIndexName" to "test-index-v4")

  @Test
  fun execute_createsEsqueIndex() {
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = "create-index-test"), templateProperties)
          .execute()
      val response = client.performRequest(Request("HEAD", "/.esque"))
      assertThat(response.statusLine.statusCode).isEqualTo(200)
    }
  }

  @Test
  fun execute_runsAllMigrations() {
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = "run-all-test"), templateProperties)
          .execute()
      assertIndexExists(client, "/test-index-v1")
      assertIndexExists(client, "/test-index-v2")
      assertIndexExists(client, "/test-index-v3")
      assertIndexExists(client, "/test-index-v4")
    }
  }

  @Test
  fun execute_recordsMigrationHistory() {
    val migrationKey = "history-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()

      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(records).hasSize(4)

      assertThat(records[0].order).isEqualTo(0)
      assertThat(records[0].filename).isEqualTo("V1.0.0__CreateTestIndex.yml")
      assertThat(records[0].version).isEqualTo("1.0.0")
      assertThat(records[0].description).isEqualTo("CreateTestIndex")

      assertThat(records[1].order).isEqualTo(1)
      assertThat(records[1].filename).isEqualTo("V1.1.0__CreateSecondIndex.yml")

      assertThat(records[2].order).isEqualTo(2)
      assertThat(records[2].filename).isEqualTo("V2.0.0__CreateThirdIndex.yml")

      assertThat(records[3].order).isEqualTo(3)
      assertThat(records[3].filename).isEqualTo("V3.0.0__CreateTemplatedIndex.yml")

      for (record in records) {
        assertThat(record.migrationKey).isEqualTo(migrationKey)
        assertThat(record.checksum).isNotNull()
        assertThat(record.installedOn).isNotNull()
        assertThat(record.executionTime).isNotNull()
        assertThat(record.executionTime).isGreaterThanOrEqualTo(0L)
      }
    }
  }

  @Test
  fun execute_isIdempotent() {
    val migrationKey = "idempotent-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val firstRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(firstRun).hasSize(4)

      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val secondRun = RestClientOperations(client, migrationKey).getMigrationRecords()
      assertThat(secondRun).hasSize(4)

      for (i in 0..3) {
        assertThat(secondRun[i].filename).isEqualTo(firstRun[i].filename)
        assertThat(secondRun[i].checksum).isEqualTo(firstRun[i].checksum)
        assertThat(secondRun[i].installedOn).isEqualTo(firstRun[i].installedOn)
      }
    }
  }

  @Test
  fun execute_differentKeysAreIndependent() {
    createRestClient().use { client ->
      val key1 = "independent-key-1"
      val key2 = "independent-key-2"

      Esque(client, EsqueConfiguration(migrationKey = key1), templateProperties).execute()

      val records1 = RestClientOperations(client, key1).getMigrationRecords()
      assertThat(records1).hasSize(4)
      records1.forEach { assertThat(it.migrationKey).isEqualTo(key1) }

      val records2 = RestClientOperations(client, key2).getMigrationRecords()
      assertThat(records2).isEmpty()
    }
  }

  @Test
  fun execute_recordsUserWhenProvided() {
    val migrationKey = "user-test"
    val user = "integration-test-user"
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = migrationKey, migrationUser = user),
              templateProperties,
          )
          .execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      for (record in records) {
        assertThat(record.installedBy).isEqualTo(user)
      }
    }
  }

  @Test
  fun execute_recordsNullUserWhenNotProvided() {
    val migrationKey = "no-user-test"
    createRestClient().use { client ->
      Esque(client, EsqueConfiguration(migrationKey = migrationKey), templateProperties).execute()
      val records = RestClientOperations(client, migrationKey).getMigrationRecords()
      for (record in records) {
        assertThat(record.installedBy).isNull()
      }
    }
  }

  @Test
  fun execute_withPropertiesAndNoPlaceholdersInFiles_succeedsAndIgnoresExtraProperties() {
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = "extra-properties-test"),
              mapOf("unused" to "value", "templatedIndexName" to "test-index-v4"),
          )
          .execute()
      assertIndexExists(client, "/test-index-v1")
    }
  }

  @Test
  fun execute_withTemplateVariables_substitutesValuesInPathAndCreatesCorrectIndex() {
    createRestClient().use { client ->
      Esque(
              client,
              EsqueConfiguration(migrationKey = "templating-substitution-test"),
              templateProperties,
          )
          .execute()
      assertIndexExists(client, "/test-index-v4")
    }
  }

  @Test
  fun execute_withMissingTemplateVariable_throwsBeforeAnyMigrationRuns() {
    createRestClient().use { client ->
      assertThatThrownBy {
            Esque(client, EsqueConfiguration(migrationKey = "missing-var-test")).execute()
          }
          .rootCause()
          .hasMessageContaining("templatedIndexName")

      val records = RestClientOperations(client, "missing-var-test").getMigrationRecords()
      assertThat(records).isEmpty()
    }
  }

  private fun assertIndexExists(client: org.elasticsearch.client.RestClient, indexPath: String) {
    val response = client.performRequest(Request("HEAD", indexPath))
    assertThat(response.statusLine.statusCode).isEqualTo(200)
  }
}
```

- [ ] **Step 2: Verify `EsqueIT` fails to compile (because `Esque` still has the old constructor)**

```bash
./gradlew :esque-core:compileTestKotlin 2>&1 | grep -A2 "error:"
```

Expected: compile error — `Esque` constructor does not accept `EsqueConfiguration`.

- [ ] **Step 3: Replace `Esque.kt` with the new implementation**

Replace the entire contents of `esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt`:

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
    private val configuration: EsqueConfiguration,
    properties: Map<String, String> = emptyMap(),
) : Closeable {

  private val migrationLoader =
      MigrationFileLoader(configuration.migrationDirectory, MigrationTemplateResolver(properties))
  private val operations = RestClientOperations(client, configuration.migrationKey)
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
            record.migrationKey == configuration.migrationKey) {
          "could not verify integrity of migration history record for filename [${record.filename}]. did you refactor your migration scripts after a previous execution?"
        }
  }

  private fun runMigrations(files: List<MigrationFile>) {
    try {
      files.forEach { file ->
        try {
          log.info { "Attempting to acquire lock for execution" }

          if (lock.tryLock(configuration.lockTimeoutMinutes, TimeUnit.MINUTES)) {
            log.info {
              "Lock acquired. Executing queries defined in migration file [${file.metadata.filename}]"
            }

            if (operations.getMigrationRecordForMigrationFile(
                file, configuration.migrationKey) != null) {
              log.info {
                "Migration for migration file [${file.metadata.filename}] and migration key [${configuration.migrationKey}] appears to already have been executed. Skipping"
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
                      migrationKey = configuration.migrationKey,
                      order = files.indexOf(file),
                      filename = file.metadata.filename,
                      version = file.metadata.version,
                      description = file.metadata.description,
                      checksum = file.metadata.checksum,
                      installedBy = configuration.migrationUser,
                      installedOn = end,
                      executionTime = duration,
                  ))
            }
          } else {
            // TODO: this could happen for long running queries. need to look for something a bit
            // smarter or allow to be configurable
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
        operations.executeMigrationDefinition(definition)
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
}
```

Key changes from the original:
- Constructor takes `configuration: EsqueConfiguration` instead of `migrationKey`, `migrationUser`
- `migrationLoader` now passes `configuration.migrationDirectory` as first arg
- `LOCK_TIMEOUT_MINUTES` constant and companion object removed; `configuration.lockTimeoutMinutes` used inline
- All `migrationKey` and `migrationUser` references replaced with `configuration.migrationKey` and `configuration.migrationUser`

- [ ] **Step 4: Compile the module to verify no errors**

```bash
./gradlew :esque-core:compileTestKotlin 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL` — `EsqueIT` now compiles against the new constructor.

- [ ] **Step 5: Update `esque-example-core-simple` Application.kt**

Replace the entire contents of `esque-examples/esque-example-core-simple/src/main/kotlin/org/loesak/esque/examples/simple/Application.kt`:

```kotlin
package org.loesak.esque.examples.simple

import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

fun main() {
  Esque(
          RestClient.builder(HttpHost("localhost", 9200, "http")).build(),
          EsqueConfiguration(migrationKey = "esque-example-core-simple"),
      )
      .use { it.execute() }
}
```

- [ ] **Step 6: Update `esque-example-core-es-auth` Application.kt**

Replace the entire contents of `esque-examples/esque-example-core-es-auth/src/main/kotlin/org/loesak/esque/examples/simpleesauth/Application.kt`:

```kotlin
package org.loesak.esque.examples.simpleesauth

import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

fun main() {
  val migrationKey = "esque-example-core-simple"
  val migrationUser = "migration-user"
  val migrationPass = "migration-p4\$\$word"

  // see
  // https://www.elastic.co/guide/en/elasticsearch/client/java-rest/7.1/_basic_authentication.html

  val credentialsProvider = BasicCredentialsProvider()
  credentialsProvider.setCredentials(
      AuthScope.ANY, UsernamePasswordCredentials(migrationUser, migrationPass))

  val client =
      RestClient.builder(HttpHost("localhost", 9200, "http"))
          .setHttpClientConfigCallback { it.setDefaultCredentialsProvider(credentialsProvider) }
          .build()

  Esque(
          client,
          EsqueConfiguration(migrationKey = migrationKey, migrationUser = migrationUser),
      )
      .use { it.execute() }
}
```

- [ ] **Step 7: Run the full build (excluding Docker integration tests)**

```bash
./gradlew build -x test 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL` — all modules compile cleanly.

- [ ] **Step 8: Run non-Docker tests**

```bash
./gradlew :esque-core:test --tests "org.loesak.esque.core.yaml.*" -x ktfmtCheck -x detekt
```

Expected: all tests PASS.

- [ ] **Step 9: Format and commit**

```bash
./gradlew ktfmtFormat
git add esque-core/src/main/kotlin/org/loesak/esque/core/Esque.kt \
        esque-core/src/test/kotlin/org/loesak/esque/core/EsqueIT.kt \
        esque-examples/esque-example-core-simple/src/main/kotlin/org/loesak/esque/examples/simple/Application.kt \
        esque-examples/esque-example-core-es-auth/src/main/kotlin/org/loesak/esque/examples/simpleesauth/Application.kt
git commit -m "Wire EsqueConfiguration into Esque; update all callsites"
```
