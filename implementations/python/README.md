# esque-py

Python implementation of [Esque](../../README.md) — an Elasticsearch migration management library.

## Installation

```bash
pip install esque-py
```

Requires Python 3.14+ and Elasticsearch 9+.

## Usage

```python
from elasticsearch import Elasticsearch
from esque.configuration import EsqueConfiguration
from esque.esque import Esque

configuration = EsqueConfiguration(
    migration_key="my-service",
    migration_user="deploy-bot",           # optional
    migration_directory="file:./migrations",
    lock_timeout_minutes=5,                # optional, default 5
)

properties = {
    "indexName": "my-index",              # available as #{indexName} in migration files
}

with Esque(
    client=Elasticsearch("http://localhost:9200"),
    configuration=configuration,
    properties=properties,
) as esque:
    esque.execute()
```

`Esque` is a context manager that closes the Elasticsearch client and releases any held lock on exit. `execute()` raises `RuntimeError` on failure.

#### `EsqueConfiguration` fields

| Field | Type | Default | Description |
|---|---|---|---|
| `migration_key` | `str` | required | Unique key scoping all migration records for this service |
| `migration_user` | `str \| None` | `None` | Label stored on each applied migration record |
| `migration_directory` | `str` | `"file:es.migration"` | Path to migration files, prefixed with `file:` |
| `lock_timeout_minutes` | `int` | `5` | Distributed lock acquisition timeout |

## How It Works

On each `execute()` call, esque:

1. Creates the internal `.esque` index in Elasticsearch if it does not already exist.
2. Discovers and parses YAML migration files from `migration_directory`, sorted by version.
3. Validates that all `#{varName}` template references have a matching entry in `properties`.
4. Resolves templates and computes a checksum for each migration file.
5. Loads the applied migration history from Elasticsearch for `migration_key`.
6. Verifies integrity — checksums, versions, and ordering of previously applied migrations must match the files on disk.
7. For each unapplied migration, acquires a distributed lock, executes the HTTP requests defined in the file, records the result, and releases the lock.

The distributed lock uses Elasticsearch's `op_type=create` to ensure only one process runs a given migration at a time, making it safe to run concurrently across multiple instances.

## Development

```bash
# Install dependencies
uv sync

# Run tests
uv run pytest

# Format
uv run ruff format src/

# Lint
uv run ruff check src/

# Type check
uv run pyright
```
