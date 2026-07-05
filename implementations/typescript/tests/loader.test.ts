import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { MigrationFileLoader } from "../src/migration/loader.js";
import { MigrationTemplateResolver } from "../src/migration/template.js";

function loaderFor(files: Record<string, string>): MigrationFileLoader {
  const dir = mkdtempSync(join(tmpdir(), "esque-loader-test-"));
  for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(dir, name), content);
  }
  return new MigrationFileLoader(`file:${dir}`, new MigrationTemplateResolver({}));
}

test("loads and orders valid migration files", () => {
  const loader = loaderFor({
    "V1.10.0__B.yml": "requests:\n  - method: PUT\n    path: /b\n",
    "V1.9.0__A.yml": "requests:\n  - method: PUT\n    path: /a\n",
    "ignored.txt": "not a migration",
  });
  const files = loader.load();
  assert.deepEqual(
    files.map((f) => f.metadata.version),
    ["1.9.0", "1.10.0"],
  );
  assert.ok(files.every((f) => Number.isInteger(f.metadata.checksum) && f.metadata.checksum !== 0));
});

test("throws on trailing-dot version in filename", () => {
  const loader = loaderFor({ "V1.__X.yml": "requests:\n  - method: PUT\n    path: /x\n" });
  assert.throws(() => loader.load(), /invalid migration filename/);
});

test("throws when requests key is missing", () => {
  const loader = loaderFor({ "V1.0.0__X.yml": "notrequests: []\n" });
  assert.throws(() => loader.load(), /must contain a 'requests' list/);
});

test("throws when request is missing method or path", () => {
  const loader = loaderFor({ "V1.0.0__X.yml": "requests:\n  - path: /x\n" });
  assert.throws(() => loader.load(), /missing required field/);
});

test("throws when params is not a mapping", () => {
  const loader = loaderFor({
    "V1.0.0__X.yml": "requests:\n  - method: PUT\n    path: /x\n    params:\n      - 1\n      - 2\n",
  });
  assert.throws(() => loader.load(), /'params' must be a mapping/);
});

test("rejects non-file: directory scheme", () => {
  const loader = new MigrationFileLoader("s3://bucket/migrations", new MigrationTemplateResolver({}));
  assert.throws(() => loader.load(), /unsupported migration directory scheme/);
});
