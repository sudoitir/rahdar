# ADR-0121: The site's home page is a hand-written story page

- **Status**: superseded by [ADR-0217](0217-the-site-home-tells-a-mixed-estate-story-with-no-third-party-origins.md)
- **Date**: 2026-09-28
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0061](0061-docs-site-vitepress-on-github-pages.md) put the whole site on VitePress, and its
home page was the default theme's hero with six feature cards. That page lists what Studio does. It
never shows the problem Studio exists for: the console bundled with Artemis manages one broker at a
time, so an operator paged about a backed-up queue opens a tab per node and walks a JMX tree in each
until they find it. A list of features does not make that pain felt, and feeling it is what makes the
rest of the page persuasive.

A page that makes the reader do the hunt, then shows the same incident solved on one screen, needs
scripted interaction, a scroll-driven sequence and an SVG topology. The default theme's home layout
has no room for any of it, and ADR-0061 considered and rejected hand-written static HTML for the
landing page.

## Decision

We will make the English home page a single hand-written file, `site/src/public/index.html`.

- VitePress copies `public/` verbatim, so the file is served at the canonical origin
  (`https://sudoitir.github.io/artemis-studio/`). `site/src/index.md` is removed so the two do not
  collide. Every other page, including the `/zh/` and `/fa/` home pages, stays on VitePress.
- The file is self-contained. CSS and script are inline. GSAP and its ScrollTrigger plugin, at a
  pinned version from cdnjs, add the scroll choreography, and fonts come from Google Fonts. Media and
  links use absolute URLs on the canonical origin, so the same file works opened from disk.
- Motion is an enhancement. Without the CDN script, or under `prefers-reduced-motion`, every scene
  is already in its final state.
- The theme's `logoLink` carries a `target`, which makes VitePress's client router step aside, so the
  logo on a docs page does a full load of the static home instead of routing to a page that no
  longer exists.
- Every claim on the page comes from the guides, and the only figure presented as the reader's is
  the time they took themselves. The demo broker names and counts are illustrative and labelled as
  such.

## Consequences

- The home page tells the story the product exists for, and the README can link to it rather than
  retell it.
- The home page is no longer checked by VitePress's dead-link pass and does not appear in the
  generated sitemap. It carries a canonical link, and every docs page links to it.
- Two third-party origins, cdnjs and Google Fonts, are now loaded by the home page. If either is
  unreachable, the page stays readable: it falls back to static scenes and system fonts.
- The home page is English only. The `/zh/` and `/fa/` home pages keep the default theme's hero, so
  ADR-0061's three languages still hold for the rest of the site, but the three home pages no longer
  match.
- Copy on the page has to be kept honest by hand when the product changes, just like the guides.

## Alternatives considered

**A Vue component in the VitePress theme.** Keeps the page inside the build and its dead-link
check, but the page then only exists as part of the site bundle, and it can't be opened or passed
around as one file. The interaction and scroll code would be the same either way.

**Keeping the default hero and adding a video.** Cheapest option, but the reader only watches. They
never feel the hunt, and that's the point of the page.

**Adding GSAP as an npm dependency of the site.** Doesn't work for a file in `public/`, because it is
copied without being bundled. A pinned CDN URL is the only way that file can load a library.
