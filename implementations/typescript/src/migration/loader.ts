import { createHash } from "node:crypto";
import type { MigrationFileContents } from "./model.js";
import { toCanonicalDict } from "./model.js";

// Canonical JSON: keys sorted alphabetically at every level, null/undefined object values
// dropped, compact separators. Matches Python's
// json.dumps(remove_nulls(data), sort_keys=True, separators=(",", ":"), ensure_ascii=False).
function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(",")}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>)
    .filter((entry) => entry[1] !== null && entry[1] !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : 1));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(",")}}`;
}

// Canonical algorithm (identical across all implementations):
// canonical JSON of {"requests": [...]} → UTF-8 → MD5 → first 4 bytes as big-endian signed int32.
export function calculateChecksum(contents: MigrationFileContents): number {
  const data = { requests: contents.requests.map((r) => toCanonicalDict(r)) };
  const digest = createHash("md5").update(canonicalJson(data), "utf8").digest();
  return digest.readInt32BE(0);
}
