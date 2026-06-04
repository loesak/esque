package org.loesak.esque.core

import org.apache.http.HttpHost
import org.elasticsearch.client.Request
import org.elasticsearch.client.ResponseException
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.elasticsearch.ElasticsearchContainer

abstract class AbstractElasticsearchIT {

    companion object {
        val ELASTICSEARCH: ElasticsearchContainer = ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.3.0")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("action.destructive_requires_name", "false")

        init {
            ELASTICSEARCH.start()
        }
    }

    @BeforeEach
    protected fun cleanElasticsearch() {
        createRestClient().use { client ->
            try {
                client.performRequest(Request("DELETE", "/.esque"))
            } catch (e: ResponseException) {
                // index doesn't exist, that's fine
            }
            try {
                client.performRequest(Request("DELETE", "/test-*"))
            } catch (e: ResponseException) {
                // no indices to delete
            }
        }
    }

    protected fun createRestClient(): RestClient =
        RestClient.builder(HttpHost(ELASTICSEARCH.host, ELASTICSEARCH.firstMappedPort, "http")).build()
}
