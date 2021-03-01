package org.loesak.esque.examples.simpleesauth;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.loesak.esque.core.Esque;

public class Application {

    public static void main(String... args) throws Exception {
        final String migrationKey = "esque-example-core-simple";
        final String migrationUser = "migration-user";
        final String migrationPass = "migration-p4$$word";

        // see https://www.elastic.co/guide/en/elasticsearch/client/java-rest/7.1/_basic_authentication.html

        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(migrationUser, migrationPass));

        RestClientBuilder builder = RestClient
                .builder(new HttpHost("localhost", 9200, "http"))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                });

        try(Esque esque = new Esque(builder.build(), migrationKey, migrationUser)) {
            esque.execute();
        }
    }
}
