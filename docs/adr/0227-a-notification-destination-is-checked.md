# ADR-0227: A notification channel's destination is checked, and an internal one is refused

- **Status**: accepted
- **Date**: 2026-11-15
- **Deciders**: Mahdi Amirabdollahi
- **Related**: [ADR-0036](0036-notification-delivery-queue-and-channel-signing.md), [ADR-0105](0105-email-teams-and-pagerduty-notification-channels.md)

## Context

A notification channel stores a URL that Rahdar's own server posts to, and `/api/v1/channels/test` fetches it on
demand and reports whether the request went through. Validation checked the scheme was `http` or `https` and that
a host was named; nothing checked where that host is.

A caller holding `alert:write` could therefore point a channel at the loopback interface, a service on the
private network, or a cloud metadata endpoint, press Test, and read the outcome — the response is a working
oracle over the server's own network position, and it bypasses whatever egress control the deployment has. The
payload the server posts is attacker-influenced JSON, so a reachable service receives a request it can be made
to act on. Plain `http` also puts alert content on the wire in clear.

## Decision

1. **Only `https` is accepted**, with a named host and no user information in the URL.
2. **The resolved addresses are checked, not the name.** Loopback, any-local, link-local (which is where a
   metadata service answers), site-local, unique-local (RFC 4193), multicast, and the carrier-grade NAT range
   (RFC 6598) are refused. A name that answers with any of them is refused as a whole.
3. **The check runs where the fetch is decided**, in `DestinationPolicy`, and is called from three places: saving
   a channel, testing one, and delivering one. A channel stored before this decision is refused at delivery and
   the delivery is recorded dead, so no request reaches it.
4. **A name that does not resolve is not refused.** It is accepted and left to the connection, so a DNS outage
   during a delivery stays a retryable failure rather than a channel the operator must recreate.
5. **SMTP stays as it was.** An on-premise installation sends through its own SMTP relay, which is internal by
   design; refusing private addresses there would refuse the ordinary deployment. The email channel keeps its
   port, TLS and recipient validation.

## Consequences

- The test button no longer reports on anything the caller could not otherwise reach, and the same rule closes
  the delivery path rather than only the button.
- A receiver inside the network — an internal Slack proxy, an on-premise gateway — can no longer be a channel.
  The supported answer is an egress proxy with its own allow-list; this decision does not add one.
- **A rebinding window remains.** A name that resolves publicly when checked and internally when connected is not
  caught, because closing it needs the connection pinned to the address that was checked. That is recorded here
  rather than implied to be closed.
- A channel that already points inside starts failing at delivery with a message naming the reason.

## Alternatives considered

- **An allow-list of destinations per installation.** The right answer for deployments that need internal
  receivers, and it needs a settings surface and a UI this change does not have. Blocking internal addresses is
  the safe default either way; the allow-list can be added on top.
- **Checking inside each sender.** Four senders and a fifth coming would each have to remember, and the same
  fetch is reachable from three entry points. One policy at the decision points is checked once and tested once.
- **Refusing SMTP hosts too.** Breaks the normal deployment, for no gain over the format and TLS checks it has.
