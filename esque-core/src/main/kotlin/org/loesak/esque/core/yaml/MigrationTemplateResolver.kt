package org.loesak.esque.core.yaml

import org.loesak.esque.core.yaml.model.MigrationFile

internal class MigrationTemplateResolver(private val properties: Map<String, String>) {

  fun validate(files: List<MigrationFile>) {
    val missing =
        files
            .flatMap { it.contents.requests }
            .flatMap { definition ->
              buildList {
                addAll(PLACEHOLDER_PATTERN.findAll(definition.path).map { it.groupValues[1] })
                definition.body?.let {
                  addAll(PLACEHOLDER_PATTERN.findAll(it).map { m -> m.groupValues[1] })
                }
              }
            }
            .filter { !properties.containsKey(it) }
            .toSet()

    check(missing.isEmpty()) {
      "migration files reference template variables with no matching properties: $missing"
    }
  }

  fun resolve(
      definition: MigrationFile.MigrationFileRequestDefinition
  ): MigrationFile.MigrationFileRequestDefinition =
      definition.copy(
          path = substitute(definition.path),
          body = definition.body?.let { substitute(it) },
      )

  private fun substitute(text: String): String =
      PLACEHOLDER_PATTERN.replace(text) { properties.getValue(it.groupValues[1]) }

  companion object {
    private val PLACEHOLDER_PATTERN = Regex("""#\{([a-zA-Z0-9._\-]+)}""")
  }
}
