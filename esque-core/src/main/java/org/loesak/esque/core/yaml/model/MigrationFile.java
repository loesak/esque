package org.loesak.esque.core.yaml.model;

import lombok.NonNull;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
public class MigrationFile implements Comparable<MigrationFile> {

    private static final String MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX = "\\.";

    @NonNull private final MigrationFileMetadata metadata;
    @NonNull private final MigrationFileContents contents;

    @Value
    public static class MigrationFileMetadata {
        @NonNull private final String filename;
        @NonNull private final String version;
        @NonNull private final String description;
        @NonNull private final Integer checksum;
    }

    @Value
    public static class MigrationFileContents {
        private List<MigrationFileRequestDefinition> requests;
    }

    @Value
    public static class MigrationFileRequestDefinition {
        @NonNull private final String method;
        @NonNull private final String path;
        @NonNull private final String contentType;
        private final Map<String, String> params;
        private final String body;
    }

    @Override
    public int compareTo(MigrationFile that) {
        final String[] thisVersionParts = this.getMetadata().getVersion().split(MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX);
        final String[] thatVersionParts = that.getMetadata().getVersion().split(MIGRATION_DEFINITION_FILE_VERSION_PARTS_DELIMITER_REGEX);

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
        final int versionCompareResult = this.getMetadata().getVersion().compareTo(that.getMetadata().getVersion());
        if (versionCompareResult != 0) {
            return versionCompareResult;
        }

        // if still the same, then lexically compare the description
        return this.getMetadata().getDescription().compareTo(that.getMetadata().getDescription());
    }

}
