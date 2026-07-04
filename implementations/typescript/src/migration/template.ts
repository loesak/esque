import type { MigrationFile, MigrationFileContents, MigrationFileRequestDefinition } from "./model.js";

const PLACEHOLDER_PATTERN = /#\{([a-zA-Z0-9._-]+)\}/g;

export class MigrationTemplateResolver {
  private readonly properties: Readonly<Record<string, string>>;

  constructor(properties: Readonly<Record<string, string>>) {
    this.properties = properties;
  }

  // Collects ALL missing variables before throwing.
  validate(files: readonly MigrationFile[]): void {
    const missing = new Set<string>();
    for (const file of files) {
      for (const request of file.contents.requests) {
        const texts = [
          request.path,
          request.contentType ?? "",
          request.body ?? "",
          ...Object.values(request.params ?? {}),
        ];
        for (const text of texts) {
          for (const match of text.matchAll(PLACEHOLDER_PATTERN)) {
            const key = match[1];
            if (key !== undefined && !(key in this.properties)) {
              missing.add(key);
            }
          }
        }
      }
    }
    if (missing.size > 0) {
      throw new Error(
        `migration files reference template variables with no matching properties: ${[...missing].join(", ")}`,
      );
    }
  }

  // Substitutes #{varName} in path, contentType, params values, body — NOT method.
  resolve(definition: MigrationFileRequestDefinition): MigrationFileRequestDefinition {
    return {
      method: definition.method,
      path: this.substitute(definition.path),
      contentType: definition.contentType !== null ? this.substitute(definition.contentType) : null,
      params:
        definition.params !== null
          ? Object.fromEntries(Object.entries(definition.params).map(([k, v]) => [k, this.substitute(v)]))
          : null,
      body: definition.body !== null ? this.substitute(definition.body) : null,
    };
  }

  resolveContents(contents: MigrationFileContents): MigrationFileContents {
    return { requests: contents.requests.map((r) => this.resolve(r)) };
  }

  private substitute(text: string): string {
    return text.replace(PLACEHOLDER_PATTERN, (_match, key: string) => {
      const value = this.properties[key];
      if (value === undefined) {
        throw new Error(`unresolved template variable '#{${key}}' — was validate() called?`);
      }
      return value;
    });
  }
}
