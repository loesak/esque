# TypeScript Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a TypeScript implementation of esque at `implementations/typescript/`, published to npm as `esque-ts`, behaviorally identical to the JVM/Python implementations and validated by the existing compatibility harness.

**Architecture:** Module-for-module mirror of `implementations/python/src/esque/` (the most recent reference port). ESM-only Node >= 22 package; official `@elastic/elasticsearch` client; all ES-touching code async. The compat harness invokes the CLI via `tsx` directly against `src/` so no build step is needed for tests.

**Tech Stack:** TypeScript (strict), npm, `tsc` build, `node:test` + `tsx` for unit tests, Biome for lint/format, `commander` CLI, `yaml` parser.

**Spec:** `.claude/superpowers/specs/2026-07-04-typescript-implementation-design.md`

**Reference implementation:** `implementations/python/src/esque/` — when in doubt about behavior or error messages, mirror it exactly. Error message *strings* should match Python's (the compat harness greps stderr in some scenarios and consistency helps debugging).

**Note on commits:** the repo pre-commit hook runs JVM checks + Python checks + the full compat suite (~4 minutes). That is expected; don't bypass with `--no-verify`. Commit at the end of each task, not each step.

---

## Task 0: Verify local prerequisites

**Files:** none

- [ ] **Step 1: Check Node and npm are available and recent enough**

Run: `node --version && npm --version`
Expected: Node >= 22.x. If Node is missing or too old, stop and report — do not attempt to install system-wide tooling without asking.

---

## Task 1: Project scaffolding

**Files:**
- Create: `implementations/typescript/package.json`
- Create: `implementations/typescript/tsconfig.json`
- Create: `implementations/typescript/tsconfig.build.json`
- Create: `implementations/typescript/biome.json`
- Create: `implementations/typescript/.gitignore`
- Create: `implementations/typescript/src/` and `implementations/typescript/tests/` directories

- [ ] **Step 1: Create `implementations/typescript/package.json`**

```json
{
  "name": "esque-ts",
  "version": "0.0.0",
  "description": "Esque (Elasticsearch Stateful Query Executor) — migration management for Elasticsearch, like Flyway for ES clusters",
  "license": "Apache-2.0",
  "type": "module",
  "engines": {
    "node": ">=22"
  },
  "bin": {
    "esque": "dist/cli.js"
  },
  "exports": {
    ".": "./dist/esque.js"
  },
  "files": [
    "dist"
  ],
  "repository": {
    "type": "git",
    "url": "git+https://github.com/loesak/esque.git"
  },
  "scripts": {
    "build": "tsc -p tsconfig.build.json",
    "typecheck": "tsc --noEmit",
    "lint": "biome ci src tests",
    "format": "biome format --write src tests",
    "test": "node --import tsx --test tests/*.test.ts",
    "prepublishOnly": "npm run build"
  },
  "dependencies": {
    "@elastic/elasticsearch": "^9.0.0",
    "commander": "^14.0.0",
    "yaml": "^2.7.0"
  },
  "devDependencies": {
    "@biomejs/biome": "^2.0.0",
    "@types/node": "^22.0.0",
    "tsx": "^4.19.0",
    "typescript": "^5.7.0"
  }
}
```

(The `version` field is a placeholder — CI patches it via `npm version --no-git-tag-version` before publishing, mirroring how `version_python.sh` output is sed-ed into `pyproject.toml`.)

- [ ] **Step 2: Create `implementations/typescript/tsconfig.json`** (typecheck config — covers src AND tests)

```json
{
  "compilerOptions": {
    "target": "es2023",
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "forceConsistentCasingInFileNames": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src", "tests"]
}
```

- [ ] **Step 3: Create `implementations/typescript/tsconfig.build.json`** (build config — src only, emits `dist/`)

```json
{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "noEmit": false,
    "outDir": "dist",
    "rootDir": "src",
    "declaration": true,
    "sourceMap": true
  },
  "include": ["src"]
}
```

- [ ] **Step 4: Create `implementations/typescript/biome.json`**

```json
{
  "$schema": "./node_modules/@biomejs/biome/configuration_schema.json",
  "files": {
    "includes": ["src/**", "tests/**"]
  },
  "formatter": {
    "enabled": true,
    "indentStyle": "space",
    "indentWidth": 2,
    "lineWidth": 120
  },
  "linter": {
    "enabled": true,
    "rules": {
      "recommended": true
    }
  },
  "javascript": {
    "formatter": {
      "quoteStyle": "double"
    }
  }
}
```

(If `biome ci` later complains about the config shape, run `npx biome migrate --write` — the schema key names occasionally shift between Biome majors; accept whatever the migration produces.)

- [ ] **Step 5: Create `implementations/typescript/.gitignore`**

```
node_modules/
dist/
```

- [ ] **Step 6: Install dependencies**

Run: `cd implementations/typescript && npm install`
Expected: `package-lock.json` created, no errors. `package-lock.json` MUST be committed (CI uses `npm ci`).

- [ ] **Step 7: Sanity-check the toolchain**

Run: `cd implementations/typescript && npx tsc --noEmit && npx biome ci src tests 2>&1 || true`
Expected: `tsc` succeeds trivially (no inputs yet is OK, or "No inputs were found" — if that error appears, create an empty placeholder `src/configuration.ts` containing only `export {};`; it gets real content in Task 2). Biome may report "no files" — fine.

- [ ] **Step 8: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: project scaffolding"
```

---

## Task 2: Configuration and migration model (TDD)

**Files:**
- Create: `implementations/typescript/src/configuration.ts`
- Create: `implementations/typescript/src/migration/model.ts`
- Test: `implementations/typescript/tests/model.test.ts`

- [ ] **Step 1: Write the failing test** — port of `implementations/python/tests/test_model.py`

`implementations/typescript/tests/model.test.ts`:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";
import type { MigrationFile } from "../src/migration/model.js";
import { compareMigrationFiles } from "../src/migration/model.js";

function file(version: string, description = "Test"): MigrationFile {
  return {
    metadata: { filename: `V${version}__${description}.yml`, version, description, checksum: 0 },
    contents: { requests: [] },
  };
}

test("numeric segment ordering: 1.9.0 < 1.10.0", () => {
  assert.ok(compareMigrationFiles(file("1.9.0"), file("1.10.0")) < 0);
});

test("major ordering", () => {
  assert.ok(compareMigrationFiles(file("1.0.0"), file("2.0.0")) < 0);
});

test("minor ordering", () => {
  assert.ok(compareMigrationFiles(file("1.0.0"), file("1.1.0")) < 0);
});

test("patch ordering", () => {
  assert.ok(compareMigrationFiles(file("1.0.0"), file("1.0.1")) < 0);
});

test("unequal segment count: 1.0 < 1.0.1", () => {
  assert.ok(compareMigrationFiles(file("1.0"), file("1.0.1")) < 0);
});

test("equal versions compare as 0", () => {
  assert.equal(compareMigrationFiles(file("1.0.0"), file("1.0.0")), 0);
});

test("sort order", () => {
  const files = [file("1.10.0"), file("2.0.0"), file("1.9.0"), file("1.0.0")];
  files.sort(compareMigrationFiles);
  assert.deepEqual(
    files.map((f) => f.metadata.version),
    ["1.0.0", "1.9.0", "1.10.0", "2.0.0"],
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd implementations/typescript && npm test`
Expected: FAIL — cannot find module `../src/migration/model.js`

- [ ] **Step 3: Write `implementations/typescript/src/configuration.ts`**

```typescript
export interface EsqueConfiguration {
  readonly migrationKey: string;
  readonly migrationUser: string | null;
  readonly migrationDirectory: string;
  readonly lockTimeoutMinutes: number;
}

export function createEsqueConfiguration(options: {
  migrationKey: string;
  migrationUser?: string | null;
  migrationDirectory?: string;
  lockTimeoutMinutes?: number;
}): EsqueConfiguration {
  return {
    migrationKey: options.migrationKey,
    migrationUser: options.migrationUser ?? null,
    migrationDirectory: options.migrationDirectory ?? "file:es.migration",
    lockTimeoutMinutes: options.lockTimeoutMinutes ?? 5,
  };
}
```

(Defaults mirror the Python `EsqueConfiguration` dataclass: `migration_directory="file:es.migration"`, `lock_timeout_minutes=5`.)

- [ ] **Step 4: Write `implementations/typescript/src/migration/model.ts`**

```typescript
export interface MigrationFileRequestDefinition {
  readonly method: string;
  readonly path: string;
  readonly contentType: string | null;
  readonly params: Readonly<Record<string, string>> | null;
  readonly body: string | null;
}

export interface CanonicalRequest {
  method: string;
  path: string;
  body?: string;
  contentType?: string;
  params?: Record<string, string>;
}

// Only non-null fields, camelCase keys — this shape feeds the checksum.
export function toCanonicalDict(request: MigrationFileRequestDefinition): CanonicalRequest {
  const d: CanonicalRequest = { method: request.method, path: request.path };
  if (request.body !== null) {
    d.body = request.body;
  }
  if (request.contentType !== null) {
    d.contentType = request.contentType;
  }
  if (request.params !== null) {
    d.params = { ...request.params };
  }
  return d;
}

export interface MigrationFileMetadata {
  readonly filename: string;
  readonly version: string;
  readonly description: string;
  readonly checksum: number;
}

export interface MigrationFileContents {
  readonly requests: readonly MigrationFileRequestDefinition[];
}

export interface MigrationFile {
  readonly metadata: MigrationFileMetadata;
  readonly contents: MigrationFileContents;
}

// Numeric per-segment comparison; shorter versions padded with zeros (1.9.0 < 1.10.0, 1.0 < 1.0.1).
// Ties broken by description, mirroring the Python MigrationFile.__lt__.
export function compareMigrationFiles(a: MigrationFile, b: MigrationFile): number {
  const av = a.metadata.version.split(".").map(Number);
  const bv = b.metadata.version.split(".").map(Number);
  const len = Math.max(av.length, bv.length);
  for (let i = 0; i < len; i++) {
    const diff = (av[i] ?? 0) - (bv[i] ?? 0);
    if (diff !== 0) {
      return diff;
    }
  }
  if (a.metadata.description < b.metadata.description) {
    return -1;
  }
  if (a.metadata.description > b.metadata.description) {
    return 1;
  }
  return 0;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd implementations/typescript && npm test`
Expected: PASS (7 tests)

- [ ] **Step 6: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 7: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: configuration and migration model"
```

---

## Task 3: Template resolver (TDD)

**Files:**
- Create: `implementations/typescript/src/migration/template.ts`
- Test: `implementations/typescript/tests/template.test.ts`

- [ ] **Step 1: Write the failing test** — port of `implementations/python/tests/test_template.py`

`implementations/typescript/tests/template.test.ts`:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";
import type { MigrationFile, MigrationFileRequestDefinition } from "../src/migration/model.js";
import { MigrationTemplateResolver } from "../src/migration/template.js";

function req(partial: Partial<MigrationFileRequestDefinition> & { method: string; path: string }): MigrationFileRequestDefinition {
  return { contentType: null, params: null, body: null, ...partial };
}

function fileOf(...requests: MigrationFileRequestDefinition[]): MigrationFile {
  return {
    metadata: { filename: "V1.0.0__Test.yml", version: "1.0.0", description: "Test", checksum: 0 },
    contents: { requests },
  };
}

test("validate passes when all vars present", () => {
  const r = req({ method: "PUT", path: "/#{indexName}" });
  new MigrationTemplateResolver({ indexName: "my-index" }).validate([fileOf(r)]);
});

test("validate throws on missing var", () => {
  const r = req({ method: "PUT", path: "/#{missing}" });
  assert.throws(() => new MigrationTemplateResolver({}).validate([fileOf(r)]), /missing/);
});

test("validate collects all missing vars", () => {
  const r = req({ method: "PUT", path: "/#{a}", body: "#{b}" });
  assert.throws(
    () => new MigrationTemplateResolver({}).validate([fileOf(r)]),
    (error: Error) => error.message.includes("a") && error.message.includes("b"),
  );
});

test("validate checks body, params, and contentType", () => {
  const r = req({ method: "POST", path: "/", contentType: "#{ct}", params: { k: "#{v}" }, body: "#{body}" });
  assert.throws(
    () => new MigrationTemplateResolver({}).validate([fileOf(r)]),
    (error: Error) => error.message.includes("ct") && error.message.includes("v") && error.message.includes("body"),
  );
});

test("resolve substitutes path", () => {
  const r = req({ method: "PUT", path: "/#{indexName}" });
  const result = new MigrationTemplateResolver({ indexName: "my-index" }).resolve(r);
  assert.equal(result.path, "/my-index");
});

test("resolve substitutes body", () => {
  const r = req({ method: "POST", path: "/", body: '{"index": "#{name}"}' });
  const result = new MigrationTemplateResolver({ name: "test" }).resolve(r);
  assert.equal(result.body, '{"index": "test"}');
});

test("resolve substitutes params values", () => {
  const r = req({ method: "GET", path: "/", params: { q: "#{query}" } });
  const result = new MigrationTemplateResolver({ query: "value" }).resolve(r);
  assert.deepEqual(result.params, { q: "value" });
});

test("resolve substitutes contentType", () => {
  const r = req({ method: "PUT", path: "/", contentType: "#{ct}" });
  const result = new MigrationTemplateResolver({ ct: "application/json" }).resolve(r);
  assert.equal(result.contentType, "application/json");
});

test("resolve does not substitute method", () => {
  const r = req({ method: "PUT", path: "/index" });
  const result = new MigrationTemplateResolver({}).resolve(r);
  assert.equal(result.method, "PUT");
});

test("resolve handles no template vars", () => {
  const r = req({ method: "DELETE", path: "/index", body: '{"key": "value"}' });
  const result = new MigrationTemplateResolver({}).resolve(r);
  assert.equal(result.path, "/index");
  assert.equal(result.body, '{"key": "value"}');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd implementations/typescript && npm test`
Expected: FAIL — cannot find module `../src/migration/template.js`

- [ ] **Step 3: Write `implementations/typescript/src/migration/template.ts`**

```typescript
import type { MigrationFile, MigrationFileContents, MigrationFileRequestDefinition } from "./model.js";

const PLACEHOLDER_PATTERN = /#\{([a-zA-Z0-9._-]+)\}/g;

export class MigrationTemplateResolver {
  private readonly properties: Readonly<Record<string, string>>;

  constructor(properties: Readonly<Record<string, string>>) {
    this.properties = properties;
  }

  // Collects ALL missing variables before throwing.
  validate(files: readonly MigrationFile[]): void {
    const missing = new Set<string>();
    for (const file of files) {
      for (const request of file.contents.requests) {
        const texts = [
          request.path,
          request.contentType ?? "",
          request.body ?? "",
          ...Object.values(request.params ?? {}),
        ];
        for (const text of texts) {
          for (const match of text.matchAll(PLACEHOLDER_PATTERN)) {
            const key = match[1];
            if (key !== undefined && !(key in this.properties)) {
              missing.add(key);
            }
          }
        }
      }
    }
    if (missing.size > 0) {
      throw new Error(
        `migration files reference template variables with no matching properties: ${[...missing].join(", ")}`,
      );
    }
  }

  // Substitutes #{varName} in path, contentType, params values, body — NOT method.
  resolve(definition: MigrationFileRequestDefinition): MigrationFileRequestDefinition {
    return {
      method: definition.method,
      path: this.substitute(definition.path),
      contentType: definition.contentType !== null ? this.substitute(definition.contentType) : null,
      params:
        definition.params !== null
          ? Object.fromEntries(Object.entries(definition.params).map(([k, v]) => [k, this.substitute(v)]))
          : null,
      body: definition.body !== null ? this.substitute(definition.body) : null,
    };
  }

  resolveContents(contents: MigrationFileContents): MigrationFileContents {
    return { requests: contents.requests.map((r) => this.resolve(r)) };
  }

  private substitute(text: string): string {
    return text.replace(PLACEHOLDER_PATTERN, (_match, key: string) => {
      const value = this.properties[key];
      if (value === undefined) {
        throw new Error(`unresolved template variable '#{${key}}' — was validate() called?`);
      }
      return value;
    });
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd implementations/typescript && npm test`
Expected: PASS (7 model + 10 template tests)

- [ ] **Step 5: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 6: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: template resolver"
```

---

## Task 4: Canonical checksum (TDD)

**Files:**
- Create: `implementations/typescript/src/migration/loader.ts` (checksum half only; `MigrationFileLoader` added in Task 5)
- Test: `implementations/typescript/tests/checksum.test.ts`

- [ ] **Step 1: Write the failing test** — port of `implementations/python/tests/test_checksum.py`

`implementations/typescript/tests/checksum.test.ts`:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";
import { calculateChecksum } from "../src/migration/loader.js";
import type { MigrationFileRequestDefinition } from "../src/migration/model.js";

function req(partial: Partial<MigrationFileRequestDefinition> & { method: string; path: string }): MigrationFileRequestDefinition {
  return { contentType: null, params: null, body: null, ...partial };
}

function checksum(requests: MigrationFileRequestDefinition[]): number {
  return calculateChecksum({ requests });
}

test("result is a signed 32-bit integer", () => {
  const result = checksum([req({ method: "PUT", path: "/test-index" })]);
  assert.ok(Number.isInteger(result));
  assert.ok(result >= -(2 ** 31) && result <= 2 ** 31 - 1);
});

test("deterministic", () => {
  const requests = [req({ method: "PUT", path: "/test", body: '{"settings": {}}' })];
  assert.equal(checksum(requests), checksum(requests));
});

test("null fields excluded", () => {
  const r1 = req({ method: "PUT", path: "/index" });
  const r2 = req({ method: "PUT", path: "/index", body: null, contentType: null, params: null });
  assert.equal(checksum([r1]), checksum([r2]));
});

test("different content differs", () => {
  const r1 = [req({ method: "PUT", path: "/index-a" })];
  const r2 = [req({ method: "PUT", path: "/index-b" })];
  assert.notEqual(checksum(r1), checksum(r2));
});

test("cross-implementation reference vector", () => {
  // MUST equal the Python/JVM value for the identical input. -991565970 was generated from
  // the Python reference implementation via MigrationFileLoader.calculate_checksum for
  // [MigrationFileRequestDefinition(method="PUT", path="/test-index")].
  const result = checksum([req({ method: "PUT", path: "/test-index" })]);
  assert.equal(result, -991565970);
});

test("multiple requests", () => {
  const r1 = req({ method: "PUT", path: "/index" });
  const r2 = req({ method: "POST", path: "/_aliases", body: "{}" });
  const combined = checksum([r1, r2]);
  assert.notEqual(combined, checksum([r1]));
  assert.notEqual(combined, checksum([r2]));
});
```

The `-991565970` vector hard-pins cross-implementation checksum equality in a unit test (the compat harness's `test_cross_implementation_record_equivalency` also verifies it end-to-end).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd implementations/typescript && npm test`
Expected: FAIL — cannot find module `../src/migration/loader.js`

- [ ] **Step 3: Write the checksum half of `implementations/typescript/src/migration/loader.ts`**

```typescript
import { createHash } from "node:crypto";
import type { MigrationFileContents } from "./model.js";
import { toCanonicalDict } from "./model.js";

type CanonicalValue = string | number | boolean | null | CanonicalValue[] | { [key: string]: CanonicalValue | undefined };

// Canonical JSON: keys sorted alphabetically at every level, null/undefined object values
// dropped, compact separators. Matches Python's
// json.dumps(remove_nulls(data), sort_keys=True, separators=(",", ":"), ensure_ascii=False).
function canonicalJson(value: CanonicalValue): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(",")}]`;
  }
  const entries = Object.entries(value)
    .filter((entry): entry is [string, CanonicalValue] => entry[1] !== null && entry[1] !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : 1));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(",")}}`;
}

// Canonical algorithm (identical across all implementations):
// canonical JSON of {"requests": [...]} → UTF-8 → MD5 → first 4 bytes as big-endian signed int32.
export function calculateChecksum(contents: MigrationFileContents): number {
  const data: CanonicalValue = { requests: contents.requests.map((r) => toCanonicalDict(r) as CanonicalValue) };
  const digest = createHash("md5").update(canonicalJson(data), "utf8").digest();
  return digest.readInt32BE(0);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd implementations/typescript && npm test`
Expected: PASS (including the cross-implementation reference vector)

- [ ] **Step 5: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 6: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: canonical checksum"
```

---

## Task 5: Migration file loader

**Files:**
- Modify: `implementations/typescript/src/migration/loader.ts` (append loader class to the checksum code from Task 4)

No dedicated unit test (mirrors Python, where file loading is covered by the compat harness). The checksum tests from Task 4 must keep passing.

- [ ] **Step 1: Append to `implementations/typescript/src/migration/loader.ts`**

Add these imports at the top (merging with the existing ones):

```typescript
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import type { MigrationFile, MigrationFileRequestDefinition } from "./model.js";
import { compareMigrationFiles } from "./model.js";
import type { MigrationTemplateResolver } from "./template.js";
```

Then append:

```typescript
const FILE_NAME_PATTERN = /^V((\d+\.?)+)__(\w+)\.yml$/;

export class MigrationFileLoader {
  private readonly migrationDirectory: string;
  private readonly templateResolver: MigrationTemplateResolver;

  constructor(migrationDirectory: string, templateResolver: MigrationTemplateResolver) {
    this.migrationDirectory = migrationDirectory;
    this.templateResolver = templateResolver;
  }

  load(): MigrationFile[] {
    const dir = resolveDirectoryPath(this.migrationDirectory);
    const rawFiles: MigrationFile[] = [];
    for (const name of readdirSync(dir)) {
      const filePath = join(dir, name);
      if (!statSync(filePath).isFile()) {
        continue;
      }
      const match = FILE_NAME_PATTERN.exec(name);
      if (match === null) {
        continue;
      }
      rawFiles.push(readRawFile(filePath, name, match));
    }
    rawFiles.sort(compareMigrationFiles);
    this.templateResolver.validate(rawFiles);
    return rawFiles.map((file) => this.resolveFile(file));
  }

  private resolveFile(file: MigrationFile): MigrationFile {
    const resolvedContents = this.templateResolver.resolveContents(file.contents);
    return {
      metadata: { ...file.metadata, checksum: calculateChecksum(resolvedContents) },
      contents: resolvedContents,
    };
  }
}

function resolveDirectoryPath(directory: string): string {
  if (directory.startsWith("file:")) {
    return directory.slice("file:".length);
  }
  throw new Error(`unsupported migration directory scheme in '${directory}'. supported schemes: 'file:'`);
}

function readRawFile(filePath: string, filename: string, match: RegExpExecArray): MigrationFile {
  const version = match[1];
  const description = match[3];
  if (version === undefined || description === undefined) {
    throw new Error(`invalid migration filename: ${filename}`);
  }
  const data = parse(readFileSync(filePath, "utf8")) as { requests: Record<string, unknown>[] };
  return {
    metadata: { filename, version, description, checksum: 0 },
    contents: { requests: data.requests.map((raw) => parseRequest(raw)) },
  };
}

function parseRequest(raw: Record<string, unknown>): MigrationFileRequestDefinition {
  return {
    method: String(raw.method),
    path: String(raw.path),
    contentType: "contentType" in raw ? String(raw.contentType) : null,
    params:
      "params" in raw
        ? Object.fromEntries(
            Object.entries(raw.params as Record<string, unknown>).map(([k, v]) => [String(k), String(v)]),
          )
        : null,
    body: "body" in raw ? String(raw.body) : null,
  };
}
```

- [ ] **Step 2: Run all tests to verify nothing broke**

Run: `cd implementations/typescript && npm test`
Expected: PASS (all model, template, checksum tests)

- [ ] **Step 3: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 4: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: migration file loader"
```

---

## Task 6: ES document types and index definition

**Files:**
- Create: `implementations/typescript/src/elasticsearch/documents.ts`

Data-only module; covered indirectly by Task 9 unit tests and the compat harness.

- [ ] **Step 1: Write `implementations/typescript/src/elasticsearch/documents.ts`**

Mirrors `implementations/python/src/esque/elasticsearch/documents.py` exactly — same index settings/mappings, same wrapper-object shapes, camelCase field names, `installedBy` omitted when null.

```typescript
export const MIGRATION_INDEX = ".esque";
export const LOCK_ID_PREFIX = "lock";

export const INDEX_DEFINITION = {
  settings: {
    index: {
      number_of_shards: "1",
      auto_expand_replicas: "0-all",
      refresh_interval: "1s",
    },
  },
  mappings: {
    properties: {
      lock: { properties: { date: { type: "date" } } },
      migration: {
        properties: {
          checksum: { type: "long" },
          description: { type: "keyword" },
          executionTime: { type: "long" },
          filename: { type: "keyword" },
          installedOn: { type: "date" },
          migrationKey: { type: "keyword" },
          order: { type: "long" },
          version: { type: "keyword" },
        },
      },
    },
  },
} as const;

export interface MigrationRecord {
  readonly migrationKey: string;
  readonly order: number;
  readonly filename: string;
  readonly version: string;
  readonly description: string;
  readonly checksum: number;
  readonly installedBy: string | null;
  readonly installedOn: string; // ISO-8601 UTC
  readonly executionTime: number;
}

// Wrapper-object serialization — mirrors JVM @JsonTypeInfo(As.WRAPPER_OBJECT).
export function migrationRecordToDocument(record: MigrationRecord): { migration: Record<string, unknown> } {
  const doc: Record<string, unknown> = {
    migrationKey: record.migrationKey,
    order: record.order,
    filename: record.filename,
    version: record.version,
    description: record.description,
    checksum: record.checksum,
    installedOn: record.installedOn,
    executionTime: record.executionTime,
  };
  if (record.installedBy !== null) {
    doc.installedBy = record.installedBy;
  }
  return { migration: doc };
}

export function migrationRecordFromDocument(source: Record<string, unknown>): MigrationRecord {
  const raw = source.migration as Record<string, unknown>;
  return {
    migrationKey: String(raw.migrationKey),
    order: Number(raw.order),
    filename: String(raw.filename),
    version: String(raw.version),
    description: String(raw.description),
    checksum: Number(raw.checksum),
    installedBy: raw.installedBy !== undefined && raw.installedBy !== null ? String(raw.installedBy) : null,
    installedOn: String(raw.installedOn),
    executionTime: Number(raw.executionTime),
  };
}

export function migrationLockToDocument(date: Date): { lock: { date: string } } {
  return { lock: { date: date.toISOString() } };
}
```

- [ ] **Step 2: Typecheck and lint**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 3: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: ES document types and index definition"
```

---

## Task 7: REST client operations

**Files:**
- Create: `implementations/typescript/src/elasticsearch/operations.ts`

Covered by the compat harness (mirrors Python, which has no unit tests for this module).

- [ ] **Step 1: Write `implementations/typescript/src/elasticsearch/operations.ts`**

```typescript
import type { Client } from "@elastic/elasticsearch";
import { errors } from "@elastic/elasticsearch";
import type { MigrationFile, MigrationFileRequestDefinition } from "../migration/model.js";
import type { MigrationRecord } from "./documents.js";
import {
  INDEX_DEFINITION,
  LOCK_ID_PREFIX,
  MIGRATION_INDEX,
  migrationLockToDocument,
  migrationRecordFromDocument,
  migrationRecordToDocument,
} from "./documents.js";

export class RestClientOperations {
  private readonly client: Client;
  private readonly migrationKey: string;

  constructor(client: Client, migrationKey: string) {
    this.client = client;
    this.migrationKey = migrationKey;
  }

  async close(): Promise<void> {
    await this.client.close();
  }

  async checkMigrationIndexExists(): Promise<boolean> {
    return await this.client.indices.exists({ index: MIGRATION_INDEX });
  }

  async createMigrationIndex(): Promise<void> {
    try {
      await this.client.indices.create({
        index: MIGRATION_INDEX,
        settings: INDEX_DEFINITION.settings,
        mappings: INDEX_DEFINITION.mappings,
      });
    } catch (error) {
      if (isAlreadyExistsError(error)) {
        return; // another process created it first — safe
      }
      throw error;
    }
  }

  async createLockRecord(): Promise<void> {
    await this.client.index({
      index: MIGRATION_INDEX,
      id: `${LOCK_ID_PREFIX}:${this.migrationKey}`,
      document: migrationLockToDocument(new Date()),
      op_type: "create",
    });
  }

  async deleteLockRecord(): Promise<void> {
    await this.client.delete({
      index: MIGRATION_INDEX,
      id: `${LOCK_ID_PREFIX}:${this.migrationKey}`,
    });
  }

  async getMigrationRecords(): Promise<MigrationRecord[]> {
    const response = await this.client.search<Record<string, unknown>>({
      index: MIGRATION_INDEX,
      query: { bool: { filter: [{ term: { "migration.migrationKey": this.migrationKey } }] } },
      size: 10000,
    });
    const records = response.hits.hits.map((hit) => migrationRecordFromDocument(hit._source as Record<string, unknown>));
    records.sort((a, b) => a.order - b.order);
    return records;
  }

  async getMigrationRecordForMigrationFile(file: MigrationFile): Promise<MigrationRecord | null> {
    const response = await this.client.search<Record<string, unknown>>({
      index: MIGRATION_INDEX,
      query: {
        bool: {
          filter: [
            { term: { "migration.migrationKey": this.migrationKey } },
            { term: { "migration.filename": file.metadata.filename } },
          ],
        },
      },
    });
    const hits = response.hits.hits;
    if (hits.length > 1) {
      throw new Error(
        `found more than one migration record for file [${file.metadata.filename}] and migration key [${this.migrationKey}]`,
      );
    }
    const first = hits[0];
    if (first !== undefined) {
      return migrationRecordFromDocument(first._source as Record<string, unknown>);
    }
    return null;
  }

  async executeMigrationDefinition(definition: MigrationFileRequestDefinition): Promise<void> {
    const headers: Record<string, string> = {};
    if (definition.contentType !== null) {
      headers["content-type"] = definition.contentType;
    }
    await this.client.transport.request(
      {
        method: definition.method,
        path: definition.path,
        querystring: definition.params ?? undefined,
        body: definition.body ?? undefined,
      },
      { headers },
    );
  }

  async createMigrationRecord(record: MigrationRecord): Promise<void> {
    if (record.migrationKey !== this.migrationKey) {
      throw new Error("migration record migration key must match operational migration key");
    }
    await this.client.index({
      index: MIGRATION_INDEX,
      document: migrationRecordToDocument(record),
      refresh: true, // without it, reads immediately after won't see the record
    });
  }
}

function isAlreadyExistsError(error: unknown): boolean {
  if (!(error instanceof errors.ResponseError)) {
    return false;
  }
  const body = error.body as { error?: { type?: string } } | undefined;
  return body?.error?.type === "resource_already_exists_exception";
}
```

(If the client's `transport.request` signature differs in the installed v9 minor — e.g. `querystring` typing — adapt the call but keep the behavior: params go in the URL query string, body sent raw with the given content-type header, method never templated.)

- [ ] **Step 2: Typecheck and lint**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 3: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: REST client operations"
```

---

## Task 8: Distributed lock

**Files:**
- Create: `implementations/typescript/src/elasticsearch/lock.ts`

- [ ] **Step 1: Write `implementations/typescript/src/elasticsearch/lock.ts`**

```typescript
import { setTimeout as sleep } from "node:timers/promises";
import type { RestClientOperations } from "./operations.js";

const IDLE_BETWEEN_TRIES_MS = 100;

// Thrown by unlock() when the lock is not held — expected during Esque.close() after a clean run.
export class LockNotHeldError extends Error {}

// Distributed lock via ES op_type=create. The JVM wraps this in a local ReentrantLock for
// thread safety; Node is single-threaded, so a held-flag suffices — it exists to make
// unlock() without tryLock() a detectable error, and to avoid deleting another process's
// lock document from a process that never acquired it.
export class ElasticsearchDocumentLock {
  private readonly operations: RestClientOperations;
  private held = false;

  constructor(operations: RestClientOperations) {
    this.operations = operations;
  }

  async tryLock(timeoutMinutes: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMinutes * 60_000;
    for (;;) {
      if (await this.doLock()) {
        this.held = true;
        return true;
      }
      if (Date.now() >= deadline) {
        return false;
      }
      await sleep(IDLE_BETWEEN_TRIES_MS);
    }
  }

  async unlock(): Promise<void> {
    if (!this.held) {
      throw new LockNotHeldError("cannot release un-acquired lock");
    }
    this.held = false;
    try {
      await this.operations.deleteLockRecord();
    } catch (error) {
      throw new Error("Failed to release mutex", { cause: error });
    }
  }

  private async doLock(): Promise<boolean> {
    try {
      await this.operations.createLockRecord();
      return true;
    } catch {
      // TODO: differentiate ConflictError (lock exists) from other failures
      return false;
    }
  }
}
```

- [ ] **Step 2: Typecheck and lint**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean

- [ ] **Step 3: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: distributed document lock"
```

---

## Task 9: Esque orchestrator (TDD on integrity checks)

**Files:**
- Create: `implementations/typescript/src/esque.ts`
- Test: `implementations/typescript/tests/integrity.test.ts`

- [ ] **Step 1: Write the failing test** — port of `implementations/python/tests/test_integrity.py`

`implementations/typescript/tests/integrity.test.ts`:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";
import type { Client } from "@elastic/elasticsearch";
import { createEsqueConfiguration } from "../src/configuration.js";
import type { MigrationRecord } from "../src/elasticsearch/documents.js";
import { Esque } from "../src/esque.js";
import type { MigrationFile } from "../src/migration/model.js";

const SENTINEL_DATE = "2026-01-01T00:00:00.000Z";

// Constructor only stores references — no ES calls happen at init time — so an empty
// object stands in for the client.
function esque(migrationKey = "test-key"): Esque {
  return new Esque({} as unknown as Client, createEsqueConfiguration({ migrationKey }));
}

function verify(instance: Esque, files: MigrationFile[], history: MigrationRecord[]): void {
  // TS `private` is compile-time only; element access is the sanctioned escape hatch for tests.
  instance["verifyStateIntegrity"](files, history);
}

function file(version: string, description = "Test", checksum = 42): MigrationFile {
  return {
    metadata: { filename: `V${version}__${description}.yml`, version, description, checksum },
    contents: { requests: [] },
  };
}

function record(f: MigrationFile, order: number, overrides: Partial<MigrationRecord> = {}): MigrationRecord {
  return {
    migrationKey: "test-key",
    order,
    filename: f.metadata.filename,
    version: f.metadata.version,
    description: f.metadata.description,
    checksum: f.metadata.checksum,
    installedBy: null,
    installedOn: SENTINEL_DATE,
    executionTime: 0,
    ...overrides,
  };
}

test("passes with no history", () => {
  verify(esque(), [file("1.0.0"), file("1.1.0")], []);
});

test("passes with complete matching history", () => {
  const f1 = file("1.0.0");
  const f2 = file("1.1.0");
  verify(esque(), [f1, f2], [record(f1, 0), record(f2, 1)]);
});

test("passes with partial history", () => {
  const f1 = file("1.0.0");
  const f2 = file("1.1.0");
  verify(esque(), [f1, f2], [record(f1, 0)]);
});

test("throws when more records than files", () => {
  const f = file("1.0.0");
  assert.throws(() => verify(esque(), [], [record(f, 0)]), /more migrations/);
});

test("throws on gap in history", () => {
  const f1 = file("1.0.0");
  const f2 = file("1.1.0");
  const f3 = file("1.2.0");
  assert.throws(() => verify(esque(), [f1, f2, f3], [record(f1, 0), record(f3, 2)]), /corrupt/);
});

test("throws on checksum mismatch", () => {
  const f = file("1.0.0", "Test", 42);
  assert.throws(() => verify(esque(), [f], [record(f, 0, { checksum: 999 })]), /integrity/);
});

test("throws on version mismatch", () => {
  const f = file("1.0.0");
  assert.throws(() => verify(esque(), [f], [record(f, 0, { version: "9.9.9" })]), /integrity/);
});

test("throws on description mismatch", () => {
  const f = file("1.0.0", "Original");
  assert.throws(() => verify(esque(), [f], [record(f, 0, { description: "Modified" })]), /integrity/);
});

test("throws on migration key mismatch", () => {
  const f = file("1.0.0");
  assert.throws(() => verify(esque(), [f], [record(f, 0, { migrationKey: "other-key" })]), /integrity/);
});

test("throws when file missing for record", () => {
  const f = file("1.0.0");
  const orphan = record(file("1.0.0", "Ghost"), 0);
  assert.throws(() => verify(esque(), [f], [orphan]), /could not find/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd implementations/typescript && npm test`
Expected: FAIL — cannot find module `../src/esque.js`

- [ ] **Step 3: Write `implementations/typescript/src/esque.ts`**

```typescript
import type { Client } from "@elastic/elasticsearch";
import type { EsqueConfiguration } from "./configuration.js";
import type { MigrationRecord } from "./elasticsearch/documents.js";
import { ElasticsearchDocumentLock, LockNotHeldError } from "./elasticsearch/lock.js";
import { RestClientOperations } from "./elasticsearch/operations.js";
import { MigrationFileLoader } from "./migration/loader.js";
import type { MigrationFile } from "./migration/model.js";
import { MigrationTemplateResolver } from "./migration/template.js";

export class Esque {
  private readonly configuration: EsqueConfiguration;
  private readonly migrationLoader: MigrationFileLoader;
  private readonly operations: RestClientOperations;
  private readonly lock: ElasticsearchDocumentLock;

  constructor(client: Client, configuration: EsqueConfiguration, properties: Record<string, string> = {}) {
    this.configuration = configuration;
    this.migrationLoader = new MigrationFileLoader(
      configuration.migrationDirectory,
      new MigrationTemplateResolver(properties),
    );
    this.operations = new RestClientOperations(client, configuration.migrationKey);
    this.lock = new ElasticsearchDocumentLock(this.operations);
  }

  async close(): Promise<void> {
    try {
      await this.lock.unlock();
    } catch (error) {
      if (!(error instanceof LockNotHeldError)) {
        console.warn("failed to release a execution lock. you may need to manually delete the lock document yourself");
      }
    }
    try {
      await this.operations.close();
    } catch {
      console.warn("failed to close client. this is likely not an issue");
    }
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }

  async execute(): Promise<void> {
    try {
      await this.initialize();
      const files = this.migrationLoader.load();
      const history = await this.operations.getMigrationRecords();
      this.verifyStateIntegrity(files, history);
      await this.runMigrations(files);
    } catch (error) {
      throw new Error("Failed to run esque execution", { cause: error });
    }
  }

  private async initialize(): Promise<void> {
    if (!(await this.operations.checkMigrationIndexExists())) {
      await this.operations.createMigrationIndex();
    }
  }

  private verifyStateIntegrity(files: MigrationFile[], history: MigrationRecord[]): void {
    if (history.length > files.length) {
      throw new Error(
        "the migration records are showing more migrations than the local system defines. " +
          "did you refactor your files or use an incorrect migration key?",
      );
    }
    const last = history[history.length - 1];
    if (last !== undefined && history.length !== last.order + 1) {
      throw new Error("the migration records seem to be corrupt as some records appear to be missing.");
    }
    for (const record of history) {
      this.verifyRecordIntegrity(record, files);
    }
  }

  private verifyRecordIntegrity(record: MigrationRecord, files: MigrationFile[]): void {
    const companion = files.find((f) => f.metadata.filename === record.filename);
    if (companion === undefined) {
      throw new Error(
        `could not find migration file matching migration history record by filename [${record.filename}]`,
      );
    }
    if (
      record.order !== files.indexOf(companion) ||
      record.version !== companion.metadata.version ||
      record.description !== companion.metadata.description ||
      record.checksum !== companion.metadata.checksum ||
      record.migrationKey !== this.configuration.migrationKey
    ) {
      throw new Error(
        `could not verify integrity of migration history record for filename [${record.filename}]. ` +
          "did you refactor your migration scripts after a previous execution?",
      );
    }
  }

  private async runMigrations(files: MigrationFile[]): Promise<void> {
    try {
      for (const file of files) {
        try {
          if (await this.lock.tryLock(this.configuration.lockTimeoutMinutes)) {
            const existing = await this.operations.getMigrationRecordForMigrationFile(file);
            if (existing === null) {
              const start = performance.now();
              await this.runMigrationForFile(file);
              const elapsedMs = Math.round(performance.now() - start);
              await this.operations.createMigrationRecord({
                migrationKey: this.configuration.migrationKey,
                order: files.indexOf(file),
                filename: file.metadata.filename,
                version: file.metadata.version,
                description: file.metadata.description,
                checksum: file.metadata.checksum,
                installedBy: this.configuration.migrationUser,
                installedOn: new Date().toISOString(),
                executionTime: elapsedMs,
              });
            }
          } else {
            throw new Error("failed to acquire lock");
          }
        } catch (error) {
          throw new Error(`Failed to execute queries in migration file [${file.metadata.filename}]`, {
            cause: error,
          });
        } finally {
          try {
            await this.lock.unlock();
          } catch (error) {
            if (!(error instanceof LockNotHeldError)) {
              throw error;
            }
          }
        }
      }
    } catch (error) {
      throw new Error("failed to run migrations", { cause: error });
    }
  }

  private async runMigrationForFile(file: MigrationFile): Promise<void> {
    for (const [position, definition] of file.contents.requests.entries()) {
      try {
        await this.operations.executeMigrationDefinition(definition);
      } catch (error) {
        throw new Error(
          `Failed to execute query in position [${position}] in migration file [${file.metadata.filename}]`,
          { cause: error },
        );
      }
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd implementations/typescript && npm test`
Expected: PASS (all suites; integrity adds 10 tests)

- [ ] **Step 5: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean. If Biome's `noConsole`-family rules flag the `console.warn` calls in `close()`, suppress per-line with `// biome-ignore lint/suspicious/noConsole: intentional operator-facing warning` (adjust rule name to what Biome reports).

- [ ] **Step 6: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: Esque orchestrator with integrity verification"
```

---

## Task 10: CLI

**Files:**
- Create: `implementations/typescript/src/cli.ts`

- [ ] **Step 1: Write `implementations/typescript/src/cli.ts`**

```typescript
#!/usr/bin/env node
import { Client } from "@elastic/elasticsearch";
import { Command, InvalidArgumentError } from "commander";
import { createEsqueConfiguration } from "./configuration.js";
import { Esque } from "./esque.js";

function collectProperty(value: string, previous: Record<string, string>): Record<string, string> {
  const separator = value.indexOf("=");
  if (separator <= 0) {
    throw new InvalidArgumentError(`must be in key=value format, got: '${value}'`);
  }
  previous[value.slice(0, separator)] = value.slice(separator + 1);
  return previous;
}

const program = new Command()
  .name("esque")
  .description("Run Elasticsearch migrations.")
  .requiredOption("--es-url <url>", "Elasticsearch URL (e.g. http://localhost:9200)")
  .requiredOption("--migrations-dir <path>", "Path to directory containing migration YAML files")
  .requiredOption("--migration-key <key>", "Unique key scoping this migration set")
  .option("--migration-user <user>", "User to record on each migration record")
  .option(
    "--lock-timeout-minutes <n>",
    "Lock acquisition timeout in minutes",
    (value: string) => Number.parseInt(value, 10),
    5,
  )
  .option("--property <key=value>", "Template substitution property as key=value (repeatable)", collectProperty, {});

program.parse();

const opts = program.opts<{
  esUrl: string;
  migrationsDir: string;
  migrationKey: string;
  migrationUser?: string;
  lockTimeoutMinutes: number;
  property: Record<string, string>;
}>();

const configuration = createEsqueConfiguration({
  migrationKey: opts.migrationKey,
  migrationUser: opts.migrationUser ?? null,
  migrationDirectory: `file:${opts.migrationsDir}`,
  lockTimeoutMinutes: opts.lockTimeoutMinutes,
});

const esque = new Esque(new Client({ node: opts.esUrl }), configuration, opts.property);
try {
  await esque.execute();
} catch (error) {
  console.error(`Error: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
} finally {
  await esque.close();
}
```

- [ ] **Step 2: Smoke-test help output**

Run: `cd implementations/typescript && npx tsx src/cli.ts --help`
Expected: usage text listing `--es-url`, `--migrations-dir`, `--migration-key`, `--migration-user`, `--lock-timeout-minutes`, `--property`; exit 0.

- [ ] **Step 3: Smoke-test error path (no ES running)**

Run: `cd implementations/typescript && npx tsx src/cli.ts --es-url=http://localhost:1 --migrations-dir=../../tests/fixtures/single --migration-key=smoke; echo "exit=$?"`
Expected: a line starting with `Error:` on stderr and `exit=1`.

- [ ] **Step 4: Lint, format, typecheck**

Run: `cd implementations/typescript && npm run format && npx biome ci src tests && npm run typecheck`
Expected: all clean (same `noConsole` caveat as Task 9 for `console.error`).

- [ ] **Step 5: Verify the build produces a runnable CLI**

Run: `cd implementations/typescript && npm run build && node dist/cli.js --help`
Expected: same usage text; exit 0.

- [ ] **Step 6: Commit**

```bash
git add implementations/typescript
git commit -m "TypeScript implementation: CLI entrypoint"
```

---

## Task 11: Register with the compatibility harness

**Files:**
- Modify: `tests/implementations.yml`

- [ ] **Step 1: Add the typescript entry to `tests/implementations.yml`**

The file becomes:

```yaml
implementations:
  jvm:
    invocation: gradle
    gradle_dir: "implementations/jvm"
    task: "run"
  python:
    invocation: direct
    command: ["uv", "run", "--project", "implementations/python", "esque"]
  typescript:
    invocation: direct
    command: ["npm", "exec", "--prefix", "implementations/typescript", "--", "tsx", "implementations/typescript/src/cli.ts"]
```

(`direct` commands run with `cwd = ROOT_DIR` — see `tests/helpers.py` `run()` — so both the `--prefix` and the `src/cli.ts` path are repo-root-relative. `npm exec --prefix` resolves the locally installed `tsx` from the implementation's `node_modules`.)

- [ ] **Step 2: Run the compat suite against typescript only**

Run: `cd tests && uv run pytest . -v -k "typescript"`
Expected: 17 passed (Docker must be running). Failures here are real behavior differences — debug the TypeScript implementation against the Python reference, don't touch the harness.

- [ ] **Step 3: Run the cross-implementation equivalency test (now includes typescript)**

Run: `cd tests && uv run pytest . -v -k "cross_implementation"`
Expected: 1 passed — proves record fields INCLUDING CHECKSUMS match across jvm, python, and typescript.

- [ ] **Step 4: Run the full suite**

Run: `cd tests && uv run pytest . -q`
Expected: 52 passed (17 × 3 + 1)

- [ ] **Step 5: Commit**

```bash
git add tests/implementations.yml
git commit -m "Register TypeScript implementation with compatibility harness"
```

---

## Task 12: Version script

**Files:**
- Create: `.github/version_typescript.sh` (mode 755)

- [ ] **Step 1: Write `.github/version_typescript.sh`**

```bash
#!/bin/bash
# Produces a SemVer version from git tags — mirrors version_python.sh but for npm.
# Exact tag X.Y.Z → X.Y.Z (release); otherwise → X.Y.Z-dev.N (prerelease).

GIT_DESCRIBE=$(git describe --tags 2>/dev/null)

if [[ $GIT_DESCRIBE =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "$GIT_DESCRIBE"
elif [[ $GIT_DESCRIBE =~ ^([0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)-g[0-9a-f]+ ]]; then
  echo "${BASH_REMATCH[1]}-dev.${BASH_REMATCH[2]}"
else
  echo "0.0.0-dev.$(git rev-list --count HEAD 2>/dev/null || echo 0)"
fi
```

- [ ] **Step 2: Make it executable and verify output is valid SemVer**

Run: `chmod +x .github/version_typescript.sh && .github/version_typescript.sh`
Expected: something like `X.Y.Z-dev.N` (or `0.0.0-dev.N` if no tags reachable). Verify npm accepts it:

Run: `cd implementations/typescript && npm version --no-git-tag-version "$(../../.github/version_typescript.sh)" && git checkout -- package.json package-lock.json`
Expected: npm prints a `v...` version and exits 0; the checkout restores the placeholder `0.0.0`.

- [ ] **Step 3: Commit**

```bash
git add .github/version_typescript.sh
git commit -m "Add git-tag-based version script for TypeScript"
```

---

## Task 13: CI workflow

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the `typescript` job** (after the `python` job, following its pattern)

```yaml
  typescript:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0
      - uses: actions/setup-node@v5
        with:
          node-version: 24
          registry-url: https://registry.npmjs.org
      - working-directory: implementations/typescript
        run: npm ci
      - working-directory: implementations/typescript
        run: npx biome ci src tests
      - working-directory: implementations/typescript
        run: npm run typecheck
      - working-directory: implementations/typescript
        run: npm test
      - working-directory: implementations/typescript
        run: npm version --no-git-tag-version "$(../../.github/version_typescript.sh)"
      - working-directory: implementations/typescript
        run: npm run build
      - if: github.event_name != 'release'
        working-directory: implementations/typescript
        run: npm publish --tag dev
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
      - if: github.event_name == 'release'
        working-directory: implementations/typescript
        run: npm publish
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

(Check the current major of `actions/setup-node` on the marketplace and use it — the repo just bumped checkout to v7 and setup-java to v5, so use whatever is latest, not blindly `v5`.)

- [ ] **Step 2: Update the `compatibility-tests` job**

Change `needs: [jvm, python]` to `needs: [jvm, python, typescript]` and add Node setup + install after the uv setup steps:

```yaml
      - uses: actions/setup-node@v5
        with:
          node-version: 24
      - working-directory: implementations/typescript
        run: npm ci
```

- [ ] **Step 3: Validate workflow syntax**

Run: `uvx --from yamllint yamllint -d relaxed .github/workflows/ci.yml || python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('OK')"`
Expected: no syntax errors / `OK`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add TypeScript job to CI"
```

**Post-merge manual step (for the user, note it in the final report):** create the `NPM_TOKEN` repository secret (an npm automation token with publish rights for `esque-ts`) before the first CI publish run.

---

## Task 14: Pre-commit hook and dev container

**Files:**
- Modify: `.githooks/pre-commit`
- Modify: `.devcontainer/Dockerfile`

- [ ] **Step 1: Update `.githooks/pre-commit`** — insert TypeScript checks as step 3, renumber compat to 4

Replace the body after the `echo "Running pre-commit checks..."` line so the checks read:

```sh
echo "[1/4] JVM: format, lint, test"
(cd implementations/jvm && ./gradlew ktfmtCheck detekt test)

echo "[2/4] Python: format, lint, typecheck"
(cd implementations/python && uv run ruff check src/esque/ && uv run ruff format --check src/esque/ && uv run pyright src/esque/)

echo "[3/4] TypeScript: format, lint, typecheck, test"
(cd implementations/typescript && npx biome ci src tests && npm run typecheck && npm test)

echo "[4/4] Compatibility tests"
(cd tests && uv run pytest . -q)

echo "Pre-commit checks passed."
```

- [ ] **Step 2: Add Node to `.devcontainer/Dockerfile`** — after the "install python things" block (still as USER ubuntu), add:

```dockerfile
# install node things
ARG NODE_VERSION=24
RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash \
    && source ${HOME}/.nvm/nvm.sh \
    && nvm install ${NODE_VERSION} \
    && nvm alias default ${NODE_VERSION}
```

- [ ] **Step 3: Verify the hook passes end-to-end**

Run: `.githooks/pre-commit`
Expected: all four sections pass (compat tests take ~3 minutes).

- [ ] **Step 4: Commit**

```bash
git add .githooks/pre-commit .devcontainer/Dockerfile
git commit -m "Add TypeScript checks to pre-commit hook and Node to dev container"
```

---

## Task 15: Documentation (CLAUDE.md)

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Apply these updates to `CLAUDE.md`:**

1. **Project Overview** — implementations line becomes:
   `- **Implementations:** JVM (Kotlin 2.4.0, Java 21) · Python 3.14 · TypeScript (Node 22+)` and add
   `- **TypeScript published to:** npm as \`esque-ts\``
2. **Repository Structure** — add a `typescript/` block under `implementations/` mirroring the python block (package.json, biome.json, tsconfig, `src/` module list, `tests/` list); add `version_typescript.sh` next to the other version scripts.
3. **Build and Development → Prerequisites** — add `Node.js 22+ (24 recommended) and npm`.
4. **Add a "TypeScript Commands (run from `implementations/typescript/`)" section:**

```bash
cd implementations/typescript

# install dependencies
npm install

# format code
npm run format

# check formatting and lint
npx biome ci src tests

# typecheck
npm run typecheck

# run unit tests
npm test

# build (emits dist/)
npm run build

# run the CLI
npx tsx src/cli.ts --help
```

5. **CI/CD section** — add the `typescript` job description: Biome + tsc + node:test + build + publish; version from `.github/version_typescript.sh` (SemVer: `X.Y.Z` on exact tag, `X.Y.Z-dev.N` otherwise) patched into `package.json`; non-release builds publish to npm under the `dev` dist-tag, releases to `latest`; secret `NPM_TOKEN`. Update compat-tests line: `needs jvm + python + typescript; runs 52 pytest scenarios via testcontainers.`
6. **Add a "TypeScript Code Conventions" section** (mirroring the Python one): `src/` layout matching the module structure, ESM-only strict TypeScript, Biome for lint/format (line width 120), official `@elastic/elasticsearch` client, `commander` CLI with the same option names, `yaml` for parsing, unit tests via `node:test` run through `tsx`.
7. **Python Code Conventions — fix stale httpx claim**: replace the `**httpx**: ES REST calls (not elasticsearch-py, ...)` bullet with `**elasticsearch**: official Python ES client (>=9) — handles auth mechanisms, retries, and typed responses`.
8. **Registered Implementations** — update the yaml snippet to include the typescript entry (same content as Task 11).
9. **Testing → Compatibility Test Harness** — mention TypeScript runs via `tsx` with no build step; update `17 scenarios` phrasing to `17 parametrized scenarios + 1 cross-implementation equivalency test`.
10. **Pre-commit hook description** at top of Repository Structure: `[1] JVM checks [2] Python checks [3] TypeScript checks [4] compat tests`.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "Document TypeScript implementation in CLAUDE.md"
```

---

## Task 16: Final verification

**Files:** none

- [ ] **Step 1: Full unit test + lint sweep for typescript**

Run: `cd implementations/typescript && npx biome ci src tests && npm run typecheck && npm test && npm run build`
Expected: all pass.

- [ ] **Step 2: Full compatibility suite**

Run: `cd tests && uv run pytest . -v`
Expected: 52 passed.

- [ ] **Step 3: Confirm clean tree and review the branch diff**

Run: `git status --short && git log --oneline master..HEAD`
Expected: clean tree; commits for scaffolding, model, template, checksum, loader, documents, operations, lock, orchestrator, CLI, harness registration, version script, CI, hook/devcontainer, docs.

- [ ] **Step 4: Report completion** — use superpowers:verification-before-completion, then superpowers:finishing-a-development-branch. Remind the user about the `NPM_TOKEN` secret.