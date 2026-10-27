---
title: Diagnostics and bug reports
description: Report a bug with Rahdar's versions and environment filled in, or download a redacted support bundle (logs, settings, health, thread dump, plugins) that you review and trim first. Nothing leaves your installation until you choose.
---

# Diagnostics and bug reports

When something goes wrong, a maintainer's first questions are always the same: which version,
which environment, what do the logs say? Rahdar answers them for you, and nothing leaves your
installation until you decide it should.

## Report a bug

Open the **user menu** (your initials, top right) and choose **Report a bug…**. Any signed-in
user can do this.

1. Give the issue a title, then describe what happened, what you expected and how to reproduce it.
2. Expand **Environment (included)** to see what Rahdar adds: the Rahdar version and plugin contract, Java,
   the operating system, the database, the sign-in providers, the installed plugins with their versions, and
   your browser. Secrets are never part of it.
3. Choose one of:
   - **Open on GitHub** opens a new issue on github.com with everything filled in. Nothing is sent until you
     submit it there. A very long report does not fit in a link; the issue then opens with only its title, and
     the text is on your clipboard to paste.
   - **Copy as Markdown** copies the whole report. Use it on an installation without internet access, and
     send the text however you like.

## Download a support bundle

**Administration → Diagnostics** builds a support bundle: one zip file with everything a maintainer needs.
It needs the permission **Create support bundles** (`diagnostics:bundle`), which the built-in Admin role
holds.

| Section | File | What it holds |
| --- | --- | --- |
| About | `about.json` | Rahdar version, plugin contract, Java, OS, CPUs, memory, uptime, database, and the registered clusters (name, health, node count) |
| Settings | `settings.json` | Every effective setting, and the secret-key status (provider, versions). Never a secret value |
| Health | `health.json` | Every health check with its details, including `brokerLinks`: each broker link (a plugin consumer, a capture, a relay) that is not open, with its node, the node serving it and why it was lost, and the last 100 link state changes |
| Features and plugins | `plugins.json` | Built-in features (enabled or not) and installed plugins with version, status and signature |
| Thread dump | `threads.txt` | What every thread is doing |
| Logs | `logs.txt` | The most recent log lines since Rahdar started, at most 5 000. Older lines are in the container log (`docker logs`) |

1. Press **Prepare bundle**. Rahdar collects every section on the server. It needs no internet connection.
2. **Review it.** Select a section to read it exactly as it will be written. Every `[redacted]` marker is
   highlighted, and each section shows how many values were removed. Use the search box to find a line.
3. **Trim it.** Clear the box of any section you do not want to share.
4. Press **Download bundle**. The file holds exactly the sections you kept, as you previewed them, even if
   new log lines were written in the meantime.

A prepared bundle is kept for ten minutes, and only for the administrator who prepared it. After that, press
**Prepare again**.

## What is never included

- **Credentials.** Every section passes through the same redaction as Rahdar's logs and audit trail: values
  of keys named like password, secret, token, API key or credential, bearer and basic authorization values,
  user information in URLs, private keys and Rahdar API tokens are replaced with `[redacted]`.
- **Message content.** No section reads a message, so no body or property value is included, whatever your
  data governance policy allows.
- **Anything automatic.** Rahdar sends no telemetry and uploads no bundle. It stays on your machine
  until you send it.

Every download is recorded in the audit log as `CREATE_DIAGNOSTICS_BUNDLE`, with who downloaded it, when,
and which sections it held.
