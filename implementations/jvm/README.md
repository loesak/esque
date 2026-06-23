# esque

JVM implementation of [Esque](../../README.md) — an Elasticsearch migration management library.

## Installation

Add the dependency to your build:

**Gradle (Kotlin DSL)**
```kotlin
implementation("org.loesak.esque:esque:VERSION")
```

**Gradle (Groovy DSL)**
```groovy
implementation 'org.loesak.esque:esque:VERSION'
```

**Maven**
```xml
<dependency>
    <groupId>org.loesak.esque</groupId>
    <artifactId>esque</artifactId>
    <version>VERSION</version>
</dependency>
```

Requires Java 21+ and Elasticsearch 9+.

## Usage

```kotlin
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

val client = RestClient.builder(HttpHost("localhost", 9200)).build()

val configuration = EsqueConfiguration(
    migrationKey = "my-service",
    migrationUser = "deploy-bot",                    // optional
    migrationDirectory = "classpath:es.migration",   // optional, default "classpath:es.migration"
    lockTimeoutMinutes = 5,                          // optional, default 5
)

val properties = mapOf(
    "indexName" to "my-index"   // available as #{indexName} in migration files
)

Esque(client, configuration, properties).use { esque ->
    esque.execute()
}
```

`Esque` implements `Closeable`. The `use` block ensures the Elasticsearch client is closed and any held distributed lock is released on exit. `execute()` throws `RuntimeException` on failure.

### `EsqueConfiguration` fields

| Field | Type | Default | Description |
|---|---|---|---|
| `migrationKey` | `String` | required | Unique key scoping all migration records for this service |
| `migrationUser` | `String?` | `null` | Label stored on each applied migration record |
| `migrationDirectory` | `String` | `"classpath:es.migration"` | Location of migration files — `classpath:` or `file:` prefix |
| `lockTimeoutMinutes` | `Long` | `5` | Distributed lock acquisition timeout |

## How It Works

On each `execute()` call, esque:

1. Creates the internal `.esque` index in Elasticsearch if it does not already exist.
2. Discovers and parses YAML migration files from `migrationDirectory`, sorted by version.
3. Validates that all `#{varName}` template references have a matching entry in `properties`.
4. Resolves templates and computes a checksum for each migration file.
5. Loads the applied migration history from Elasticsearch for `migrationKey`.
6. Verifies integrity — checksums, versions, and ordering of previously applied migrations must match the files on disk.
7. For each unapplied migration, acquires a distributed lock, executes the HTTP requests defined in the file, records the result, and releases the lock.

The distributed lock uses Elasticsearch's `op_type=create` to ensure only one process runs a given migration at a time, making it safe to run concurrently across multiple instances.

## Development

```bash
cd implementations/jvm

# Compile
./gradlew compileKotlin

# Build and test
./gradlew build

# Format
./gradlew ktfmtFormat

# Lint
./gradlew ktfmtCheck detekt
```
