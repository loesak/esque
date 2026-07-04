import assert from "node:assert/strict";
import { test } from "node:test";
import {
  migrationLockToDocument,
  migrationRecordFromDocument,
  migrationRecordToDocument,
} from "../src/elasticsearch/documents.js";

test("round-trips a full migration record", () => {
  const record = {
    migrationKey: "default",
    order: 0,
    filename: "V1.0.0__CreateFirstIndex.yml",
    version: "1.0.0",
    description: "CreateFirstIndex",
    checksum: -123456789,
    installedBy: "aaron",
    installedOn: "2026-06-13T12:00:00.000Z",
    executionTime: 42,
  };
  const doc = migrationRecordToDocument(record);
  assert.deepEqual(migrationRecordFromDocument(doc), record);
});

test("omits installedBy from the document when null", () => {
  const doc = migrationRecordToDocument({
    migrationKey: "default",
    order: 0,
    filename: "V1.0.0__CreateFirstIndex.yml",
    version: "1.0.0",
    description: "CreateFirstIndex",
    checksum: 1,
    installedBy: null,
    installedOn: "2026-06-13T12:00:00.000Z",
    executionTime: 1,
  });
  assert.equal("installedBy" in doc.migration, false);
});

test("throws on missing required field", () => {
  assert.throws(
    () => migrationRecordFromDocument({ migration: { migrationKey: "k", order: 0 } }),
    /missing field 'filename'/,
  );
});

test("migrationLockToDocument wraps an ISO date under lock.date", () => {
  const date = new Date("2026-06-13T12:00:00.000Z");
  assert.deepEqual(migrationLockToDocument(date), { lock: { date: "2026-06-13T12:00:00.000Z" } });
});
