import assert from "node:assert/strict";
import { test } from "node:test";
import { calculateChecksum } from "../src/migration/loader.js";
import type { MigrationFileRequestDefinition } from "../src/migration/model.js";

function req(
  partial: Partial<MigrationFileRequestDefinition> & { method: string; path: string },
): MigrationFileRequestDefinition {
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
