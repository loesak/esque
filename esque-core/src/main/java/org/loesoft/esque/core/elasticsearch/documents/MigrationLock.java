package org.loesoft.esque.core.elasticsearch.documents;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.NonNull;
import lombok.Value;

import java.time.Instant;

@Value
@JsonTypeInfo(include = JsonTypeInfo.As.WRAPPER_OBJECT, use = JsonTypeInfo.Id.NAME)
@JsonTypeName(value = "lock")
public class MigrationLock {

    @NonNull Instant date;

}
