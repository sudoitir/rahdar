package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns an expectation's declared reply addresses — literals and {@code *} globs —
 * into the concrete addresses to browse, and answers whether an observed address
 * belongs to an expectation.
 *
 * <p>Both halves live here so the sampler and the correlator cannot drift: if
 * {@link #resolve} browses an address that {@link #matches} would reject, a reply
 * is read and then discarded.
 *
 * <p>Resolution reads {@code subscription_snapshot}, which the scrape loop already fills,
 * so a pattern costs no broker call at all and a reply queue created by a new
 * responder is picked up within one scrape cycle of appearing (design.md, D4). The
 * cost is that such a queue is invisible to tracing until that first scrape — the
 * right trade against resolving over the broker on every observation, which would
 * turn a per-message hot path into a per-message management call.
 */
@Component
@RequiredArgsConstructor
public class ReplyAddressResolver {

    /**
     * The most concrete reply addresses one expectation may expand to. A pattern of
     * {@code *} alone would otherwise resolve to every address on the broker and
     * multiply the sampler's browses by the address count (design.md, D7). Hitting
     * the cap is reported, not silently applied.
     */
    public static final int MAX_RESOLVED = 32;

    /**
     * How long a cluster's address set is reused. The correlator calls
     * {@link #matches} once per notification, and re-reading every snapshot row per
     * event would repeat the mistake {@code expectationFor} already makes.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final QueueSnapshots snapshots;

    private final Map<UUID, CachedAddresses> cache = new ConcurrentHashMap<>();
    private final Map<String, Pattern> compiled = new ConcurrentHashMap<>();

    /** The concrete addresses to browse for this expectation, capped at {@link #MAX_RESOLVED}. */
    public Resolution resolve(UUID clusterId, RrExpectationEntity expectation) {
        List<String> declared = expectation.getReplyAddresses();
        if (declared.isEmpty()) {
            return new Resolution(List.of(), false, false);
        }
        boolean singleLiteral = declared.size() == 1 && !isPattern(declared.getFirst());

        Set<String> found = new LinkedHashSet<>();
        boolean capped = false;
        Set<String> known = null;

        for (String entry : declared) {
            if (isPattern(entry)) {
                if (known == null) {
                    known = knownAddresses(clusterId);
                }
                capped = addMatching(found, known, patternFor(entry));
            } else {
                // A literal needs no snapshot: it is browsable whether or not the
                // last scrape happened to see it. This is what keeps an expectation
                // working on a cluster whose scrape has not run yet.
                capped = addCapped(found, entry);
            }
            if (capped) {
                break;
            }
        }
        return new Resolution(List.copyOf(found), capped, singleLiteral);
    }

    /** True when {@link #MAX_RESOLVED} stopped a match from being added. */
    private static boolean addMatching(Set<String> found, Set<String> known, Pattern pattern) {
        for (String address : known) {
            if (pattern.matcher(address).matches() && addCapped(found, address)) {
                return true;
            }
        }
        return false;
    }

    /** Adds {@code address} unless the resolution is full; true when it was full. */
    private static boolean addCapped(Set<String> found, String address) {
        if (found.size() >= MAX_RESOLVED) {
            return true;
        }
        found.add(address);
        return false;
    }

    /**
     * Whether {@code address} is one this expectation expects replies on. Evaluated
     * against the declared entries rather than against {@link #resolve}'s output, so
     * a reply on a queue created since the last scrape is still recognised.
     */
    public boolean matches(RrExpectationEntity expectation, String address) {
        if (address == null) {
            return false;
        }
        for (String entry : expectation.getReplyAddresses()) {
            if (isPattern(entry) ? patternFor(entry).matcher(address).matches() : entry.equals(address)) {
                return true;
            }
        }
        return false;
    }

    /** An entry with no {@code *} is compared by equality; it is never compiled. */
    static boolean isPattern(String entry) {
        return entry != null && entry.indexOf('*') >= 0;
    }

    /**
     * Compiles a glob: {@code *} becomes "any run of characters", every other
     * character is literal, and the whole is anchored at both ends so
     * {@code orders.reply.*} does not match {@code legacy.orders.reply.x}.
     *
     * <p>Deliberately not a regular expression (design.md, D1): a regex invites a
     * pattern that takes exponential time on a crafted address and is hard to show
     * back to an operator. Artemis's {@code #} is deliberately not aliased — there
     * {@code *} is one word and {@code #} is any number, and quietly redefining
     * {@code *} to mean Artemis's {@code #} would be worse than one syntax that is
     * plainly Rahdar's own.
     */
    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        int from = 0;
        int star;
        while ((star = glob.indexOf('*', from)) >= 0) {
            if (star > from) {
                regex.append(Pattern.quote(glob.substring(from, star)));
            }
            regex.append(".*");
            from = star + 1;
        }
        if (from < glob.length()) {
            regex.append(Pattern.quote(glob.substring(from)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private Pattern patternFor(String glob) {
        return compiled.computeIfAbsent(glob, ReplyAddressResolver::compile);
    }

    private Set<String> knownAddresses(UUID clusterId) {
        CachedAddresses cached = cache.get(clusterId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.addresses();
        }
        Set<String> addresses = new LinkedHashSet<>(snapshots.addresses(clusterId));
        cache.put(clusterId, new CachedAddresses(Set.copyOf(addresses), now.plus(CACHE_TTL)));
        return addresses;
    }

    /** Drop a cluster's cached address set — used when the cluster is deleted or re-registered. */
    public void forget(UUID clusterId) {
        cache.remove(clusterId);
    }

    /**
     * @param addresses the concrete reply addresses, in declaration order
     * @param capped whether {@link #MAX_RESOLVED} cut the expansion short, so the UI
     *     can say the pattern is too broad rather than silently tracing a subset
     * @param singleLiteral whether the expectation <em>declared</em> exactly one
     *     literal address — the one case where a flow's reply destination is knowable
     *     before a reply arrives (design.md, D5). Deliberately a property of the
     *     declaration, not of this expansion: a glob matching one address today may
     *     match two tomorrow, so stamping from it would be a guess that happens to be
     *     right for now.
     */
    public record Resolution(List<String> addresses, boolean capped, boolean singleLiteral) {

        public boolean isEmpty() {
            return addresses.isEmpty();
        }
    }

    private record CachedAddresses(Set<String> addresses, Instant expiresAt) {}
}
