package io.github.sudoitir.rahdar.platform.broker;

import java.util.Set;

/**
 * What a node knows of its diverts and addresses that the broker SDK's listings do not carry: whether an
 * address is there or would be created on first use, and which diverts replay retained messages.
 */
public interface DivertSettings extends SystemExtension {

    /** Whether the address exists on the node, or would be created the first time something is sent to it. */
    boolean addressAvailable(BrokerTarget node, String address);

    /** The names of the node's diverts that replay the retained messages of their source address. */
    Set<String> retroactiveDiverts(BrokerTarget node);
}
