package org.loesak.esque.examples.simple

import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque

fun main() {
  val migrationKey = "esque-example-core-simple"

  Esque(
          RestClient.builder(HttpHost("localhost", 9200, "http")).build(),
          migrationKey,
      )
      .use { it.execute() }
}
