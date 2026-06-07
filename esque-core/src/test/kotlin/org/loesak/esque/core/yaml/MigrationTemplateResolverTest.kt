package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.loesak.esque.core.yaml.model.MigrationFile

class MigrationTemplateResolverTest {

  private fun makeDefinition(
      path: String = "/index",
      body: String? = null,
      method: String = "PUT",
      contentType: String? = null,
  ) =
      MigrationFile.MigrationFileRequestDefinition(
          method = method, path = path, body = body, contentType = contentType)

  private fun makeFile(
      vararg definitions: MigrationFile.MigrationFileRequestDefinition
  ): MigrationFile =
      MigrationFile(
          metadata =
              MigrationFile.MigrationFileMetadata(
                  filename = "V1.0.0__Test.yml",
                  version = "1.0.0",
                  description = "Test",
                  checksum = 0,
              ),
          contents = MigrationFile.MigrationFileContents(requests = definitions.toList()),
      )

  // --- validate ---

  @Test
  fun validate_noPlaceholders_emptyProperties_succeeds() {
    MigrationTemplateResolver(emptyMap()).validate(listOf(makeFile(makeDefinition("/plain-index"))))
  }

  @Test
  fun validate_allPlaceholdersResolved_succeeds() {
    val resolver = MigrationTemplateResolver(mapOf("suffix" to "v1", "replicas" to "3"))
    resolver.validate(
        listOf(makeFile(makeDefinition("/test-#{suffix}", """{"replicas":#{replicas}}"""))))
  }

  @Test
  fun validate_missingVariable_throwsWithVariableName() {
    assertThatThrownBy {
          MigrationTemplateResolver(emptyMap())
              .validate(listOf(makeFile(makeDefinition("/#{suffix}"))))
        }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("suffix")
  }

  @Test
  fun validate_multipleMissingVariables_listsAllInSingleMessage() {
    assertThatThrownBy {
          MigrationTemplateResolver(emptyMap())
              .validate(
                  listOf(
                      makeFile(makeDefinition("/#{prefix}-index", """{"replicas":#{replicas}}"""))))
        }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("prefix")
        .hasMessageContaining("replicas")
  }

  @Test
  fun validate_missingVariableSpanningMultipleFiles_listsAll() {
    val resolver = MigrationTemplateResolver(mapOf("a" to "1"))
    assertThatThrownBy {
          resolver.validate(
              listOf(makeFile(makeDefinition("/#{a}")), makeFile(makeDefinition("/#{b}"))))
        }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("b")
  }

  @Test
  fun validate_extraPropertiesNotReferencedInFiles_succeeds() {
    val resolver = MigrationTemplateResolver(mapOf("unused" to "value"))
    resolver.validate(listOf(makeFile(makeDefinition("/plain"))))
  }

  // --- resolve ---

  @Test
  fun resolve_substitutesPlaceholderInPath() {
    val resolved =
        MigrationTemplateResolver(mapOf("suffix" to "v1"))
            .resolve(makeDefinition("/test-#{suffix}"))
    assertThat(resolved.path).isEqualTo("/test-v1")
  }

  @Test
  fun resolve_substitutesPlaceholderInBody() {
    val resolved =
        MigrationTemplateResolver(mapOf("replicas" to "5"))
            .resolve(makeDefinition(body = """{"number_of_replicas":#{replicas}}"""))
    assertThat(resolved.body).isEqualTo("""{"number_of_replicas":5}""")
  }

  @Test
  fun resolve_substitutesMultiplePlaceholdersInSameField() {
    val resolved =
        MigrationTemplateResolver(mapOf("prefix" to "prod", "suffix" to "v1"))
            .resolve(makeDefinition("/#{prefix}-index-#{suffix}"))
    assertThat(resolved.path).isEqualTo("/prod-index-v1")
  }

  @Test
  fun resolve_nullBody_remainsNull() {
    val resolved = MigrationTemplateResolver(mapOf("x" to "y")).resolve(makeDefinition(body = null))
    assertThat(resolved.body).isNull()
  }

  @Test
  fun resolve_doesNotSubstituteInMethod() {
    val resolved =
        MigrationTemplateResolver(mapOf("x" to "replaced")).resolve(makeDefinition(method = "#{x}"))
    assertThat(resolved.method).isEqualTo("#{x}")
  }

  @Test
  fun resolve_doesNotSubstituteInContentType() {
    val resolved =
        MigrationTemplateResolver(mapOf("x" to "replaced"))
            .resolve(makeDefinition(contentType = "application/#{x}"))
    assertThat(resolved.contentType).isEqualTo("application/#{x}")
  }

  @Test
  fun resolve_emptyStringValue_substitutesToEmptyString() {
    val resolved =
        MigrationTemplateResolver(mapOf("empty" to "")).resolve(makeDefinition("/#{empty}-index"))
    assertThat(resolved.path).isEqualTo("/-index")
  }

  @Test
  fun resolve_variableNameWithDotAndHyphen_substitutes() {
    val resolved =
        MigrationTemplateResolver(mapOf("cluster.index-suffix" to "prod"))
            .resolve(makeDefinition("/#{cluster.index-suffix}"))
    assertThat(resolved.path).isEqualTo("/prod")
  }
}
