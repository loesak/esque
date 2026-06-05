package org.loesak.esque.examples.simpleesauth

import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque

fun main() {
    val migrationKey = "esque-example-core-simple"
    val migrationUser = "migration-user"
    val migrationPass = "migration-p4\$\$word"

    // see https://www.elastic.co/guide/en/elasticsearch/client/java-rest/7.1/_basic_authentication.html

    val credentialsProvider = BasicCredentialsProvider()
    credentialsProvider.setCredentials(AuthScope.ANY, UsernamePasswordCredentials(migrationUser, migrationPass))

    val client =
        RestClient
            .builder(HttpHost("localhost", 9200, "http"))
            .setHttpClientConfigCallback { it.setDefaultCredentialsProvider(credentialsProvider) }
            .build()

    Esque(client, migrationKey, migrationUser).use { it.execute() }
}
