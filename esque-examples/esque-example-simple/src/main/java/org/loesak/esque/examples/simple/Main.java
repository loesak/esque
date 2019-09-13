package org.loesak.esque.examples.simple;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.loesak.esque.core.Esque;

public class Main {

    public static void main(String... args) throws Exception {
        final String migrationKey = "foobar";

        try(Esque esque = new Esque(
                RestClient.builder(new HttpHost("localhost", 9200, "http"))
                          .build(),
                migrationKey)) {
            esque.execute();
        }
    }
}
