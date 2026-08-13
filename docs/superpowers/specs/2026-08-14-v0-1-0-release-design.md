# Tessera v0.1.0 Release Design

## Goal

Publish Tessera's first usable public release as `v0.1.0`. The release must
contain the complete current MVP, including ordered same-file constants, and a
single executable JAR that can run the documented CLI on Java 17 or newer.

## Current State

- The public repository is `kmizu/tessera` and its default branch is `main`.
- GitHub has no existing tags or releases for the repository.
- The remote `main` branch still contains only the bootstrap commit, while the
  complete MVP history exists locally through `codex/ordered-module-constants`.
- `build.sbt` reports `0.0.1-SNAPSHOT` and has no packaging plugin.
- There are no GitHub Actions workflows yet.
- The full local suite currently passes with 80 tests.

## Release Identity

- Project version: `0.1.0`
- Git tag: annotated tag `v0.1.0`
- GitHub Release title: `Tessera v0.1.0`
- Runtime baseline: Java 17+
- Scala version remains 3.4.2 for this release.

The build version and tag must match exactly after removing the tag's leading
`v`. A mismatched tag must fail before packaging or publication.

## Artifact

The primary downloadable artifact is a single executable assembly JAR:

```text
tessera-0.1.0.jar
```

The manifest points to `tessera.Main`, so users can run:

```bash
java -jar tessera-0.1.0.jar check examples/Constants.tes
```

The release also contains:

```text
tessera-0.1.0.jar.sha256
```

The checksum file uses the portable `<hex>  <filename>` format. GitHub's
automatically generated source archives remain available, but they are not the
primary runnable distribution.

## Build Configuration

Use sbt-assembly because Tessera is a JVM CLI with no platform-specific native
dependencies. The build will:

- set the project version to `0.1.0`;
- set `Compile / mainClass` and `assembly / mainClass` to `tessera.Main`;
- produce the stable artifact name `tessera-0.1.0.jar`;
- avoid publishing test dependencies in the assembly;
- preserve the existing `sbt test` behavior.

No Maven Central publication, native image, installer, launcher ZIP, or package
manager integration is part of `v0.1.0`.

## Continuous Integration

Add a CI workflow for pull requests and pushes to `main`. It runs on a Linux
GitHub-hosted runner with Java 17 and performs:

1. checkout;
2. Java and sbt setup with dependency caching;
3. `sbt test`.

The workflow uses least privilege (`contents: read`) and concurrency
cancellation for superseded runs. Its purpose is to protect the exact runtime
baseline advertised by the release, not to claim broader operating-system or
JDK compatibility.

## Release Automation

Add a release workflow triggered only by tags matching `v*`. It uses
`contents: write` plus read-only Actions access and performs these stages in order:

1. check out the exact tagged commit;
2. verify the tag is `v0.1.0`-shaped and equals `v` plus the sbt project
   version;
3. require an annotated tag whose peeled commit is contained in `main` and has
   a successful Java 17 CI check;
4. configure Java 17 and sbt;
5. run the complete test suite;
6. build the assembly JAR;
7. run a smoke test with the JAR against `examples/Constants.tes` and require
   `id`, `alias`, and `inferredAlias` to succeed;
8. generate and verify the SHA-256 checksum;
9. create the GitHub Release and upload both files.

Publication happens only after every validation stage succeeds. Re-running the
workflow for an existing release must fail clearly rather than silently replace
published assets.

## Release Notes

Use curated notes committed in the repository instead of relying only on
auto-generated commit summaries. The `v0.1.0` notes cover:

- the minimal dependent-term kernel and definitional equality;
- parser and declaration CLI commands;
- structured `eval --trace` output;
- `synth do` / `for`, `param`, `let`, and bind desugaring;
- tuple syntax for arities 2 through 10;
- explicit `Synth` combinators with kernel rechecking;
- ordered same-file constants and transparent delta reduction;
- known limits: no imports, recursion, opacity, general inductives, or stable
  language compatibility guarantee.

## Integration and Publication Flow

The current feature branch receives the release infrastructure and release
notes. After local verification:

1. push the branch and open a pull request to `main`;
2. require the PR CI check to pass;
3. merge the PR without rewriting its reviewed content;
4. verify `main` CI on the merged commit;
5. create and push annotated tag `v0.1.0` on that exact merged commit;
6. wait for the release workflow;
7. verify the GitHub Release, both assets, checksum, and downloaded JAR smoke
   test independently.

The tag is not created from the feature branch. This keeps the release tied to
the public default-branch history.

## Failure Handling

- Test, assembly, smoke, checksum, tag/version, annotated-tag, main-ancestry,
  or green-CI provenance failures prevent publication.
- A failed release workflow leaves no manually created replacement release.
- A tag pointing at the wrong commit is not force-moved. Corrective action uses
  a new version unless the unpublished tag can be removed safely with explicit
  user authorization.
- GitHub Release publication and tag creation happen only after the pull request
  has merged and `main` is green.

## Documentation

README gains a binary-download quick start and the Java 17 requirement while
retaining source-build commands. `IMPLEMENTATION_STATUS.md` records the first
release packaging/CI capability without claiming native or package-manager
distribution.

## Testing and Completion Evidence

Before opening the pull request:

- `sbt test` passes;
- `sbt assembly` succeeds;
- `java -jar target/.../tessera-0.1.0.jar check examples/Constants.tes`
  succeeds;
- the checksum verifies locally;
- workflow syntax and version/tag validation scripts are exercised against
  valid and invalid inputs where practical;
- `git diff --check` is clean.

The release is complete only when the remote `main`, annotated tag, successful
GitHub Actions runs, published GitHub Release, downloadable artifacts, checksum,
and an independent downloaded-JAR smoke test have all been verified.

## Non-goals

- Maven Central or another artifact repository
- native binaries or GraalVM
- Windows/macOS-specific launchers
- Homebrew, Scoop, SDKMAN, or package-manager recipes
- signed artifacts, SBOM, or provenance attestations
- a compatibility promise beyond the documented MVP
- choosing or adding a software license without explicit owner direction
