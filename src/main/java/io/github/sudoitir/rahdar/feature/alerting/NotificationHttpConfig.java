package io.github.sudoitir.rahdar.feature.alerting;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@code RestClient} for outbound notification delivery only (ADR-0036) — built
 * with the {@link HttpClientSettings} recipe, and never through the broker gateway: a call to a
 * Slack or webhook endpoint must not share the per-node broker rate limiter or a broker's TLS bundle.
 */
@Configuration
public class NotificationHttpConfig {

    @Bean
    public RestClient notificationRestClient(AlertingProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.connectTimeout());
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
