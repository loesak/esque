#!/usr/bin/env node
import { Client } from "@elastic/elasticsearch";
import { Command, InvalidArgumentError } from "commander";
import { createEsqueConfiguration } from "./configuration.js";
import { Esque } from "./esque.js";

function collectProperty(value: string, previous: Record<string, string>): Record<string, string> {
  const separator = value.indexOf("=");
  if (separator <= 0) {
    throw new InvalidArgumentError(`must be in key=value format, got: '${value}'`);
  }
  previous[value.slice(0, separator)] = value.slice(separator + 1);
  return previous;
}

function formatErrorChain(error: unknown): string {
  if (!(error instanceof Error)) {
    return String(error);
  }
  const messages = [error.message];
  let cause = error.cause;
  while (cause instanceof Error) {
    messages.push(cause.message);
    cause = cause.cause;
  }
  return messages.join(" -> ");
}

const program = new Command()
  .name("esque")
  .description("Run Elasticsearch migrations.")
  .requiredOption("--es-url <url>", "Elasticsearch URL (e.g. http://localhost:9200)")
  .requiredOption("--migrations-dir <path>", "Path to directory containing migration YAML files")
  .requiredOption("--migration-key <key>", "Unique key scoping this migration set")
  .option("--migration-user <user>", "User to record on each migration record")
  .option(
    "--lock-timeout-minutes <n>",
    "Lock acquisition timeout in minutes",
    (value: string) => Number.parseInt(value, 10),
    5,
  )
  .option("--property <key=value>", "Template substitution property as key=value (repeatable)", collectProperty, {});

program.parse();

const opts = program.opts<{
  esUrl: string;
  migrationsDir: string;
  migrationKey: string;
  migrationUser?: string;
  lockTimeoutMinutes: number;
  property: Record<string, string>;
}>();

const configuration = createEsqueConfiguration({
  migrationKey: opts.migrationKey,
  migrationUser: opts.migrationUser ?? null,
  migrationDirectory: `file:${opts.migrationsDir}`,
  lockTimeoutMinutes: opts.lockTimeoutMinutes,
});

const esque = new Esque(new Client({ node: opts.esUrl }), configuration, opts.property);
try {
  await esque.execute();
} catch (error) {
  console.error(`Error: ${formatErrorChain(error)}`);
  process.exitCode = 1;
} finally {
  await esque.close();
}
