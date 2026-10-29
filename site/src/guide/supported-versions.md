---
title: Supported broker versions
description: Which Apache Artemis and Apache Kafka releases Rahdar supports, how that range was established, and what happens outside it.
---

# Supported broker versions

Rahdar manages two broker systems. Each has its own range, and a cluster is checked against the range of the
system it runs.

## Apache Kafka

| | Kafka release |
| --- | --- |
| Minimum | **3.0** |
| Tested | 3.5 (ZooKeeper mode) and 4.x (KRaft mode, including share groups) |

Every release from 3.0 on is supported, in **KRaft** and in **ZooKeeper** mode. A cluster older than 3.0 is
refused: the check names the version it found and the supported range, and nothing is stored.

Kafka brokers report their version only as far as their protocol goes, so Rahdar often cannot name the exact
release. It then shows the version as a lower bound, **at least 3.5**, together with the mode, and never as an
exact number.

What a release or a mode cannot do is shown as unavailable, with its reason, and never read as healthy:

| On | Unavailable | Why |
| --- | --- | --- |
| ZooKeeper mode | The controller quorum | The quorum is a KRaft concept. The brokers page says the quorum is unknown, and a cluster whose brokers all serve is healthy with a note. |
| Before 4.0 | Share groups | They arrived with 4.0. The groups list shows only the group types the cluster has. |
| A cluster without an authoriser | ACLs | There is nothing to list. The ACLs page says so, with the broker's reason. |
| An account that may not describe a group or topic | That group's lag, or that topic's configuration | The figure says it could not be read and why, instead of showing 0. |

Registering a cluster newer than the range Rahdar was built against is accepted, with a warning.

## Apache Artemis

| | Artemis release | Image CI tests |
| --- | --- | --- |
| Minimum | **2.33.0** | `apache/activemq-artemis:2.33.0` |
| Latest tested | **2.57.0** | `apache/artemis:2.57.0` |

Every release in between is supported. CI runs the whole integration suite against both ends of the range
on every pull request, not against each release in between.

Artemis became its own Apache project with 2.50: its images moved from `apache/activemq-artemis` (which ends
at 2.44.0) to `apache/artemis`. Rahdar treats both lines as one broker and compares versions by number.

**ActiveMQ Classic is not supported.** Rahdar manages Artemis only; Classic has a different management
model, and nothing in Rahdar talks to it.

## How the range was established

The minimum is the oldest release that has every management operation Rahdar calls. Reading the broker's
management interface at each release, then running the integration suite against the candidates, shows
that the form of `addSecuritySettings` carrying the *view* and *edit* permissions, which message capture,
plugin taps and configuration edits set, first appears in **2.33.0**. An older broker cannot hold those
permissions, so Rahdar does not register it rather than set a weaker security setting than it shows.

One operation is newer: the JSON form of `createDivert` arrived in 2.38.0. On an older broker Rahdar falls
back to the positional form, which carries the same fields, so creating diverts, capture and plugin taps
work across the whole range.

The latest tested release is the newest Artemis release when Rahdar was built, and it matches the client
library Rahdar ships.

## What Rahdar does with a broker's version

Rahdar reads each node's version when a cluster is registered, and keeps it current while it polls the
node, so an upgraded broker is picked up without re-registering.

| The broker runs | Registration | Afterwards |
| --- | --- | --- |
| Older than 2.33.0 | Refused, naming the minimum. A connection check gives the same answer. | A node found later by topology discovery stays in the cluster and is marked *unsupported release* on the topology. |
| 2.33.0 to 2.57.0 | Accepted. | Everything is available. |
| Newer than 2.57.0 | Accepted, with a warning that the release is untested. | The node is marked *newer release than Rahdar has tested*. |

A node Rahdar reaches only through the Core protocol, with no management URL, has no known version and is
never refused on that account.

## Operations that need a newer release

When an operation needs a newer release than a node runs, and Rahdar cannot fall back, the control stays
visible, disabled, with the release it needs, and the cluster's capabilities list it. In a cluster whose
nodes run different releases, as during a rolling upgrade, the operation runs on the nodes that have it
and each other node is reported as skipped, with the release it needs.

No operation needs this today: everything works from 2.33.0.
