package org.loesak.esque.examples.simple;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.loesak.esque.core.MigrationExecutor;
import org.loesak.esque.core.elasticsearch.RestClientOperations;

public class Main {

    public static void main(String... args) throws Exception {

        RestClientBuilder builder = RestClient.builder(
                new HttpHost("localhost", 9200, "http"));

        RestClient client = builder.build();

        try {
            new MigrationExecutor(client, "foober").execute();
        } finally {
            client.close();
        }

    }
}
