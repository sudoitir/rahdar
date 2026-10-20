package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adopting a cluster's running configuration as its first declared revision, as a step of registering
 * it (ADR-0176). The configuration feature implements it, so the clusters module never names the
 * feature; when the feature is off there is no implementation, and registration offers no adoption.
 */
public interface RegistrationAdoption {

    /** A live node the check reached, with the connection to read it through. */
    record LiveNode(String name, BrokerTarget target) {}

    /** How many entries of each section adoption would declare. */
    record Counts(int addresses, int addressSettings, int securitySettings, int diverts) {}

    /**
     * What adopting would declare.
     *
     * @param disagreements items the nodes report differently, each naming the nodes and their values
     */
    record Preview(Counts counts, List<String> disagreements, List<String> notes) {}

    /**
     * What adopting the running configuration of these nodes would declare. Reads the nodes and writes
     * nothing. Empty when the caller may not declare configuration, or no node could be read.
     */
    Optional<Preview> preview(List<LiveNode> nodes);

    /** What the live nodes run, read before the cluster is saved; handed back to {@link #adopt}. */
    interface Reading {}

    /**
     * Reads what these nodes run, for {@link #adopt}. Reads the nodes and writes nothing, so it runs before
     * the registration transaction opens. Empty when no node could be read.
     *
     * @throws kernel.core.ResourceForbiddenException when the caller may not declare
     *     configuration
     */
    Optional<Reading> read(List<LiveNode> nodes);

    /**
     * Saves what {@link #read} found as the cluster's revision 1, attributed to the caller and audited, in
     * the caller's transaction. The cluster and its nodes are already saved in it; no broker is called.
     */
    void adopt(UUID clusterId, Reading reading);
}
