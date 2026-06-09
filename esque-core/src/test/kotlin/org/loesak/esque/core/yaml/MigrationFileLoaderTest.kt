package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.loesak.esque.core.yaml.model.MigrationFile

class MigrationFileLoaderTest {

  private fun req(
      method: String = "PUT",
      path: String = "/index",
      contentType: String? = null,
      params: Map<String, String>? = null,
      body: String? = null,
  ) =
      MigrationFile.MigrationFileRequestDefinition(
          method = method, path = path, contentType = contentType, params = params, body = body)

  private fun contents(vararg requests: MigrationFile.MigrationFileRequestDefinition) =
      MigrationFile.MigrationFileContents(requests = requests.toList())

  @Test
  fun calculateChecksum_sameContents_sameChecksum() {
    val c = contents(req(path = "/index-v1", contentType = "application/json; charset=utf-8"))
    assertThat(MigrationFileLoader.calculateChecksum(c))
        .isEqualTo(MigrationFileLoader.calculateChecksum(c))
  }

  @Test
  fun calculateChecksum_resolvedTemplateMatchesHardcoded() {
    val hardcoded = contents(req(path = "/my-index-v1"))
    val withTemplate = contents(req(path = "/#{name}"))
    val resolved =
        MigrationTemplateResolver(mapOf("name" to "my-index-v1")).resolveContents(withTemplate)
    assertThat(MigrationFileLoader.calculateChecksum(hardcoded))
        .isEqualTo(MigrationFileLoader.calculateChecksum(resolved))
  }

  @Test
  fun calculateChecksum_requestsReordered_differentChecksum() {
    val a = req(path = "/path-a")
    val b = req(path = "/path-b")
    assertThat(MigrationFileLoader.calculateChecksum(contents(a, b)))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(b, a)))
  }

  @Test
  fun calculateChecksum_requestInserted_differentChecksum() {
    val a = req(path = "/path-a")
    val b = req(path = "/path-b")
    assertThat(MigrationFileLoader.calculateChecksum(contents(a)))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(a, b)))
  }

  @Test
  fun calculateChecksum_methodChanged_differentChecksum() {
    assertThat(MigrationFileLoader.calculateChecksum(contents(req(method = "PUT"))))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(req(method = "POST"))))
  }

  @Test
  fun calculateChecksum_pathChanged_differentChecksum() {
    assertThat(MigrationFileLoader.calculateChecksum(contents(req(path = "/index-v1"))))
        .isNotEqualTo(MigrationFileLoader.calculateChecksum(contents(req(path = "/index-v2"))))
  }

  @Test
  fun calculateChecksum_bodyChanged_differentChecksum() {
    assertThat(MigrationFileLoader.calculateChecksum(contents(req(body = """{"replicas":1}"""))))
        .isNotEqualTo(
            MigrationFileLoader.calculateChecksum(contents(req(body = """{"replicas":2}"""))))
  }

  @Test
  fun calculateChecksum_paramsInDifferentInsertionOrder_sameChecksum() {
    val r1 = req(params = mapOf("b" to "2", "a" to "1"))
    val r2 = req(params = mapOf("a" to "1", "b" to "2"))
    assertThat(MigrationFileLoader.calculateChecksum(contents(r1)))
        .isEqualTo(MigrationFileLoader.calculateChecksum(contents(r2)))
  }
}
