# Publishing & Distribution

This plugin ships through three channels. The first two are automated by the
`Release` workflow (`.github/workflows/release.yml`) on every `v*` tag. The
third (JMeter Plugins Manager) is a one-time registration plus a JSON bump per
release.

| Channel | Artifact | Audience | Automated? |
|---------|----------|----------|------------|
| Maven Central | thin jar (`from components.java`) | Maven/Gradle consumers, Plugin Manager | Yes (on tag) |
| GitHub Releases | fat jar (`-all.jar`) | Manual `lib/ext` install | Yes (on tag) |
| JMeter Plugins Manager | thin jar + declared libs | JMeter GUI users | Manual JSON entry |

## 1. Maven Central (automated)

Pushing a `vX.Y.Z` tag runs `publishToSonatype closeAndReleaseSonatypeStagingRepository`,
which uploads through the **OSSRH Staging API bridge**
(`ossrh-staging-api.central.sonatype.com`, see `build.gradle`) in front of the
new **Central Portal**.

Release checklist:

1. Bump `version` in `build.gradle` and update `CHANGELOG.md`.
2. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. Watch the run: `gh run watch --repo perfana/perfana-jmeter-timescaledb`.

### Gotcha: green build ≠ live on Central

The Gradle run reports `state: released` and `BUILD SUCCESSFUL` once the staging
repo is handed off to the Central Portal. It is **not** on `repo1.maven.org`
yet. The Portal then validates and publishes the deployment:

- If the `io.perfana` namespace is set to **Automatic** publishing (Portal →
  Namespaces), the deployment moves to `PUBLISHING` → `PUBLISHED` on its own.
  Sync to `repo1.maven.org` typically takes 15–60 min.
- If set to **Manual**, the deployment sits in `VALIDATED` until you click
  **Publish** at https://central.sonatype.com → Deployments.

Verify it landed:

```bash
curl -sI https://repo1.maven.org/maven2/io/perfana/perfana-jmeter-timescaledb/X.Y.Z/perfana-jmeter-timescaledb-X.Y.Z.jar
# expect: HTTP/2 200
```

## 2. GitHub Releases (automated)

The release job builds the fat jar (`./gradlew fatJar` →
`perfana-jmeter-timescaledb-X.Y.Z-all.jar`) and attaches it to the GitHub
Release. This is the manual-install path: download and drop into
`JMETER_HOME/lib/ext/`. It bundles all dependencies, so no separate libs are
needed.

## 3. JMeter Plugins Manager (manual registration)

The Plugins Manager is a catalog of JSON descriptors in
[`undera/jmeter-plugins`](https://github.com/undera/jmeter-plugins) under
`site/dat/repo/`. You get listed by opening a PR that adds your descriptor, then
raising it on the [community forum](https://jmeter-plugins.org/support/) so the
maintainers merge and pick it up. There is no source-code handover — you keep
releasing yourself; the catalog just points at your Maven Central download URLs.

The source-of-truth descriptor lives in this repo at
[`plugin-manager.json`](../plugin-manager.json).

### Why the thin jar, not the fat jar

Plugin Manager installs the plugin jar into `lib/ext/` and each declared `lib`
into `lib/` separately, then dedupes libs across all installed plugins. So the
descriptor points at the **thin** Maven Central jar and declares `postgresql`
and `HikariCP` in `libs` — never the shaded `-all.jar`. (`log4j` is provided by
JMeter itself, which is why it is `compileOnly` in `build.gradle` and absent
from `libs`.)

### Descriptor fields

| Field | Value / source | Required |
|-------|----------------|----------|
| `id` | `perfana-timescaledb` (stable, never change) | Yes |
| `name` / `description` | shown in the Plugin Manager UI | Yes |
| `screenshotUrl` | raw GitHub URL to a screenshot in `docs/images/` | Yes |
| `helpUrl` | repo URL | Yes |
| `vendor` | `Perfana` | Yes |
| `markerClass` | `io.perfana.jmeter.timescaledb.JMeterTimescaleDBBackendListenerClient` — how PM detects "installed" | Yes |
| `componentClasses` | the listener client class | Optional |
| `versions` | map of version → `{downloadUrl, libs}` | Yes |

`libs` keys use `name>=version` syntax so Plugin Manager can satisfy the minimum
against libs other plugins already installed.

### One-time setup before the first PR

1. **Add a screenshot.** `screenshotUrl` is mandatory. Add the JMeter Backend
   Listener config screen (with this class selected) at
   `docs/images/jmeter-backend-listener.png`. The descriptor already points at
   its raw GitHub URL on `main`.
2. **Confirm the version is live on Central** (see §1 verify step). Plugin
   Manager installs will 404 otherwise.

### Test the descriptor locally before the PR

Point JMeter's Plugin Manager at your local descriptor and confirm it installs
the right files:

```bash
# Serve the descriptor, then launch JMeter with the repo override:
jmeter -Jjpgc.repo.address=file:///ABSOLUTE/PATH/TO/plugin-manager.json
```

Open Options → Plugins Manager → Available Plugins, install, and verify:
- `perfana-jmeter-timescaledb-X.Y.Z.jar` landed in `lib/ext/`
- `postgresql-*.jar` and `HikariCP-*.jar` landed in `lib/`

### Submit

1. Fork `undera/jmeter-plugins`.
2. Add the contents of `plugin-manager.json` to a new
   `site/dat/repo/perfana.json` (or append to an existing third-party file).
3. Open the PR, then link it on the
   [JMeter Plugins forum](https://jmeter-plugins.org/support/).

### Per-release update

After each Maven Central release, add the new version to the `versions` map in
both `plugin-manager.json` here and the entry in `undera/jmeter-plugins` (PR).
Keep older versions in the map — Plugin Manager offers them for downgrade.
