package org.loesak.esque.core.elasticsearch.documents;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.time.Instant;
import java.util.Objects;

@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "lock")
public record MigrationLock(Instant date) {

    public MigrationLock {
        Objects.requireNonNull(date);
    }

}
