package io.github.sudoitir.rahdar.system.rabbitmq.mcp;

import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationLink;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Shovel;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ShovelPage;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the MCP read tools of a RabbitMQ cluster answer where the REST views are not enough: a page size that is
 * bounded, and the shovel and federation addresses scrubbed once more of any credential, so that no tool can ever
 * hand one to a model even if a driver let one through.
 */
final class RabbitMqMcpViews {

    /** The most entries a tool answers in one page. */
    static final int PAGE = 100;

    private static final Pattern USER_INFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]*@");

    private RabbitMqMcpViews() {}

    record Links(ShovelPage shovels, FederationPage federation) {}

    /** The page size asked for, at most {@link #PAGE}; absent when the caller named none. */
    static Integer limit(Integer asked) {
        if (asked == null) {
            return null;
        }
        return Math.clamp(asked, 1, PAGE);
    }

    /** The address without the user and password of its authority. */
    static String address(String uri) {
        return uri == null ? null : USER_INFO.matcher(uri).replaceAll("$1");
    }

    static ShovelPage shovels(ShovelPage page) {
        List<Shovel> items = page.items().stream()
                .map(s -> new Shovel(
                        s.id(),
                        s.vhost(),
                        s.name(),
                        s.state(),
                        address(s.source()),
                        address(s.destination()),
                        s.managedBy()))
                .toList();
        return new ShovelPage(page.available(), page.remedy(), items, page.next());
    }

    static FederationPage federation(FederationPage page) {
        List<FederationLink> items = page.items().stream()
                .map(l -> new FederationLink(
                        l.vhost(), l.upstream(), l.type(), l.name(), l.status(), address(l.source())))
                .toList();
        return new FederationPage(page.available(), page.remedy(), items, page.next());
    }
}
