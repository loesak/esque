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
  // biome-ignore lint/complexity/useLiteralKeys: dot notation would be a compile error on a private member.
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

test("throws when a record's order does not match the file's position", () => {
  const f1 = file("1.0.0");
  const f2 = file("1.1.0");
  // f2 sits at index 1 in `files`, but the record claims order 0. Using order 0 (rather than a
  // trailing-gap value) keeps this past the earlier "gap in history" check so it actually
  // exercises verifyRecordIntegrity's order comparison.
  const swapped = record(f2, 0);
  assert.throws(() => verify(esque(), [f1, f2], [swapped]), /integrity/);
});
