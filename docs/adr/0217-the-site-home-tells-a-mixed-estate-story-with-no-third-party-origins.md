# ADR-0217: The site home tells a mixed-estate story, with no third-party origins

- **Status**: accepted
- **Date**: 2026-10-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0121](0121-the-site-home-is-a-hand-written-story-page.md) made the English home one hand-written
file, `site/src/public/index.html`, telling the one-broker-per-tab hunt across eight Artemis brokers. It
loaded GSAP and ScrollTrigger from cdnjs and three typefaces from Google Fonts, and it was dark only.

Rahdar now manages Apache Kafka beside Artemis. The hunt no longer tells what makes the product
different: one estate across systems, a backlog that says whether it is exact or an estimate, a preview
before anything destructive, and an approval gate that holds people and assistants alike. Meanwhile the
platform caught up with what GSAP was there for: scroll-driven animations (`animation-timeline: view()`),
the Web Animations API and `offset-path` cover every motion the page needs, without a script from
another origin. Two third-party origins on the first request, and a dark-only page, were costs with no
remaining benefit.

## Decision

We will keep the home a single hand-written static page, as ADR-0121 decided, and change what it tells
and what it loads:

- **The story** is one on-call night across a mixed estate: a Kafka group falling behind, the hanging
  transaction behind it, a held offset reset approved by a second person, the Artemis dead-letter queue in
  the same estate, and an assistant whose change is held. Every scene is an illustration drawn in the page,
  labelled as one, and every claim comes from the guides. A system that is planned, not shipped, is shown
  only as coming.
- **No third-party origin.** No CDN script, no web font service, no analytics. Motion is CSS (transitions,
  scroll-driven animations) and the Web Animations API. The typefaces are the app's own, Atkinson
  Hyperlegible Next and Mono, self-hosted under `site/src/public/fonts/` with their SIL OFL licence.
- **Light and dark.** The page follows `prefers-color-scheme`, a toggle overrides it and remembers the
  choice in `localStorage` where it can.
- **Motion explains.** Lag grows, the last stable offset moves on an abort and the lag drains, the hold
  fills. Under `prefers-reduced-motion` every scene is still and already legible; the hold keeps its
  length, because it is part of the control (ADR-0186).
- Fonts and images load from the page's own origin by root-relative paths, so a preview of the built site
  works; links keep the canonical origin.

## Consequences

- The home loads from one origin and works without any CDN: about 18 KB of compressed HTML and 52 KB of
  fonts, with no script beyond its own.
- Opening the file straight from disk no longer finds its fonts; it falls back to system faces. The built
  site, and any static server rooted at `site/src/public`, serves them.
- Scroll-driven entrances need a browser with `animation-timeline`; elsewhere the page is simply static.
- Copy still has to be kept honest by hand when the product changes, now for two systems.
- The `/zh/` and `/fa/` home pages stay on VitePress's hero layout, as in ADR-0121, and are rewritten to the
  same multi-broker positioning.

## Alternatives considered

**Keep GSAP from cdnjs.** It works, but nothing on the page needs it any more, and it is the page's only
render-path dependency on another origin.

**Rebuild the home as a VitePress page.** Rejected for ADR-0121's reasons: the scripted scenes would be the
same code, and the page would no longer be one file.

**Keep the hunt and add a Kafka chapter.** The hunt's point is one broker per tab. A mixed estate's point is
one estate across systems, honest numbers and held changes; two stories on one page would tell neither.
