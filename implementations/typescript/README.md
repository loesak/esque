# esque-ts

TypeScript implementation of [Esque](../../README.md) — an Elasticsearch migration management library.

## Installation

```bash
npm install esque-ts
```

Requires Node.js 22+ and Elasticsearch 9+.

## Usage

```typescript
import { Client } from "@elastic/elasticsearch";
import { Esque, createEsqueConfiguration } from "esque-ts";

const configuration = createEsqueConfiguration({
  migrationKey: "my-service",
  migrationUser: "deploy-bot",            // optional
  migrationDirectory: "file:./migrations", // optional, default "file:es.migration"
  lockTimeoutMinutes: 5,                   // optional, default 5
});

const properties = {
  indexName: "my-index", // available as #{indexName} in migration files
};

const esque = new Esque(new Client({ node: "http://localhost:9200" }), configuration, properties);
try {
  await esque.execute();
} finally {
  await esque.close();
}
```

`Esque` also implements `Symbol.asyncDispose`, so it works with `await using` where your TypeScript/Node version supports explicit resource management:

```typescript
await using esque = new Esque(new Client({ node: "http://localhost:9200" }), configuration, properties);
await esque.execute();
```

`close()` releases the Elasticsearch client and any held distributed lock. `execute()` throws an `Error` (with the underlying failure attached via `.cause`) on failure.

#### `EsqueConfiguration` fields

| Field | Type | Default | Description |
|---|---|---|---|
| `migrationKey` | `string` | required | Unique key scoping all migration records for this service |
| `migrationUser` | `string \| null` | `null` | Label stored on each applied migration record |
| `migrationDirectory` | `string` | `"file:es.migration"` | Path to migration files, prefixed with `file:` |
| `lockTimeoutMinutes` | `number` | `5` | Distributed lock acquisition timeout |

## CLI

esque-ts also ships a standalone CLI (`bin: esque`):

```bash
npx esque-ts \
  --es-url=http://localhost:9200 \
  --migrations-dir=./migrations \
  --migration-key=my-service \
  --migration-user=deploy-bot \
  --lock-timeout-minutes=5 \
  --property=indexName=my-index
```

`--property` is repeatable for multiple template variables. Exits `1` and prints the full error chain to stderr on failure.

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
cd implementations/typescript

# Install dependencies
npm install

# Run tests
npm test

# Format
npm run format

# Lint
npm run lint

# Type check
npm run typecheck

# Build (emits dist/)
npm run build
```
