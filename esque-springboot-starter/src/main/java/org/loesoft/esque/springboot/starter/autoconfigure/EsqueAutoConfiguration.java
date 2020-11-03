package org.loesoft.esque.springboot.starter.autoconfigure;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.loesoft.esque.core.Esque;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Supplier;

@Configuration
@ConditionalOnProperty(prefix = "esque", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(EsqueConfigurationProperties.class)
@AutoConfigureAfter({ ElasticsearchRestClientAutoConfiguration.class })
public class EsqueAutoConfiguration {

    private final EsqueConfigurationProperties esqueProperties;
    private final ElasticsearchRestClientProperties restClientProperties;
    private final RestClient elasticsearchRestClient;

    public EsqueAutoConfiguration(
            final EsqueConfigurationProperties esqueProperties,
            final ElasticsearchRestClientProperties restClientProperties,
            final ObjectProvider<RestClient> elasticsearchRestClient) {
        this.esqueProperties = esqueProperties;
        this.restClientProperties = restClientProperties;
        this.elasticsearchRestClient = elasticsearchRestClient.getIfUnique();
    }

    @Bean(destroyMethod = "close")
    public Esque esque() {
        return new Esque(this.getRestClient(), this.esqueProperties.getMigrationKey());
    }

    @Bean
    public EsqueInitializer esqueInitializer(final Esque esque) {
        return new EsqueInitializer(esque);
    }

    private RestClient getRestClient() {
        /*
         if connection information is different enough from any pre-created RestClient bean, then we need to create our own
         */

        if (!StringUtils.isEmpty(this.esqueProperties.getUris()) || !StringUtils.isEmpty(this.esqueProperties.getUsername())) {
            // creating our own
            List<String> uris = this.getProperty(this.esqueProperties::getUris, this.restClientProperties::getUris);
            HttpHost[] hosts = uris.stream().map(HttpHost::create).toArray(HttpHost[]::new);

            RestClientBuilder builder = RestClient.builder(hosts);

            String username = this.getProperty(this.esqueProperties::getUsername, this.restClientProperties::getUsername);
            if (!StringUtils.isEmpty(username)) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                Credentials credentials = new UsernamePasswordCredentials(
                        username,
                        this.getProperty(this.esqueProperties::getPassword, this.restClientProperties::getPassword));
                credentialsProvider.setCredentials(AuthScope.ANY, credentials);
                builder.setHttpClientConfigCallback(
                        (httpClientBuilder) -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
            }

            // TODO: add AWS authentication stuff

            return builder.build();
        } else if (this.elasticsearchRestClient != null) {
            return this.elasticsearchRestClient;
        } else {
            throw new RuntimeException("could not create or find a RestClient to use for migrations. You either need to configure esque to create its own RestClient or it needs to use a pre-created RestClient bean");
        }
    }

    private <T> T getProperty(Supplier<T> property, Supplier<T> defaultValue) {
        T value = property.get();
        return (value != null) ? value : defaultValue.get();
    }

}
