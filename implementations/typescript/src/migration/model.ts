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
