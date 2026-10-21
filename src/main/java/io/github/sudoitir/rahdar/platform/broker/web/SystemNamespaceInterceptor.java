package io.github.sudoitir.rahdar.platform.broker.web;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Keeps a broker system's own routes ({@code /clusters/{id}/artemis/...}) to the clusters that run
 * that system. The URL namespace is the declaration: a segment that is an installed driver's system
 * id answers the standard 404 unless the cluster runs it, so a new system-only controller cannot
 * forget the guard.
 */
@Component
class SystemNamespaceInterceptor implements HandlerInterceptor {

    static final String PATH = "/api/{version}/clusters/{clusterId}/{system}/**";

    private static final PathPattern PATTERN = PathPatternParser.defaultInstance.parse(PATH);

    private final BrokerSystems systems;

    SystemNamespaceInterceptor(BrokerSystems systems) {
        this.systems = systems;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
        PathPattern.PathMatchInfo match = PATTERN.matchAndExtract(path);
        Map<String, String> variables = match == null ? null : match.getUriVariables();
        String system = variables == null ? null : variables.get("system");
        UUID clusterId = clusterId(variables);
        if (system != null
                && clusterId != null
                && systems.ids().contains(system)
                && !system.equals(systems.idOf(clusterId))) {
            throw new NotFoundException("Cluster " + clusterId + " does not run " + system + ".");
        }
        return true;
    }

    private static UUID clusterId(Map<String, String> variables) {
        try {
            return variables == null ? null : UUID.fromString(variables.get("clusterId"));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
