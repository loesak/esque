package org.loesak.esque.core.yaml.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MigrationFile(
        MigrationFileMetadata metadata,
        MigrationFileContents contents) implements Comparable<MigrationFile> {

    private static final String MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX = "\\.";

    public MigrationFile {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(contents);
    }

    public record MigrationFileMetadata(
            String filename,
            String version,
            String description,
            Integer checksum) {

        public MigrationFileMetadata {
            Objects.requireNonNull(filename);
            Objects.requireNonNull(version);
            Objects.requireNonNull(description);
            Objects.requireNonNull(checksum);
        }
    }

    public record MigrationFileContents(
            List<MigrationFileRequestDefinition> requests) {
    }

    public record MigrationFileRequestDefinition(
            String method,
            String path,
            String contentType,
            Map<String, String> params,
            String body) {

        public MigrationFileRequestDefinition {
            Objects.requireNonNull(method);
            Objects.requireNonNull(path);
        }
    }

    @Override
    public int compareTo(MigrationFile that) {
        final String[] thisVersionParts = this.metadata().version().split(MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX);
        final String[] thatVersionParts = that.metadata().version().split(MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX);

        final int largestNumberOfParts = Math.max(thisVersionParts.length, thatVersionParts.length);

        // sort by version parts
        for (int i = 0; i < largestNumberOfParts; i++) {
            final Integer thisVersionPartValue = thisVersionParts.length <= i ? 0 : Integer.valueOf(thisVersionParts[i]);
            final Integer thatVersionPartValue = thatVersionParts.length <= i ? 0 : Integer.valueOf(thatVersionParts[i]);

            final int result = thisVersionPartValue.compareTo(thatVersionPartValue);
            if (result != 0) {
                return result;
            }
        }

        // if same, then lexically compare the version string
        final int versionCompareResult = this.metadata().version().compareTo(that.metadata().version());
        if (versionCompareResult != 0) {
            return versionCompareResult;
        }

        // if still the same, then lexically compare the description
        return this.metadata().description().compareTo(that.metadata().description());
    }

}
