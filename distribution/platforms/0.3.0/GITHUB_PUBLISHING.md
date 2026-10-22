# GitHub v0.3.0 publishing helper

`python scripts/publish-github-030.py` is a dry-run. It neither creates nor pushes a tag and never rebuilds or starts Minecraft. It stops before remote operations when a local gate fails.

Only the root operator should invoke `python scripts/publish-github-030.py --publish`, after all exact targets have current world evidence and publication is ready.

Before that invocation:

1. Finish the 67 exact builds, actual ownership/block-replacement/export XML regressions and hash-matched worlds; preserve installed-production versus development-client scope.
2. Stage the as-produced evidence bytes, regenerate `scripts/prepare-publication-030.py`, stage its output, then run its `--verify-git-evidence` check.
3. Commit the final files and create/push the exact `v0.3.0` tag through the root workflow. The helper requires a clean repository and checks the tag's raw evidence/JAR/JSON blobs, not merely a manifest's readiness flag.
4. Run the dry-run. It also verifies that the pushed tag resolves to the same commit and inspects any existing release assets through authenticated GitHub CLI.
5. Invoke `--publish` explicitly. A new release stays draft while the helper uploads missing files and verifies all 68 assets: 67 primary JARs and `SHA256SUMS`. Only then does it publish a stable release and mark it latest.

An interrupted draft can be resumed with the same command. Matching assets are reused. Unexpected, duplicate, incomplete or mismatched assets are rejected; the helper never uses `--clobber` or deletes files. An already complete public release is verified without changing it.

Every asset must match size and GitHub's SHA-256 digest. If the API omits its digest, the helper downloads and hashes the actual bytes. Notes come from the tagged bilingual notes file; its two repository-relative dependency/evidence links are mechanically expanded to absolute `v0.3.0` URLs.

GitHub assets have flat filenames. The release's LF `SHA256SUMS` is therefore derived from those 67 filenames. The separate package index's loader-directory checksum file is left untouched.

On verified success the helper writes `distribution/platforms/0.3.0/github-release-verification.json`. That file does not exist before publication succeeds and is not an approval or moderation record for Modrinth/CurseForge. Commit it afterward through the root workflow. The helper does not edit the global platform status JSON.

Offline tests: `python scripts/test-publish-github-030.py`.

Implementation follows the official [GitHub CLI release workflow](https://cli.github.com/manual/gh_release_create) and [release asset API](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).
