package io.github.sudoitir.rahdar.platform.broker;

/**
 * What only some broker systems can do, or do in their own way. A system's module provides one
 * implementation per extension it supports, and a feature asks {@link SystemExtensions} for the one
 * that serves a cluster.
 */
public interface SystemExtension {

    /** The id of the broker system this extension serves, as the broker SDK names it. */
    String system();
}
