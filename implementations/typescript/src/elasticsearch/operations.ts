import type { Client } from "@elastic/elasticsearch";
import { errors } from "@elastic/elasticsearch";
import type { MigrationFile, MigrationFileRequestDefinition } from "../migration/model.js";
import type { MigrationRecord } from "./documents.js";
import {
  INDEX_DEFINITION,
  LOCK_ID_PREFIX,
  MIGRATION_INDEX,
  migrationLockToDocument,
  migrationRecordFromDocument,
  migrationRecordToDocument,
} from "./documents.js";

export class RestClientOperations {
  private readonly client: Client;
  private readonly migrationKey: string;

  constructor(client: Client, migrationKey: string) {
    this.client = client;
    this.migrationKey = migrationKey;
  }

  async close(): Promise<void> {
    await this.client.close();
  }

  async checkMigrationIndexExists(): Promise<boolean> {
    return await this.client.indices.exists({ index: MIGRATION_INDEX });
  }

  async createMigrationIndex(): Promise<void> {
    try {
      await this.client.indices.create({
        index: MIGRATION_INDEX,
        settings: INDEX_DEFINITION.settings,
        mappings: INDEX_DEFINITION.mappings,
      });
    } catch (error) {
      if (isAlreadyExistsError(error)) {
        return; // another process created it first — safe
      }
      throw error;
    }
  }

  async createLockRecord(): Promise<void> {
    await this.client.index({
      index: MIGRATION_INDEX,
      id: `${LOCK_ID_PREFIX}:${this.migrationKey}`,
      document: migrationLockToDocument(new Date()),
      op_type: "create",
    });
  }

  async deleteLockRecord(): Promise<void> {
    await this.client.delete({
      index: MIGRATION_INDEX,
      id: `${LOCK_ID_PREFIX}:${this.migrationKey}`,
    });
  }

  async getMigrationRecords(): Promise<MigrationRecord[]> {
    const response = await this.client.search<Record<string, unknown>>({
      index: MIGRATION_INDEX,
      query: { bool: { filter: [{ term: { "migration.migrationKey": this.migrationKey } }] } },
      size: 10000,
    });
    const records = response.hits.hits.map((hit) =>
      migrationRecordFromDocument(hit._source as Record<string, unknown>),
    );
    records.sort((a, b) => a.order - b.order);
    return records;
  }

  async getMigrationRecordForMigrationFile(file: MigrationFile): Promise<MigrationRecord | null> {
    const response = await this.client.search<Record<string, unknown>>({
      index: MIGRATION_INDEX,
      query: {
        bool: {
          filter: [
            { term: { "migration.migrationKey": this.migrationKey } },
            { term: { "migration.filename": file.metadata.filename } },
          ],
        },
      },
    });
    const hits = response.hits.hits;
    if (hits.length > 1) {
      throw new Error(
        `found more than one migration record for file [${file.metadata.filename}] and migration key [${this.migrationKey}]`,
      );
    }
    const first = hits[0];
    if (first !== undefined) {
      return migrationRecordFromDocument(first._source as Record<string, unknown>);
    }
    return null;
  }

  async executeMigrationDefinition(definition: MigrationFileRequestDefinition): Promise<void> {
    const headers: Record<string, string> = {};
    if (definition.contentType !== null) {
      headers["content-type"] = definition.contentType;
    }
    await this.client.transport.request(
      {
        method: definition.method,
        path: definition.path,
        querystring: definition.params ?? undefined,
        body: definition.body ?? undefined,
      },
      { headers },
    );
  }

  async createMigrationRecord(record: MigrationRecord): Promise<void> {
    if (record.migrationKey !== this.migrationKey) {
      throw new Error("migration record migration key must match operational migration key");
    }
    await this.client.index({
      index: MIGRATION_INDEX,
      document: migrationRecordToDocument(record),
      refresh: true, // without it, reads immediately after won't see the record
    });
  }
}

function isAlreadyExistsError(error: unknown): boolean {
  if (!(error instanceof errors.ResponseError)) {
    return false;
  }
  const body = error.body as { error?: { type?: string } } | undefined;
  return body?.error?.type === "resource_already_exists_exception";
}
