package org.loesak.esque.core;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.io.IOException;

/**
 * Abstract base class for integration tests that require an Elasticsearch container.
 * Uses a shared singleton container to avoid startup overhead per test class.
 */
public abstract class AbstractElasticsearchIT {

    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("action.destructive_requires_name", "false");

    static {
        ELASTICSEARCH.start();
    }

    @BeforeEach
    protected void cleanElasticsearch() throws IOException {
        try (RestClient client = createRestClient()) {
            // delete the .esque index to ensure clean state
            try {
                client.performRequest(new Request("DELETE", "/.esque"));
            } catch (ResponseException e) {
                // index doesn't exist, that's fine
            }
            // delete all test indices created by migrations
            try {
                client.performRequest(new Request("DELETE", "/test-*"));
            } catch (ResponseException e) {
                // no indices to delete
            }
        }
    }

    protected RestClient createRestClient() {
        return RestClient.builder(
                new HttpHost(ELASTICSEARCH.getHost(), ELASTICSEARCH.getFirstMappedPort(), "http")
        ).build();
    }
}
