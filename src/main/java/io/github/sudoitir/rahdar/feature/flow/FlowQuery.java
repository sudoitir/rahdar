package io.github.sudoitir.rahdar.feature.flow;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a flow read asks for: an optional focus, how far it reaches, how paths are ranked and
 * bounded, how clients are grouped, and which routing layers are drawn. Every value is clamped here,
 * so a caller cannot ask the server for an unbounded graph.
 *
 * @param clamped the caller asked for more paths than {@link #MAX_LIMIT}
 */
public record FlowQuery(
        Focus focus,
        int hops,
        Rank rank,
        int limit,
        GroupBy groupBy,
        Set<Layer> layers,
        boolean clamped,
        boolean byNode) {

    /**
     * The same query, with or without each resource's per-node breakdown (ADR-0110). Only the Split
     * layout asks for it, so the default graph's payload does not grow with the node count.
     */
    public FlowQuery withByNode(boolean breakdown) {
        return new FlowQuery(focus, hops, rank, limit, groupBy, layers, clamped, breakdown);
    }

    public static final int DEFAULT_LIMIT = 40;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_HOPS = 3;

    public enum Rank {
        /** Messages added to the path's queue per second. */
        IN,
        /** Messages acknowledged from the path's queue per second. */
        OUT,
        /** Messages waiting on the path's queue. */
        BACKLOG
    }

    public enum GroupBy {
        /** Client id, falling back to user, then host. */
        CLIENT_ID,
        /** Authenticated user, falling back to host. */
        USER,
        /** Remote host without its port. */
        HOST
    }

    /** Optional routing drawn on top of produce, route and consume (flow-visualization spec). */
    public enum Layer {
        ROUTES,
        CLUSTER,
        DEAD_LETTER,
        TEMPORARY,
        CAPTURE
    }

    public static final Set<Layer> DEFAULT_LAYERS = Set.copyOf(EnumSet.of(Layer.ROUTES, Layer.CLUSTER));

    public enum FocusKind {
        CLIENT,
        DESTINATION,
        SUBSCRIPTION
    }

    public record Focus(FocusKind kind, String name) {}

    /**
     * @param focus {@code client:<name>}, {@code destination:<name>} or {@code subscription:<name>}, or blank for none
     * @param layers comma-separated {@link Layer} names; blank for the default layers
     * @throws IllegalArgumentException for a focus or a layer that is not one of those shapes
     */
    public static FlowQuery of(String focus, int hops, Rank rank, int limit, GroupBy groupBy, String layers) {
        return new FlowQuery(
                parseFocus(focus),
                Math.clamp(hops, 1, MAX_HOPS),
                rank == null ? Rank.IN : rank,
                Math.clamp(limit, 1, MAX_LIMIT),
                groupBy == null ? GroupBy.CLIENT_ID : groupBy,
                parseLayers(layers),
                limit > MAX_LIMIT,
                false);
    }

    private static Set<Layer> parseLayers(String layers) {
        if (layers == null || layers.isBlank()) {
            return DEFAULT_LAYERS;
        }
        EnumSet<Layer> out = EnumSet.noneOf(Layer.class);
        for (String part : layers.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            if (name.isEmpty() || name.equals("NONE")) {
                continue;
            }
            try {
                out.add(Layer.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "layers must be a comma-separated list of " + EnumSet.allOf(Layer.class) + ", not '" + part
                                + "'",
                        e);
            }
        }
        return Set.copyOf(out);
    }

    private static Focus parseFocus(String focus) {
        if (focus == null || focus.isBlank()) {
            return null;
        }
        int colon = focus.indexOf(':');
        String message = "focus must be client:<name>, destination:<name> or subscription:<name>, not '" + focus + "'";
        if (colon <= 0 || colon == focus.length() - 1) {
            throw new IllegalArgumentException(message);
        }
        try {
            return new Focus(
                    FocusKind.valueOf(focus.substring(0, colon).toUpperCase(Locale.ROOT)), focus.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message, e);
        }
    }
}
