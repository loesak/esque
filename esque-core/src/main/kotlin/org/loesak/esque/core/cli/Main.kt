package org.loesak.esque.core.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import org.loesak.esque.core.Esque
import org.loesak.esque.core.EsqueConfiguration

class EsqueCli :
    CliktCommand(
        name = "esque",
        help = "Run Elasticsearch migrations",
    ) {

  private val esUrl by
      option("--es-url", help = "Elasticsearch URL (e.g. http://localhost:9200)").required()

  private val migrationsDir by
      option(
              "--migrations-dir",
              help = "Absolute path to directory containing migration YAML files")
          .required()

  private val migrationKey by
      option("--migration-key", help = "Unique key scoping this migration set").required()

  private val migrationUser by
      option("--migration-user", help = "User to record on each migration record")

  private val lockTimeoutMinutes by
      option("--lock-timeout-minutes", help = "Lock acquisition timeout in minutes")
          .long()
          .default(5L)

  private val properties by
      option(
              "--property",
              help = "Template substitution property as key=value (repeatable)",
          )
          .multiple()

  override fun run() {
    val props =
        properties.associate { entry ->
          val parts = entry.split("=", limit = 2)
          check(parts.size == 2) { "Property must be in key=value format, got: $entry" }
          parts[0] to parts[1]
        }

    val host = HttpHost.create(esUrl)

    RestClient.builder(host).build().use { client ->
      Esque(
              client = client,
              configuration =
                  EsqueConfiguration(
                      migrationKey = migrationKey,
                      migrationUser = migrationUser,
                      migrationDirectory = "file:$migrationsDir",
                      lockTimeoutMinutes = lockTimeoutMinutes,
                  ),
              properties = props,
          )
          .execute()
    }
  }
}

fun main(args: Array<String>) = EsqueCli().main(args)
