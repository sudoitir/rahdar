package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * What is fixed about one gated operation type, whatever its parameters: a type's {@link Trait}s can depend on the
 * parameters of a request, so they are not listed here.
 *
 * @param type the type, such as {@code subscription.purge} or {@code <pluginId>:<name>}
 * @param version the version a held request of this type must have to run
 * @param mode who runs it once approved
 */
@PluginApi
public record GatedOperationInfo(String type, int version, ExecutionMode mode) {}
