package org.loesak.esque.core

data class EsqueConfiguration(
    val migrationKey: String,
    val migrationUser: String? = null,
    val migrationDirectory: String = "classpath:es.migration",
    val lockTimeoutMinutes: Long = 5,
)
