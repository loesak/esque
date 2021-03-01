package org.loesak.esque.core.elasticsearch.documents;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.NonNull;
import lombok.Value;

import java.time.Instant;

@Value
@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "migration")
public class MigrationRecord {

    @NonNull String migrationKey;

    @NonNull Integer order;

    @NonNull String filename;

    @NonNull String version;

    @NonNull String description;

    @NonNull Integer checksum;

    String installedBy; // user if any

    @NonNull Instant installedOn;

    @NonNull Long executionTime;

}