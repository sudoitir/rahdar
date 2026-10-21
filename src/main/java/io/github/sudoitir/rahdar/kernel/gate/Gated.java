package io.github.sudoitir.rahdar.kernel.gate;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method that passes {@link OperationGate#run} for the named operation type. An
 * architecture test checks that every marked method calls the gate and that its type has a {@link
 * GatedOperation} bean.
 */
@PluginApi
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Gated {

    /** The operation type, such as {@code subscription.purge}. */
    String value();
}
