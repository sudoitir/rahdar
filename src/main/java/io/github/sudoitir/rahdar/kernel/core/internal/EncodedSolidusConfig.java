package io.github.sudoitir.rahdar.kernel.core.internal;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Lets {@code %2F} reach the application. Tomcat rejects an encoded slash by default, and a resource
 * id is one URL segment whose own slashes are encoded. {@code passthrough} leaves them encoded (not
 * {@code decode}, which would split the segment) for Spring to decode after it has matched the route.
 */
@Component
class EncodedSolidusConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling("passthrough"));
    }
}
