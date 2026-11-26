# ADR-0232: A fixable finding the image cannot carry is suppressed, not merged past

## Status

Accepted. Amends ADR-0139's scan rule.

## Context

ADR-0139 fails a pull request on a high or critical Grype finding that has a fix, and reports the rest.
That rule assumes the fix can be taken: bump the dependency or the base digest. Sometimes it cannot,
because the fixed version does not exist yet.

On 2026-11-26 the scan failed on `tomcat-embed-core` 11.0.29 for CVE-2026-133325, fixed in 11.0.30.
Maven Central's newest 11.x is 11.0.29, and no Spring Boot 4.1.x manages a newer Tomcat. The image
could not carry the fix, and the scan failed every pull request that touched the image inputs — a
change unrelated to Tomcat.

The alternatives were to fail every such pull request until Apache publishes, to drop the scan, or
to widen the exception silently by lowering the severity threshold. The first blocks work with no
remedy, the second removes the gate, and the third hides the next real finding.

## Decision

Grype reads a `.grype.yaml` at the repository root holding an explicit `ignore` list. An entry names
the one vulnerability, and its comment carries:

- the date it was added;
- the version that fixes it;
- why that version cannot ship yet;
- what removes the entry.

The scan passes the file with `-c` because the Grype image has no shell and its working directory is
`/tmp`, so a file next to the Dockerfile would not be found. The first run adds `--show-suppressed`,
so a suppressed finding is printed as suppressed rather than disappearing. `--only-fixed --fail-on
high` stays the gate.

## Consequences

- A pull request stays green when its only blocking finding has no published fix, and the
  suppression is visible in the log and in review.
- The file is a debt list. Each entry is a line someone must remove, and the comment says when.
- A second finding of the same severity on the same component needs its own entry: an ignore rule
  names one vulnerability, not a package.

## Alternatives considered

- **Lower `--fail-on` to `critical`**: hides every fixable high finding, not one.
- **Skip the scan when it fails**: removes the gate for everything.
- **Patch the Tomcat jar or override the module**: an unverified build of a security-critical
  component is worse than the advisory.
- **Bump Spring Boot to a release managing a fixed Tomcat**: none exists yet; 4.1.3 still manages
  11.0.27.
