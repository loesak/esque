# esque
Resembles an **E**lasticsearch **S**tateful **Qu**ery **E**xecutor

# What is it
A means of repeatable execution of pre-defined queries against your Elasticsearch cluster. Esque will remember which queries it ran against the cluster and only execute those that have not in the order they are defined.

It is Flyway-esque but for Elasticsearch.

# What it does
* Define queries in migration files using YAML
* Executes the migration files in order as needed
* Maintains state of which migration files have been executed
* Ensures integrity between migration files and applied migrations
* Locks migration operations across distributed systems to ensure single execution
* Can supply your own distributed lock if needed (e.g. Hazelcast)
* Allows for different logical separation of migration sets via a migration key

# What it doesn't
* Rollback in the face of failure. Back up your systems and test your migrations

# Prerequisites
* Elasticsearch 7+

# Dependencies
* org.elasticsearch.client:elasticsearch-rest-client
* com.fasterxml.jackson.core:jackson-databind
* com.fasterxml.jackson.module:jackson-module-parameter-names
* com.fasterxml.jackson.datatype:jackson-datatype-jdk8
* com.fasterxml.jackson.datatype:jackson-datatype-jsr310
* com.fasterxml.jackson.dataformat:jackson-dataformat-yaml
* org.slf4j:slf4j-api

# Use cases
* Executing all queries for bootstrapping a brand new Elasticsearch cluster. For example:
    - cluster settings
    - saved searches
    - visualizations
    - dashboards
    - creating indexes
    - creating users
* Executing queries needed for a particular application. For example:
    - creating indexes
    - creating index templates
    - creating/modifying index aliases
    - index schema modification
    - etc.
    
It basically executes queries and remembers which queries have been run on a cluster for a given migration key. You can organize it's usage to your needs.

# Security
TODO: deal with Elasticsearch security as well as AWS ES security

# Examples
TODO: create examples project

# Integrations
## Spring Boot
esque-springboot is a Spring Boot starter that will auto configure the necessary components based on configuration and execute the migrations on application startup.

# Future Features
* may allow ability to define "undo" queries for each definition to allow for attempts to roll back in the face of partial failure
* support other versions of Elasticsearch (5.6+ only) - may just ditch the elasticsearch rest client and move to straight HTTP queries...