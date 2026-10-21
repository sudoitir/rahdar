package io.github.sudoitir.rahdar.feature.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates one node's sampled producers or consumers into client edges keyed by identity,
 * destination and subscription (ADR-0081). All identity parts are kept so a reader can regroup by client
 * id, user or host without another sweep.
 */
final class ClientEdges {

    enum Kind {
        PRODUCE,
        CONSUME
    }

    /** One sampled producer or consumer with its computed rate. */
    record Member(
            Kind kind,
            String clientId,
            String user,
            String remoteAddress,
            String protocol,
            String destination,
            String subscription,
            Double rate,
            long unacked,
            boolean stalled) {}

    /**
     * One aggregated edge. {@code rate} sums the members whose rate is known and is {@code null}
     * only when none is, so a group still being measured never reads as idle.
     */
    record Edge(
            Kind kind,
            String clientId,
            String user,
            String remoteHost,
            String protocol,
            String destination,
            String subscription,
            Double rate,
            long unacked,
            int memberCount,
            boolean stalled) {}

    private record Key(
            Kind kind,
            String clientId,
            String user,
            String remoteHost,
            String protocol,
            String destination,
            String subscription) {}

    private ClientEdges() {}

    static List<Edge> aggregate(List<Member> members) {
        Map<Key, List<Member>> groups = new LinkedHashMap<>();
        for (Member m : members) {
            Key key = new Key(
                    m.kind(),
                    blank(m.clientId()),
                    blank(m.user()),
                    host(m.remoteAddress()),
                    blank(m.protocol()),
                    blank(m.destination()),
                    blank(m.subscription()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        List<Edge> out = new ArrayList<>(groups.size());
        groups.forEach((k, list) -> {
            Double rate = null;
            long unacked = 0;
            boolean stalled = false;
            for (Member m : list) {
                if (m.rate() != null) {
                    rate = (rate == null ? 0.0 : rate) + m.rate();
                }
                unacked += m.unacked();
                stalled |= m.stalled();
            }
            out.add(new Edge(
                    k.kind(),
                    k.clientId(),
                    k.user(),
                    k.remoteHost(),
                    k.protocol(),
                    k.destination(),
                    k.subscription(),
                    rate,
                    unacked,
                    list.size(),
                    stalled));
        });
        return out;
    }

    /**
     * The host part of a broker-reported remote address, without the ephemeral port that would
     * make every connection of one application a separate client: {@code 10.0.0.5:51234},
     * {@code /10.0.0.5:51234} and {@code [::1]:51234} become {@code 10.0.0.5} and {@code ::1}.
     */
    static String host(String remoteAddress) {
        String s = blank(remoteAddress);
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0
                && s.indexOf(':') == colon
                && s.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return s.substring(0, colon);
        }
        return s;
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}
