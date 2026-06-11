package org.loesak.esque.examples.simple

import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

fun main() {
  Esque(
          RestClient.builder(HttpHost("localhost", 9200, "http")).build(),
          EsqueConfiguration(migrationKey = "esque-example-core-simple"),
      )
      .use { it.execute() }
}
