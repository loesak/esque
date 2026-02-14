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
* Elasticsearch 9+

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
    
It basically executes queries and remembers which queries have been run on a cluster for a given migration key. You can organize its usage to your needs.

# Install
Add the following to your maven dependencies. Make sure to check the releases or Maven Central for the latest version.
```xml
<dependency>
  <groupId>org.loesak.esque</groupId>
  <artifactId>esque-core</artifactId>
  <version>0.1.4</version>
</dependency>
```

# Cluster Authentication
You provide the RestClient, so you configure it for whatever authentication mechanism is in place for your cluster.

# Examples
Example projects exist in the `esque-examples` subdirectory 

# Future Features
* may allow ability to define "undo" queries for each definition to allow for attempts to roll back in the face of partial failure
* may allow ability to define "always" queries that are executed every run
* support multiple versions of Elasticsearch
* migrate to the new Rest5Client and RestClient is now legacy.
