# esque
Resembles an **E**lasticsearch **S**tateful **Qu**ery **E**xecutor

## What is it

A means of repeatable, ordered execution of pre-defined queries against an Elasticsearch cluster. Esque remembers which queries it has run and only executes those that haven't been applied yet, in the order they are defined.

It is Flyway-esque but for Elasticsearch.

## What it does

- Define queries in migration files using YAML
- Execute migration files in version order
- Track which migrations have been applied
- Verify integrity between local files and applied history (checksums, ordering)
- Lock migration operations across distributed systems to ensure single execution
- Support logical separation of migration sets via a migration key
- Substitute template variables (`#{varName}`) in migration files at runtime

## What it doesn't

- Roll back on failure — back up your data and test migrations before applying them

## Prerequisites

- Elasticsearch 9+

## Implementations

Esque is available as a **JVM library** (Kotlin/Java), a **Python package**, and a **TypeScript/npm package**.

### JVM (Kotlin/Java)

Available on Maven Central. See [releases](https://github.com/loesak/esque/releases) for the latest version.

**Gradle (Kotlin DSL):**
```kotlin
implementation("org.loesak.esque:esque:<version>")
```

**Gradle (Groovy DSL):**
```groovy
implementation 'org.loesak.esque:esque:<version>'
```

**Maven:**
```xml
<dependency>
  <groupId>org.loesak.esque</groupId>
  <artifactId>esque</artifactId>
  <version><version></version>
</dependency>
```

You supply the `RestClient`, so you configure it for whatever authentication mechanism your cluster uses.

### Python

Available on PyPI:

```bash
pip install esque-py
```

### TypeScript

Available on npm:

```bash
npm install esque-ts
```

Requires Node.js 22+. Also ships a standalone CLI (`npx esque-ts`).

All three implementations share the same CLI contract, migration file format, checksum algorithm, and ES document structure, so they are interchangeable for any given migration key.

## Migration File Format

Files follow the naming convention `V{VERSION}__{DESCRIPTION}.yml` and are placed in a migrations directory:

```
V1.0.0__CreateIndex.yml
V1.1.0__AddAlias.yml
V2.0.0__UpdateMapping.yml
```

File contents:

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

Each request supports: `method` (required), `path` (required), `contentType`, `params` (key-value map), `body`. Template variables (`#{varName}`) are substituted at runtime.

## Use Cases

- Bootstrapping a new cluster: settings, index templates, aliases, users
- Application-scoped migrations: creating indexes, modifying mappings, updating aliases
- Any scenario where you need ordered, idempotent, tracked ES operations

## Known Limitations

- No rollback on failure
- No "always run" migrations
- Esque tracks history per `migrationKey` — different implementations writing to the same key must use the same checksum algorithm (all do; they use JSON canonical MD5)
