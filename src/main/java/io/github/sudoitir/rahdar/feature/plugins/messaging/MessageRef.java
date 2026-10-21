package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * Where one message is: the serving node it was received on and its position there, an opaque
 * string only the node's system understands.
 *
 * @param nodeId the serving node that delivered it
 * @param position the message's position on that node
 */
@PluginApi
public record MessageRef(UUID nodeId, String position) {}
