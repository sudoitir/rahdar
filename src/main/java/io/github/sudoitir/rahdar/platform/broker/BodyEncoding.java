package io.github.sudoitir.rahdar.platform.broker;

/** How to read {@link BrowsedMessage#body()}: as UTF-8 text, or as base64-encoded bytes. */
public enum BodyEncoding {
    TEXT,
    BASE64
}
