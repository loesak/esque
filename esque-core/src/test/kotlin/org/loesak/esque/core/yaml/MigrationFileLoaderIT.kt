package org.loesak.esque.core.yaml

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.loesak.esque.core.yaml.model.MigrationFile

class MigrationFileLoaderIT {

    private val loader = MigrationFileLoader()

    @Test
    fun `load discovers all migration files`() {
        val files = loader.load()
        assertThat(files).hasSize(3)
    }

    @Test
    fun `load files sorted by version`() {
        val files = loader.load()
        assertThat(files[0].metadata.version).isEqualTo("1.0.0")
        assertThat(files[1].metadata.version).isEqualTo("1.1.0")
        assertThat(files[2].metadata.version).isEqualTo("2.0.0")
    }

    @Test
    fun `load parses metadata from filenames`() {
        val files = loader.load()

        val first = files[0]
        assertThat(first.metadata.filename).isEqualTo("V1.0.0__CreateTestIndex.yml")
        assertThat(first.metadata.version).isEqualTo("1.0.0")
        assertThat(first.metadata.description).isEqualTo("CreateTestIndex")
        assertThat(first.metadata.checksum).isNotNull

        val second = files[1]
        assertThat(second.metadata.filename).isEqualTo("V1.1.0__CreateSecondIndex.yml")
        assertThat(second.metadata.description).isEqualTo("CreateSecondIndex")

        val third = files[2]
        assertThat(third.metadata.filename).isEqualTo("V2.0.0__CreateThirdIndex.yml")
        assertThat(third.metadata.description).isEqualTo("CreateThirdIndex")
    }

    @Test
    fun `load calculates checksums`() {
        val files = loader.load()

        files.forEach { assertThat(it.metadata.checksum).isNotNull }

        assertThat(files[0].metadata.checksum).isNotEqualTo(files[1].metadata.checksum)
    }

    @Test
    fun `load parses request definitions`() {
        val files = loader.load()

        val first = files[0]
        assertThat(first.contents.requests).hasSize(1)

        val request = first.contents.requests[0]
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/test-index-v1")
        assertThat(request.contentType).isEqualTo("application/json; charset=utf-8")
    }

    @Test
    fun `load checksums are stable`() {
        val first = loader.load()
        val second = loader.load()

        for (i in first.indices) {
            assertThat(first[i].metadata.checksum).isEqualTo(second[i].metadata.checksum)
        }
    }
}
