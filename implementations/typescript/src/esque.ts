import type { Client } from "@elastic/elasticsearch";
import type { EsqueConfiguration } from "./configuration.js";
import type { MigrationRecord } from "./elasticsearch/documents.js";
import { ElasticsearchDocumentLock, LockNotHeldError } from "./elasticsearch/lock.js";
import { RestClientOperations } from "./elasticsearch/operations.js";
import { MigrationFileLoader } from "./migration/loader.js";
import type { MigrationFile } from "./migration/model.js";
import { MigrationTemplateResolver } from "./migration/template.js";

export class Esque {
  private readonly configuration: EsqueConfiguration;
  private readonly migrationLoader: MigrationFileLoader;
  private readonly operations: RestClientOperations;
  private readonly lock: ElasticsearchDocumentLock;

  constructor(client: Client, configuration: EsqueConfiguration, properties: Record<string, string> = {}) {
    this.configuration = configuration;
    this.migrationLoader = new MigrationFileLoader(
      configuration.migrationDirectory,
      new MigrationTemplateResolver(properties),
    );
    this.operations = new RestClientOperations(client, configuration.migrationKey);
    this.lock = new ElasticsearchDocumentLock(this.operations);
  }

  async close(): Promise<void> {
    try {
      await this.lock.unlock();
    } catch (error) {
      if (!(error instanceof LockNotHeldError)) {
        console.warn("failed to release a execution lock. you may need to manually delete the lock document yourself");
      }
    }
    try {
      await this.operations.close();
    } catch {
      console.warn("failed to close client. this is likely not an issue");
    }
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }

  async execute(): Promise<void> {
    try {
      await this.initialize();
      const files = this.migrationLoader.load();
      const history = await this.operations.getMigrationRecords();
      this.verifyStateIntegrity(files, history);
      await this.runMigrations(files);
    } catch (error) {
      throw new Error("Failed to run esque execution", { cause: error });
    }
  }

  private async initialize(): Promise<void> {
    if (!(await this.operations.checkMigrationIndexExists())) {
      await this.operations.createMigrationIndex();
    }
  }

  private verifyStateIntegrity(files: MigrationFile[], history: MigrationRecord[]): void {
    if (history.length > files.length) {
      throw new Error(
        "the migration records are showing more migrations than the local system defines. " +
          "did you refactor your files or use an incorrect migration key?",
      );
    }
    const last = history[history.length - 1];
    if (last !== undefined && history.length !== last.order + 1) {
      throw new Error("the migration records seem to be corrupt as some records appear to be missing.");
    }
    for (const record of history) {
      this.verifyRecordIntegrity(record, files);
    }
  }

  private verifyRecordIntegrity(record: MigrationRecord, files: MigrationFile[]): void {
    const companion = files.find((f) => f.metadata.filename === record.filename);
    if (companion === undefined) {
      throw new Error(
        `could not find migration file matching migration history record by filename [${record.filename}]`,
      );
    }
    if (
      record.order !== files.indexOf(companion) ||
      record.version !== companion.metadata.version ||
      record.description !== companion.metadata.description ||
      record.checksum !== companion.metadata.checksum ||
      record.migrationKey !== this.configuration.migrationKey
    ) {
      throw new Error(
        `could not verify integrity of migration history record for filename [${record.filename}]. ` +
          "did you refactor your migration scripts after a previous execution?",
      );
    }
  }

  private async runMigrations(files: MigrationFile[]): Promise<void> {
    try {
      for (const file of files) {
        let executionError: unknown;
        try {
          if (await this.lock.tryLock(this.configuration.lockTimeoutMinutes)) {
            const existing = await this.operations.getMigrationRecordForMigrationFile(file);
            if (existing === null) {
              const start = performance.now();
              await this.runMigrationForFile(file);
              const elapsedMs = Math.round(performance.now() - start);
              await this.operations.createMigrationRecord({
                migrationKey: this.configuration.migrationKey,
                order: files.indexOf(file),
                filename: file.metadata.filename,
                version: file.metadata.version,
                description: file.metadata.description,
                checksum: file.metadata.checksum,
                installedBy: this.configuration.migrationUser,
                installedOn: new Date().toISOString(),
                executionTime: elapsedMs,
              });
            }
          } else {
            throw new Error("failed to acquire lock");
          }
        } catch (error) {
          executionError = new Error(`Failed to execute queries in migration file [${file.metadata.filename}]`, {
            cause: error,
          });
        }

        // Never let a release failure silently replace an execution failure (or its success) —
        // capture both separately instead of releasing in a `finally` that could throw over
        // whatever the try/catch above was about to produce.
        let releaseError: unknown;
        try {
          await this.lock.unlock();
        } catch (error) {
          if (!(error instanceof LockNotHeldError)) {
            releaseError = error;
          }
        }

        if (executionError !== undefined) {
          if (releaseError !== undefined) {
            console.warn(
              `failed to release execution lock for migration file [${file.metadata.filename}] after a migration failure. you may need to manually delete the lock document yourself`,
            );
          }
          throw executionError;
        }
        if (releaseError !== undefined) {
          throw new Error(`Failed to release execution lock after migration file [${file.metadata.filename}]`, {
            cause: releaseError,
          });
        }
      }
    } catch (error) {
      throw new Error("failed to run migrations", { cause: error });
    }
  }

  private async runMigrationForFile(file: MigrationFile): Promise<void> {
    for (const [position, definition] of file.contents.requests.entries()) {
      try {
        await this.operations.executeMigrationDefinition(definition);
      } catch (error) {
        throw new Error(
          `Failed to execute query in position [${position}] in migration file [${file.metadata.filename}]`,
          { cause: error },
        );
      }
    }
  }
}
