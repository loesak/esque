import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import type { MigrationFile, MigrationFileContents, MigrationFileRequestDefinition } from "./model.js";
import { compareMigrationFiles, toCanonicalDict } from "./model.js";
import type { MigrationTemplateResolver } from "./template.js";

// Canonical JSON: keys sorted alphabetically at every level, null/undefined object values
// dropped, compact separators. Matches Python's
// json.dumps(remove_nulls(data), sort_keys=True, separators=(",", ":"), ensure_ascii=False).
// Only ever fed the closed shape produced by toCanonicalDict — do not reuse for arbitrary JSON.
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
  if (version === undefined || description === undefined || version.split(".").some((s) => s === "")) {
    // Empty segment (e.g. trailing dot in "V1.__X.yml") — fail loud, mirroring Python's
    // int("") ValueError, instead of silently treating it as 0.
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
