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
