package org.loesak.esque.core.elasticsearch.documents;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;
import java.util.Objects;

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
public record MigrationRecord(
        String migrationKey,
        Integer order,
        String filename,
        String version,
        String description,
        Integer checksum,
        String installedBy, // user if any
        Instant installedOn,
        Long executionTime) {

    public MigrationRecord {
        Objects.requireNonNull(migrationKey);
        Objects.requireNonNull(order);
        Objects.requireNonNull(filename);
        Objects.requireNonNull(version);
        Objects.requireNonNull(description);
        Objects.requireNonNull(checksum);
        Objects.requireNonNull(installedOn);
        Objects.requireNonNull(executionTime);
    }

}
