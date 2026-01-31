package org.loesak.esque.core.yaml;

import org.junit.jupiter.api.Test;
import org.loesak.esque.core.yaml.model.MigrationFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MigrationFileLoader. These tests rely on migration files
 * in src/test/resources/es.migration/ being on the classpath.
 */
class MigrationFileLoaderIT {

    private final MigrationFileLoader loader = new MigrationFileLoader();

    @Test
    void load_discoversAllMigrationFiles() throws Exception {
        List<MigrationFile> files = loader.load();

        assertThat(files).hasSize(3);
    }

    @Test
    void load_filesSortedByVersion() throws Exception {
        List<MigrationFile> files = loader.load();

        assertThat(files.get(0).metadata().version()).isEqualTo("1.0.0");
        assertThat(files.get(1).metadata().version()).isEqualTo("1.1.0");
        assertThat(files.get(2).metadata().version()).isEqualTo("2.0.0");
    }

    @Test
    void load_parsesMetadataFromFilenames() throws Exception {
        List<MigrationFile> files = loader.load();

        MigrationFile first = files.get(0);
        assertThat(first.metadata().filename()).isEqualTo("V1.0.0__CreateTestIndex.yml");
        assertThat(first.metadata().version()).isEqualTo("1.0.0");
        assertThat(first.metadata().description()).isEqualTo("CreateTestIndex");
        assertThat(first.metadata().checksum()).isNotNull();

        MigrationFile second = files.get(1);
        assertThat(second.metadata().filename()).isEqualTo("V1.1.0__CreateSecondIndex.yml");
        assertThat(second.metadata().description()).isEqualTo("CreateSecondIndex");

        MigrationFile third = files.get(2);
        assertThat(third.metadata().filename()).isEqualTo("V2.0.0__CreateThirdIndex.yml");
        assertThat(third.metadata().description()).isEqualTo("CreateThirdIndex");
    }

    @Test
    void load_calculatesChecksums() throws Exception {
        List<MigrationFile> files = loader.load();

        // all checksums should be non-null integers
        for (MigrationFile file : files) {
            assertThat(file.metadata().checksum()).isNotNull();
        }

        // different file contents should produce different checksums
        // (V1.0.0 creates test-index-v1, V1.1.0 creates test-index-v2 - different content)
        assertThat(files.get(0).metadata().checksum())
                .isNotEqualTo(files.get(1).metadata().checksum());
    }

    @Test
    void load_parsesRequestDefinitions() throws Exception {
        List<MigrationFile> files = loader.load();

        MigrationFile first = files.get(0);
        assertThat(first.contents().requests()).hasSize(1);

        MigrationFile.MigrationFileRequestDefinition request = first.contents().requests().get(0);
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.path()).isEqualTo("/test-index-v1");
        assertThat(request.contentType()).isEqualTo("application/json; charset=utf-8");
    }

    @Test
    void load_checksumsAreStable() throws Exception {
        List<MigrationFile> first = loader.load();
        List<MigrationFile> second = loader.load();

        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).metadata().checksum())
                    .isEqualTo(second.get(i).metadata().checksum());
        }
    }
}
