package org.loesoft.esque.examples.simple;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.loesoft.esque.core.Esque;

public class Application {

    public static void main(String... args) throws Exception {
        final String migrationKey = "esque-example-core-simple";

        try(Esque esque = new Esque(
                RestClient.builder(new HttpHost("localhost", 9200, "http"))
                          .build(),
                migrationKey)) {
            esque.execute();
        }
    }
}
