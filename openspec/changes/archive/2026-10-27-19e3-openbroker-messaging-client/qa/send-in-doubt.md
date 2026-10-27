# QA: the in-doubt send and the audit outcome

The change adds no screen. Its UI is the in-doubt toast of the send dialog and the "in doubt" outcome in the
audit grid. An in-doubt send cannot be produced on demand against a live stack, so the states were captured in
Vitest browser mode (`web/src/ui/notify.browser.test.tsx`), at 1920 × 1080, light and dark, with axe
(WCAG 2.2 AA) on the whole document, and with a long subject.

- Light and dark: the title reads "Not confirmed: sending a message to subscription …", the detail says what to
  check, the edge takes the warning token, the long subject wraps inside the toast, and axe finds nothing.
- The audit grid's "in doubt" is a `StatusBadge` in the warning tone, whose light and dark browser tests already
  cover contrast and layout (`web/src/ui/StatusBadge.browser.test.tsx`); its words are asserted in
  `AuditView.test.tsx`.

No findings.
