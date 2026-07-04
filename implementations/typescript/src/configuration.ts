export interface EsqueConfiguration {
  readonly migrationKey: string;
  readonly migrationUser: string | null;
  readonly migrationDirectory: string;
  readonly lockTimeoutMinutes: number;
}

export function createEsqueConfiguration(options: {
  migrationKey: string;
  migrationUser?: string | null;
  migrationDirectory?: string;
  lockTimeoutMinutes?: number;
}): EsqueConfiguration {
  return {
    migrationKey: options.migrationKey,
    migrationUser: options.migrationUser ?? null,
    migrationDirectory: options.migrationDirectory ?? "file:es.migration",
    lockTimeoutMinutes: options.lockTimeoutMinutes ?? 5,
  };
}
