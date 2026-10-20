# ADR-0098: Per-cluster Core TLS through an Artemis SSLContextFactory

- **Status**: superseded by [ADR-0203](0203-rahdar-reaches-every-broker-through-openbroker.md)
- **Date**: 2026-09-21
- **Deciders**: Artemis Studio maintainers

## Context

Core connections set TLS trust by replacing the JVM-default `SSLContext` with the
cluster's Spring SSL bundle (`useDefaultSslContext=true`). This was a recorded
`ponytail:` ceiling. It holds while every broker's certificate chains to one CA.
Cross-broker transfer (ADR-0097) holds Core connections to two clusters at once. When
those clusters use different CAs, the second connection overwrites the first one's
trust, and one of the two handshakes fails.

Artemis resolves TLS through the `SSLContextFactory` SPI, which is loaded by
`ServiceLoader` and chosen by priority. It passes the connection's transport parameters,
which include `sslContext`, a name Artemis uses to cache contexts.

## Decision

We will register `StudioSslContextFactory` through `META-INF/services`, with a priority
above Artemis's default.

- For a connection whose `sslContext` parameter names a Spring SSL bundle, the factory
  returns that bundle's `SSLContext`, cached per bundle name.
- For any other connection, it defers to the default behaviour.

`CoreConnectionFactory` emits `sslEnabled=true;sslContext=<bundle>` and no longer
touches `SSLContext.setDefault`. Because `ServiceLoader` creates the factory, it reaches
`SslBundles` through a single static holder that a Spring component sets at startup.

## Consequences

- Each cluster's Core connections trust only that cluster's bundle, and any number of
  clusters with distinct CAs can be connected at once.
- The JVM default `SSLContext` is no longer mutated, which removes a hidden
  process-wide side effect.
- One static seam exists between Spring and an Artemis SPI. If the holder is unset,
  the factory fails loudly and names the missing bundle.
- A rotated bundle takes effect when that cluster's pool is rebuilt, which is the same
  as before.

## Alternatives considered

- **Point the URL at trust-store files.** Studio's trust lives in Spring SSL bundles,
  not files, so this would mean materialising secrets to disk.
- **Keep the shared default context.** This breaks any two-CA transfer, and every
  other feature holding Core connections to more than one cluster.
