# TypeScript Implementation Design

**Date:** 2026-07-04
**Status:** Draft — pending review
**Goal:** Add a TypeScript implementation of esque to the monorepo, published to npm as `esque-ts`, behaviorally identical to the JVM and Python implementations and validated by the existing black-box compatibility test harness.

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| ES client | Official `@elastic/elasticsearch` (^9) | Consistent with JVM and Python (both use official clients); handles auth mechanisms, retries, and typing. |
| npm package name | `esque-ts` | Mirrors the `esque-py` PyPI naming convention. |
| Snapshot publishing | Real npm registry, `dev` dist-tag | Prerelease versions (`X.Y.Z-dev.N`) are invisible to normal installs and semver ranges; closest match to the JVM `-SNAPSHOT` / Python TestPyPI flow without a second registry. |
| Toolchain | Lean Node-native: npm + `tsc` (strict) + `node:test` + Biome | Fewest moving parts; Biome is the ruff analog (one tool for lint + format); no bundler needed for a small library + CLI. |
| Module format | ESM-only (`"type": "module"`), Node >= 22 | Modern default; no CJS consumers expected for a CLI-first tool. |
| Directory | `implementations/typescript/` | Full platform name, consistent with `implementations/python/`. |

## Repository Placement

```
implementations/typescript/
├── package.json            # name: esque-ts, type: module, bin: {"esque": "dist/cli.js"}, engines: {"node": ">=22"}
├── package-lock.json
├── tsconfig.json           # strict: true, NodeNext modules, outDir dist/
├── biome.json              # lint + format; line width 120 (matches ruff config)
├── src/
│   ├── configuration.ts    # EsqueConfiguration
│   ├── esque.ts            # Esque orchestrator + verifyStateIntegrity
│   ├── cli.ts              # commander entrypoint (shebang line)
│   ├── migration/
│   │   ├── model.ts        # MigrationFile, MigrationFileMetadata, MigrationFileContents,
│   │   │                   #   MigrationFileRequestDefinition, numeric version comparison
│   │   ├── template.ts     # MigrationTemplateResolver: #{varName} validation + substitution
│   │   └── loader.ts       # MigrationFileLoader: discovery, parsing, canonical checksum
│   └── elasticsearch/
│       ├── documents.ts    # INDEX_DEFINITION, index name, wrapper-object document types
│       ├── operations.ts   # RestClientOperations over @elastic/elasticsearch
│       └── lock.ts         # ElasticsearchDocumentLock
└── tests/
    ├── model.test.ts       # version ordering (1.9.0 < 1.10.0, padding)
    ├── checksum.test.ts    # canonical checksum reference vectors
    ├── template.test.ts    # validation + substitution across all request fields
    └── integrity.test.ts   # verifyStateIntegrity error scenarios via mock client
```

**Runtime dependencies:** `@elastic/elasticsearch` (^9), `commander`, `yaml`.
**Dev dependencies:** `typescript`, `@biomejs/biome`, `tsx`, `@types/node`.

## Behavioral Contract

The implementation follows the esque-new-language-implementation skill guide exactly: same nine
building blocks, same ES document wrapper-object shapes (`{"migration": {...}}`, `{"lock": {...}}`),
camelCase field names in ES, lock doc id `lock:<migrationKey>`, `.esque` index definition,
`refresh=true` on record creation, and identical CLI option surface. Only TypeScript-specific
design points are documented below.

## TypeScript-Specific Design

### Canonical checksum

`JSON.stringify` does not sort keys, so `loader.ts` includes a small canonical-serialization
helper:

1. Build `{"requests": [request.toCanonicalDict(), ...]}` from the **resolved** requests;
   `toCanonicalDict()` returns only non-null fields with camelCase keys.
2. Recursively drop `null`/`undefined` values.
3. Serialize with keys sorted alphabetically at every level, compact separators (no spaces).
4. UTF-8 encode → `node:crypto` MD5 → `Buffer.readInt32BE(0)` (first 4 bytes as big-endian
   signed 32-bit int).

`checksum.test.ts` pins the same reference vectors as the Python `test_checksum.py`, which
guarantees cross-implementation equality; the compatibility harness verifies it end-to-end.

### Async model and lifecycle

- All ES-touching methods are `async`; the CLI awaits `execute()`.
- `Esque` constructor is `(client: Client, configuration: EsqueConfiguration, properties: Record<string, string> = {})`
  and only stores references / instantiates collaborators — no I/O at construction time
  (required so unit tests can construct with a mock client).
- `Esque` implements `close()` (try unlock → swallow "not held" errors, warn on others; then
  close the client) and `Symbol.asyncDispose` delegating to `close()` so `await using` works.
- `verifyStateIntegrity` and `verifyRecordIntegrity` are TS-`private` methods on `Esque`, using
  `configuration.migrationKey` directly. Unit tests access them via index access
  (`esque["verifyStateIntegrity"](...)`) on an instance built with a mock client — TS `private`
  is compile-time only, so this works without `any` casts beyond the index expression.

### Version comparison

`model.ts` exports a comparator: split versions on `.`, compare segment-by-segment numerically,
pad the shorter version with zeros (`1.9.0 < 1.10.0`, `2.1 == 2.1.0` for ordering purposes).
Files sort by this comparator after loading.

### Distributed lock

- ES side identical to other implementations: `op_type=create` on doc id `lock:<migrationKey>`,
  poll every 100ms via `setTimeout` until the deadline (`lockTimeoutMinutes`, default 5).
- `tryLock(timeoutMinutes): Promise<boolean>`, `unlock(): Promise<void>` (deletes the ES doc,
  releases the local guard in `finally`).
- `doLock()` returns `false` on any error (no ConflictError differentiation yet — same known
  TODO as the other implementations).
- The JVM wraps the ES lock in a local `ReentrantLock` for thread safety; Node is
  single-threaded, so the local guard is a simple held-flag maintained for API parity and to
  make `unlock()` without `tryLock()` an error.

### Error semantics

- CLI exits 1 on any error, message to `stderr` (matching JVM/Python).
- Template validation collects **all** missing variables before throwing.
- `checkMigrationIndexExists` treats 404 as `false`; `createMigrationIndex` treats 409
  (already exists) as success.

## CLI

`commander`, identical options to Clikt/Click:

```
--es-url TEXT              required
--migrations-dir TEXT      required   # CLI prepends "file:" before building EsqueConfiguration
--migration-key TEXT       required
--migration-user TEXT      optional
--lock-timeout-minutes N   default 5
--property key=value       repeatable; split on first "="
```

`package.json` declares `"bin": {"esque": "dist/cli.js"}`; `cli.ts` starts with
`#!/usr/bin/env node`. Consumers can run `npx esque-ts` or install globally.

## Compatibility Harness Registration

Add to `tests/implementations.yml`:

```yaml
typescript:
  invocation: direct
  command: ["npm", "exec", "--prefix", "implementations/typescript", "--", "tsx", "implementations/typescript/src/cli.ts"]
```

Running via `tsx` means the harness never depends on a possibly-stale `dist/` build — only
`npm ci` in `implementations/typescript/` is required beforehand (analogous to `uv run`'s
auto-sync for Python). The 17 parametrized scenarios run automatically against the new
implementation, and the non-parametrized `test_cross_implementation_record_equivalency` picks
it up too — taking the CI compat matrix from 35 to 52 tests (17 × 3 + 1).

Per existing project policy, different-language implementations are **not** interchangeable
against the same `migrationKey`; the harness tests each implementation independently against
the shared behavioral contract.

## Versioning

New `.github/version_typescript.sh`, mirroring `version_python.sh` but emitting strict SemVer:

- Exact tag `X.Y.Z` → `X.Y.Z` (release)
- Otherwise (tag + N commits) → `X.Y.Z-dev.N` (prerelease)
- No tags → `0.0.0-dev.<commit count>`

npm constraints honored: exactly three numeric segments, prerelease as `-dev.N` (PEP 440's
fourth-segment `.devN` is invalid SemVer), versions immutable once published. Note:
`X.Y.Z-dev.N` sorts *before* the released `X.Y.Z`; this is harmless because the `dev`
dist-tag and normal semver ranges shield consumers from prereleases, and it matches the
ordering semantics of PEP 440 `.devN`.

## CI

New `typescript` job in `.github/workflows/ci.yml`, following the `python` job pattern:

1. `actions/checkout` (with `fetch-depth: 0` + `fetch-tags` for git describe)
2. `actions/setup-node` — Node 24
3. `npm ci` (in `implementations/typescript/`)
4. Lint/format check: `npx biome ci src/ tests/`
5. Typecheck: `npx tsc --noEmit`
6. Unit tests: `node --import tsx --test tests/` (runs the `.test.ts` files directly, no build
   step; same invocation used by the pre-commit hook and the `npm test` script)
7. Build: `npx tsc`
8. Version: patch `package.json` via
   `npm version --no-git-tag-version "$(.github/version_typescript.sh)"`
9. Publish: `npm publish --tag dev` for non-release builds; `npm publish` (implicit `latest`)
   on GitHub release events. Auth via new `NPM_TOKEN` repository secret.

`compatibility-tests` job: add `needs: typescript` and an `npm ci` step for
`implementations/typescript/` before running pytest.

## Repo Housekeeping

- **Pre-commit hook** (`.githooks/pre-commit`): insert `[3] TypeScript checks` (biome check,
  `tsc --noEmit`, `node --test`) and renumber compat tests to `[4]`.
- **Dev container**: add Node 24 (devcontainer feature or apt setup).
- **CLAUDE.md**: add TypeScript to repository structure, build commands, code conventions, and
  registered implementations; update compat test count (35 → 52); fix the stale claim that the
  Python implementation uses httpx — it uses the official `elasticsearch` client
  (`elasticsearch>=9.0.0`).

## Testing Strategy

Matches the Python precedent exactly:

- **Unit tests** (`node:test`, no ES): the four complex areas — version ordering, canonical
  checksum, template resolution, integrity verification (with a mock ES client).
- **Integration**: the black-box compatibility harness in `tests/` is the integration layer.
  No TypeScript-specific integration tests.

## Out of Scope

- Rollback/undo, FAILED migration records, ConflictError differentiation — existing known
  TODOs shared by all implementations.
- CJS build output, Bun/Deno support.
- npm provenance/OIDC publishing (can be added later; requires workflow permission changes).
