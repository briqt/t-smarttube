---
type: maintenance-guide
description: Shared enhancement integration and stable/beta release automation
---

# Release tools

The application enhancement has one source of truth. Each channel is built from
its upstream release commit plus the same enhancement delta; building two flavors
of the latest development checkout is not a substitute for this.

These tools use Python 3.9+ standard-library modules, Git, the GitHub CLI, JDK 17,
and the Android SDK. They do not generate a release key or enable remote releases.

## Source selection

`observe.py` makes only GitHub REST GET requests to `yuliskov/SmartTube`:

```sh
python3 -I tools/release/observe.py --output-dir /tmp/new-source-observation
```

It reads every numbered release-list page through a short/empty terminal page,
selects the newest numbered stable/beta releases, resolves annotated tags, and
pins each commit's exact tree and root gitlinks. Release names, dates, notes and
upstream APK replacements are not source identities. Historical prerelease flags
are not required to follow today's convention; the selected current candidates
must agree with it. Errors are not reported as successful “no update” results.

An observation explicitly has `scope: upstream-root-source-only` and
`releaseReady: false`. The subsequent integration step checks out the exact
commit and verifies recursive submodules before any Gradle task runs. A tag is
checked again before feed promotion. Neither pagination nor repeated GETs is an
atomic GitHub snapshot; a tag that moves and moves back between reads cannot be
detected by these observations alone.

## One enhancement delta

`config.json` records the **single upstream snapshot on which the enhancement is
based**, as `upstreamBase`. The workflow exports the application delta once from
that pinned base, then applies that same binary patch to each selected release.
When deliberately moving the development baseline, update this pointer in the
same reviewed change. Do not infer it from current upstream master: an upstream
release branch can diverge, and a master merge base would then mix release-branch
changes into the enhancement. It is also not recomputed independently per channel.

The application paths are explicit in `integration.APP_PATHS`. Release-control
files and maintenance Markdown are not copied into the application delta. Other
changed build inputs fail closed instead of silently disappearing. Symlink or
submodule additions in the application delta are not accepted. New application
modules require a deliberate update to this path list.

The exporter uses a temporary Git index. It does not stage, commit, reset, or
switch the developer's worktree. Normal release planning requires a clean public
worktree. For local testing only:

```sh
python3 -I tools/release/pipeline.py plan \
  --observation /tmp/new-source-observation/observation.json \
  --output-dir /tmp/new-plans --fetch-upstream --include-worktree
```

`--include-worktree` always marks the result as a nonpublishable snapshot. Signing
and publication entry points reject that marker. Candidate directories and run
outputs must be new; existing runs are never overwritten.

Integration stops on a patch conflict. It never automatically chooses ours/theirs
and never replaces the upstream version literals. Submodule URLs/paths must match
the supported official graph, and their checked-out commits must match gitlinks.
Local object caches are an optimization, not an alternative source identity.
Neighbor directories must not shadow the locked root submodules.

## Version numbers

`config.json` supplies `enhancementRevision` (integer 0–999) and a build recipe
revision. The application retains the simple formula in `gradle/fork-identity.gradle`:

```text
versionCode = upstreamVersionCode * 1000 + enhancementRevision
versionName = upstreamVersionName + "-t" + enhancementRevision
```

Increment the enhancement revision when changing APK inputs for the same upstream
version. Both packages use the same enhancement, but each has its own feed and
upgrade history. There is no independent version-number allocation service.
Lower codes, a different candidate reusing a code, a changed signer, and different
APK bytes reusing a completed code are errors, not instructions to overwrite old
artifacts. An optional `forkBuildTimestamp` build property fixes the embedded
build timestamp for the selected source; this is not a claim of bit-for-bit
reproducible builds.

## Validation and signing

The existing application update UI/client is retained. The release verifier
checks actual APK package/version/debuggable state, API17 minimum, search
identity, channel feed, native ABI directories/ELF machine headers and pairing assets. Signed verification also
checks the expected certificate and v1 signature support. It does not add client
manifest signature validation.

`pipeline.py build` runs release lint, release assembly, the subtitle tests, and
the native Preference test. It requires every expected suite, fresh XML results,
and zero failures/errors/skips. The receipt binds the unsigned APK lengths/hashes;
signing rejects swapped APKs even when their package/version fields match. Its
receipt states the tested scope; it does not claim all upstream tests or device/TV acceptance. Unsigned APKs are not releases.
The universal APK's actual native ABIs are recorded, not inferred from its name;
it is not a promise of compatibility with every CPU architecture.

The workflows isolate arbitrary Gradle execution from signing and publishing:

- **Build:** read-only token, no signing environment or inherited secrets.
- **Sign:** a fresh runner using the `release-signing` environment; it never runs
  Gradle or scripts from the candidate build tree.
- **Publish:** another fresh runner; rechecks signed APKs before remote writes and
  does not receive the signing environment.

Artifacts are passed by IDs from the expected jobs. Controller code comes from
the caller's fixed commit, not from an uploaded artifact. Checkout credentials
are not persisted; signing/publishing do not restore build-writable Gradle caches.

## Activation

The fork's legacy upstream CI/scanning jobs are restricted to the upstream
repository. The fork uses:

- `.github/workflows/t-smarttube-follow.yml`
- `.github/workflows/t-smarttube-channel.yml`

Manual dispatch defaults to validation only. Push validation is available on
master; scheduled following and all signing/publication are **off by default**.
Scheduled detection uses a half-hour cron outside the hour boundary. GitHub may
delay/drop schedules, and fork/inactive-repository schedules may need enabling;
this is not an immediate or hard-30-minute delivery guarantee.

Only after the required acceptance and explicit activation:

1. Keep the dedicated long-term signing key backed up outside the source tree.
2. Configure **environment secrets in `release-signing`**, not repository-wide
   signing secrets and not `secrets: inherit`:
   `TSMARTTUBE_SIGNING_KEY` (base64 keystore), `TSMARTTUBE_STORE_PASSWORD`,
   `TSMARTTUBE_KEY_PASSWORD`, `TSMARTTUBE_KEY_ALIAS`.
3. Set repository variable `TSMARTTUBE_CERT_SHA256` to the public certificate's
   lowercase SHA-256 fingerprint.
4. Protect the signing environment/source branch as appropriate, enable the
   workflow/schedule, and set `TSMARTTUBE_RELEASES_ENABLED=true`.
5. A manual publication also requires the dispatch `publish` input to be true.

A successful validation run with signing/publication skipped is **not** a
published release. No key value belongs in source, logs, arguments, or a feed.
The signer uses environment password references and a private temporary keystore
file, which is removed when the signing scope exits.

## Publication and failure behavior

The release tag identifies the enhancement/controller commit. Exact APK inputs
are the upstream commit plus the delta recorded in `source-lock.json`; do not
mistake the tag's automatically generated source ZIP alone for the integrated
build tree. Recreate recorded inputs rather than observing today's latest tags.
Existing release tags must resolve to the recorded enhancement commit; a tag
collision or unexpected movement is rejected before feed promotion.

Publication uses the existing compatible JSON schema. It does not insert new
metadata keys into the feed: the original client treats every non-`package`
top-level key as a version. ABI URL arrays are nonempty, and package URLs point
only to this fork's version-specific assets.

The publisher creates a draft, uploads all required assets, checks downloaded
bytes, publishes the complete release, checks public downloads, and only then
updates the channel feed and its receipt in one `updates` branch commit. Old
assets stay unchanged. Non-fast-forward conflicts cause bounded state reloads,
not force pushes; an older candidate cannot overwrite a newer one or the other
channel's changes. A receipt records published inputs/bytes; it does not allocate
version numbers.

A public readback mismatch is an explicit error, not success. The pointer may
already have been committed, so recovery must reread current state rather than
blindly restore an old snapshot over a newer publication. CDN propagation can
make a valid old feed temporarily visible. Both the old and new feed must refer
to already-verified immutable assets.

For interrupted upload/publication, retry the **same signed artifact** (for
example, rerun only the failed publication job). Do not rebuild and clobber the
same version tag: different bytes under an already-used code are refused.

The direct publisher CLI also requires an explicit `--publish` flag and an
independently supplied expected certificate. Running ordinary tests or source
observation never performs remote writes.

## Tests

```sh
python3 -I -m unittest discover -s tools/release -p 'test_*.py' -v
```

Fixtures exercise the real controllers through their I/O seams, including local
Git integration, failed verification, code/identity conflicts, interrupted
publication, CAS races and malformed feeds. They do not establish live Actions
permissions, real release signing, public feed deployment, or an installed
application's same-signature upgrade. Those require their own acceptance.
