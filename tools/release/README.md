# tools/release — iOS release through Swift Package Manager

The iOS library ships as a static `Teslable.xcframework` in a zip attached to a GitHub Release. The repository-root
`Package.swift` points at that zip with a `.binaryTarget(url:checksum:)`, so app developers add
`https://github.com/smallmiro/tesla-ble-kmp` in Xcode and `import Teslable` (ADR-0017). There is no CI for releases
(ADR-0012): everything runs on one Apple Silicon Mac.

## Files

| File | What it does |
|---|---|
| `build-xcframework.sh` | Full release build of `Teslable.xcframework`, checks (slices, iOS 16, Swift interface, static, an absolute Kotlin compile unit name, no build-machine paths), then `build/release/Teslable.xcframework.zip` and its `.sha256` (`swift package compute-checksum`). Every run gives a new checksum. |
| `check-consumer-ios.sh` | Builds an isolated consumer app (`consumer-ios/`) that links the XCFramework through SwiftPM: device build, a Release device archive whose app dSYM must hold the Kotlin debug info, and 4 simulator tests. No arguments: rebuilds first. `--no-build`: uses `build/release/`. `--remote <version>`: uses the published package. |
| `prepare.sh <version>` | Release phase 1: a branch with one commit that writes `Package.swift`. |
| `publish.sh <version>` | Release phase 2: tag, GitHub Release, remote checks. |
| `release-lib.sh` | Values and functions shared by `prepare.sh` and `publish.sh` (sourced, not run). |
| `test_release_scripts.sh` | Offline tests of `prepare.sh` and `publish.sh` (temporary git repositories, stand-ins for `gh`, `curl` and the build scripts). Part of `tools/ci/verify.sh`. |

## Requirements

- Apple Silicon Mac with Xcode, an `iPhone 17` simulator, `xcodegen`, `python3`.
- `gh` logged in (`gh auth login`) with push access to `smallmiro/tesla-ble-kmp`, recent enough that
  `gh release view --json` knows the `isImmutable` field (2.103 was used). The scripts use `gh` authentication only and
  never print a token.
- A clone whose `origin` is `smallmiro/tesla-ble-kmp` (HTTPS or SSH).
- Version numbers are SemVer `X.Y.Z` without a leading `v`. The first release is `1.0.0`; a change that breaks the
  public API needs a new major version.

## Release a version

`main` is protected, so a release takes two phases: `Package.swift` goes in through a pull request, and the tag and the
GitHub Release are made after it is merged. Run both phases on the same Mac: the zip whose checksum is in `Package.swift`
exists only there, in `build/release/v<version>/`.

### 1. Prepare (branch and pull request)

```bash
git switch main && git pull --ff-only
tools/release/prepare.sh 1.0.0
```

`prepare.sh` checks that the working tree is clean, that `HEAD` is the latest `origin/main`, that tag `v1.0.0` is not on
`origin` and that `build/release/v1.0.0/` does not exist yet. Then it:

1. creates the branch `release/v1.0.0`;
2. runs `check-consumer-ios.sh`, which rebuilds the XCFramework and checks the consumer app (several minutes);
3. keeps the zip, its `.sha256` and the git tree it was built from in `build/release/v1.0.0/`;
4. writes `Package.swift` with that checksum and reads it back with `swift package dump-package`;
5. commits `chore: release 1.0.0` (only `Package.swift`).

It does not push. Push the branch and open a pull request (put the checksum and the consumer check result in the body),
then merge it with a rebase merge. **Do not delete `build/release/` until `publish.sh` has finished.**

If `prepare.sh` stops, fix the cause and run the same command again: a `release/v<version>` branch without commits is
reused, and a failed run leaves no `Package.swift` and no `build/release/v<version>/`.

### 2. Publish (from the merged `main`)

```bash
git switch main && git pull --ff-only
tools/release/publish.sh 1.0.0
```

`publish.sh` never builds a zip. Before it changes anything it checks that `Package.swift` is exactly what `prepare.sh`
writes for this version, that `build/release/v1.0.0/` holds the zip with that checksum, and that the source has not
changed since the zip was built (only `Package.swift` may differ). Then it:

1. runs `tools/ci/verify.sh`, once per commit and zip (the result is kept in `build/release/v1.0.0/verified`);
2. creates the annotated tag `v1.0.0` and pushes only the tag (`main` is never pushed);
3. creates the GitHub Release as a draft, uploads the zip, downloads it again to check the checksum, and only then
   publishes it as a full release (not a prerelease). The release notes have an Installation section and the section
   "Real-vehicle check pending: #118";
4. downloads the zip from the URL in `Package.swift` and checks the checksum again;
5. runs `check-consumer-ios.sh --remote 1.0.0` against the published package.

It ends with `RELEASED v1.0.0`.

### Running publish.sh again

`publish.sh` can be run again after any failure; it looks at what exists and skips finished steps.

| State found | What happens |
|---|---|
| `verify.sh` already passed for this commit and zip | the gate is skipped |
| tag `v<version>` on `origin` at `HEAD` | not pushed again (if no gate record exists, the gate still runs first) |
| tag on `origin` at another commit, `HEAD` is `origin/main` | stops: a published tag never moves. If `main` moved on after the tag was pushed, finish from the tagged commit: `git fetch origin tag v<version> && git switch --detach v<version>` |
| draft or prerelease made by `publish.sh` | the zip is uploaded or checked, then the release is published as a full release |
| release asset with another checksum | stops without touching it. Delete it (`gh release delete-asset v<version> Teslable.xcframework.zip --repo smallmiro/tesla-ble-kmp`) and run again |
| immutable release (GitHub setting) without the right asset | stops: that version cannot be fixed. Prepare and publish a new version |
| release whose notes lack the two sections (not made by `publish.sh`) | stops: fix the notes or delete the release |

### When a check refuses

| Message | Meaning and fix |
|---|---|
| `version must be X.Y.Z` | Pass the version as `1.0.0` (no `v`, no leading zeros). |
| `working tree not clean` | Commit or remove the listed files. |
| `HEAD is not origin/main` | `git switch main && git pull --ff-only`. |
| `Package.swift version mismatch` | The merged `Package.swift` is for another version: merge the right `release/v<version>` pull request first. |
| `zip missing: run prepare.sh on this machine` | `build/release/v<version>/` is gone or this is another Mac. Run `prepare.sh <version>` again and merge its new `Package.swift`. |
| `checksum mismatch: do not rebuild the zip` | The kept zip or its `.sha256` no longer matches `Package.swift`. Never rebuild it by hand: run `prepare.sh` again and merge its `Package.swift`. |
| `the source changed since prepare.sh built the zip` | Other changes reached `main` before the release commit; the tag would not mark the source of the binary. Delete `build/release/v<version>/`, run `prepare.sh` again and merge. |

## Tests

```bash
tools/release/test_release_scripts.sh
```

Runs offline in temporary git repositories with a local bare repository as `origin`. `gh`, `curl` and the build and
gate scripts are stand-ins; `xcodebuild`, `xcodegen` and `gradle` fail if called. It covers the prechecks of both
scripts, the order in `prepare.sh` (the zip is built once, before the checksum is read) and the resume decisions of
`publish.sh`.

## See also

- ADR-0017 (release design) and ADR-0012 (local verification gate).
- Installation guide for app developers: `{{LIB_DOCS_DIR}}11-lib-overview.md` §Installation.
