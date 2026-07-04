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

test("description tiebreak when versions equal", () => {
  assert.ok(compareMigrationFiles(file("1.0.0", "Alpha"), file("1.0.0", "Beta")) < 0);
  assert.ok(compareMigrationFiles(file("1.0.0", "Beta"), file("1.0.0", "Alpha")) > 0);
});
