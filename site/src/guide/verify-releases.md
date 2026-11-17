---
title: Verify a release
description: Check that a Rahdar image, jar or plugin kit package was built by this project's release workflow, and read its SBOM.
---

# Verify a release

Every published artifact carries a signed attestation of where it came from. There is no key of ours to
download: each signature is made with a short-lived certificate issued to the workflow that built it,
through [Sigstore](https://www.sigstore.dev/), so the signer is the workflow itself.

| Artifact | Signed | SLSA provenance | CycloneDX SBOM | Signer workflow |
| --- | --- | --- | --- | --- |
| Stable image (`:<version>`, `:latest`, `:stable`) | yes | yes | yes, attached to the image and the release | `release.yml` |
| Jar on the GitHub release | yes | yes, also as `.intoto.jsonl` | yes, attached to the release | `release.yml` |
| Moving image `:dev` | yes | yes | yes, attached to the image | `ci.yml` |
| Plugin API and Java client on Maven Central | yes (and GPG) | yes | no | `publish-central.yml` |
| `@rahdar/plugin-sdk` and `@rahdar/client` on npm | yes | yes (npm provenance) | no | `release.yml` |

A stable release published before this split, and its jar, was signed by `ci.yml`. Pass that
workflow name when the release predates `release.yml`.

The repository is always `sudoitir/rahdar`. The commands below use `release.yml`.

You need the [GitHub CLI](https://cli.github.com/) (`gh`), logged in to any GitHub account.

## The image

```bash
V=2026.10.0   # the release you run
gh attestation verify oci://docker.io/sudoit1/rahdar:$V \
  --repo sudoitir/rahdar \
  --signer-workflow sudoitir/rahdar/.github/workflows/release.yml
```

A pass prints `✓ Verification succeeded!` and the attestation's source commit and workflow run. Add
`--bundle-from-oci` to read the attestation from Docker Hub instead of GitHub; it is stored next to
the image. For a pinned digest, use `oci://docker.io/sudoit1/rahdar@sha256:…`.

`:dev` is signed by `ci.yml`:

```bash
gh attestation verify oci://docker.io/sudoit1/rahdar:dev \
  --repo sudoitir/rahdar \
  --signer-workflow sudoitir/rahdar/.github/workflows/ci.yml
```

## The jar

Download `rahdar-<version>.jar` from the
[release](https://github.com/sudoitir/rahdar/releases), then:

```bash
gh attestation verify rahdar-$V.jar \
  --repo sudoitir/rahdar \
  --signer-workflow sudoitir/rahdar/.github/workflows/release.yml
```

To check against the bundle on the release page instead of GitHub's attestation store, add
`--bundle rahdar-$V.jar.intoto.jsonl`.

## The SBOM

Each release page carries `rahdar-<version>.image.cdx.json` and
`rahdar-<version>.jar.cdx.json`. The image's SBOM is also a signed attestation on the image:

```bash
gh attestation verify oci://docker.io/sudoit1/rahdar:$V \
  --repo sudoitir/rahdar \
  --predicate-type https://cyclonedx.org/bom \
  --format json --jq '.[0].verificationResult.statement.predicate' > sbom.cdx.json
```

Feed it to your scanner, for example `grype sbom:sbom.cdx.json`.

## The plugin kit

The plugin API jar from Maven Central is signed by `publish-central.yml`:

```bash
curl -fsSLO https://repo1.maven.org/maven2/io/github/sudoitir/rahdar/$V/rahdar-$V.jar
gh attestation verify rahdar-$V.jar \
  --repo sudoitir/rahdar \
  --signer-workflow sudoitir/rahdar/.github/workflows/publish-central.yml
```

For the npm package, in a project that depends on it:

```bash
npm audit signatures
```

It checks the registry signature and the provenance of `@rahdar/plugin-sdk` and `@rahdar/client`, and npmjs.com
shows the provenance (repository, commit and workflow) on the package page.

## When verification fails

`gh attestation verify` exits non-zero and says why. A file changed after the release has another
digest, so no attestation matches it. A package published anywhere but the release workflow has no
attestation from this repository. Do not run either, and
[report it](https://github.com/sudoitir/rahdar/security/advisories/new).
