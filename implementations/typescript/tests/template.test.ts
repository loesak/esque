import assert from "node:assert/strict";
import { test } from "node:test";
import type { MigrationFile, MigrationFileRequestDefinition } from "../src/migration/model.js";
import { MigrationTemplateResolver } from "../src/migration/template.js";

function req(
  partial: Partial<MigrationFileRequestDefinition> & { method: string; path: string },
): MigrationFileRequestDefinition {
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

test("validate treats Object.prototype member names as missing vars", () => {
  const r = req({ method: "PUT", path: "/#{toString}" });
  assert.throws(() => new MigrationTemplateResolver({}).validate([fileOf(r)]), /toString/);
});

test("resolve handles adjacent placeholders", () => {
  const r = req({ method: "PUT", path: "/#{a}#{b}" });
  const result = new MigrationTemplateResolver({ a: "x", b: "y" }).resolve(r);
  assert.equal(result.path, "/xy");
});

test("resolve inserts property values containing dollar patterns literally", () => {
  const r = req({ method: "POST", path: "/", body: "#{v}" });
  const result = new MigrationTemplateResolver({ v: "cost: $& and $1" }).resolve(r);
  assert.equal(result.body, "cost: $& and $1");
});
