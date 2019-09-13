package org.loesoft.esque.springboot.starter.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "esque")
public class EsqueConfigurationProperties {

    /**
     * Enables esque auto configuration.
     */
    @NotNull
    private Boolean enabled = true;

    /**
     * Esque migration key.
     */
    @NotEmpty
    private String migrationKey;

    /**
     * Comma-separated list of the Elasticsearch instances to use.
     */
    private List<String> uris;

    /**
     * Credentials username.
     */
    private String username;

    /**
     * Credentials password.
     */
    private String password;

    // TODO: aws authentication information

}
