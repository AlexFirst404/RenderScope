# Offline platform publication verification

`scripts/verify-platform-030.py` reads the current prepared release manifest, current source/packaged JARs and the actual UI submission ledgers. It does not access the network, start Minecraft, upload, retry submissions, alter ledgers or generate release files. Its reports go to stdout.

```powershell
python scripts/verify-platform-030.py
python scripts/verify-platform-030.py --platform modrinth --require-downloads
python scripts/verify-platform-030.py --platform curseforge --json
```

The default check requires **67 current submissions and 67 verified saved metadata records per platform**. Missing downloads remain explicitly pending; `--require-downloads` additionally requires all 67 actual-download fingerprints. A supplied wrong download hash, size or retained file fails even without that flag. Partial submissions never count as a complete release.

The exit status and `success` describe these local checks. They do **not** mean moderation approval, public visibility or platform availability. Raw `observedStatus` strings are counted separately, and `moderationApprovalInferred` is always false. A complete submitted ledger can therefore pass metadata checks while every CurseForge file still says `Processing` or `Under Review`.

## Ledger contract

Actual ledgers live at:

- `distribution/platforms/0.3.0/modrinth-release-verification.json`
- `distribution/platforms/0.3.0/curseforge-release-verification.json`

The header contains `schemaVersion: 1`, `renderscopeVersion: "0.3.0"`, `platform`, the actual `projectId`, `manifestSha256` of the exact prepared `release-manifest.json` bytes, and `files`. All 67 distinct `loader:minecraft` identities must come from that current manifest. Remote IDs must be nonempty strings and unique within a platform.

Record the intent **before the final UI submission**, with these fields copied from the matching current target:

| Intent field | Meaning |
| --- | --- |
| `identity`, `loader`, `mc` | Exactly one loader and Minecraft version |
| `sha256`, `bytes`, `file`, `packagedArtifact` | Exact current uploaded JAR identity and repository-relative location |
| `expectedGameVersions`, `expectedLoaders` | One-element arrays for that target |
| `javaRuntime`, `clientOnly`, `expectedReleaseChannel` | Exact Java requirement, `true`, and `"release"` |
| `expectedFabricApi` | The current manifest's full Fabric API record, or `null` for other loaders |
| `submissionStartedAt`, `submissionState` | Timezone-bearing timestamp and initially `"submitting"` |

An interrupted or uncertain `submitting` record remains pending. Inspect the existing remote attempt before any retry; creating a second record for that identity is rejected.

After the actual save, record `submissionState: "submitted"`, the real string `remoteId`, HTTPS `remoteUrl`, and raw `observedStatus`. Inspect the **saved** file/version UI again before recording `metadataVerified: true`, `metadataSource: "post-save-ui"`, `metadataVerifiedAt` and `observedMetadata`. Reading an unsaved form is insufficient.

`observedMetadata` contains:

- `gameVersions: [minecraft]`, `loaders: [loader]`, `clientOnly: true`, `releaseChannel: "release"`, and the exact `fileName`.
- Modrinth: the exact current `versionNumber`.
- CurseForge: `javaRuntime` read from the saved file's Java tag. Modrinth's Java requirement stays in the intent; the verifier does not invent a Java UI tag.
- `requiredDependencies`, explicitly listing only dependencies observed with the **Required** relation. An optional `dependencyType` field, when supplied, must be `"required"`.

For Fabric, Modrinth requires exactly the Fabric API project `P7dR8mSH` and the exact `versionId` from that Minecraft target's current build pin. CurseForge requires exactly project **306612** as a Required Dependency. CurseForge related projects cannot express that version pin: do not record a fabricated `versionId` or `version`. Non-Fabric files require an empty `requiredDependencies` list.

## Actual download evidence

Only after actually downloading remote bytes, record HTTPS `downloadUrl`, `downloadedSha256`, integer `downloadedBytes` and timezone-bearing `downloadVerifiedAt`. These must match the current intended JAR. Do not copy the upload's known checksum into a download record without fetching the remote file.

The optional `downloadedArtifact` points to the retained download under a repository-relative path such as `.tools/platform-downloads-030/modrinth/`. It must never contain an absolute user path. When the file exists, the verifier reads and hashes its actual bytes. A changed cache file fails; a removed optional cache produces a note and reduces `downloadCacheRehashedCount` without erasing the recorded historical hash/size/URL/time. Public records can therefore remain useful after the ignored local cache is cleared.

The report separates `submittedCount`, `metadataVerifiedCount`, `downloadVerifiedCount`, `downloadCacheRehashedCount`, missing identities, raw status counts and errors. Recorded historical downloads and newly rehashed local cache files are different facts. This offline tool validates the records and available bytes; the producer remains responsible for obtaining UI observations and downloads from the actual platform.

Regression fixtures exist only in temporary folders created by `scripts/test-verify-platform-030.py`. They are never written to the real submission ledgers.
