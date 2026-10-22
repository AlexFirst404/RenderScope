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

## Inbound download helper

After recording a real saved file and its observed CDN URL, run:

```powershell
python scripts/download-platform-030.py --platform modrinth
python scripts/download-platform-030.py --platform curseforge
python scripts/download-platform-030.py --platform both
```

This helper performs inbound public GETs only. It requires the current-ready manifest and matching ledger manifest hash, and processes only `submitted` entries with verified post-save metadata. Partial ledgers are accepted; unsaved records and absent links remain pending. It never constructs a URL from a file ID, requests upload/authentication APIs, uses account cookies, or operates a browser.

Only recorded HTTPS URLs on `cdn.modrinth.com` or the CurseForge CDN hosts `edge.forgecdn.net`, `media.forgecdn.net` and `mediafilez.forgecdn.net` are allowed. Every redirect and final response URL must remain on the same platform's exact allowlist. The response is streamed with an expected-size cap, and actual SHA-256/byte count must match before a cache file and ledger proof are committed. Network errors and mismatches stop the sequential run explicitly; they never manufacture verified fields.

The cache is `.tools/platform-downloads-030/<platform>/`. Resume rehashes the actual retained bytes and requires either an existing actual-download ledger proof or the helper's provenance sidecar from a prior completed GET. A matching copy of the upload JAR without remote provenance is not accepted as a download. An interrupted ledger update can resume from a verified GET sidecar. Corrupted cache bytes fail explicitly.

Both the downloader and Root's UI ledger writers use an exclusive `fs.open(..., 'wx')`/`O_EXCL` lock at `.tools/platform-downloads-030/<platform>/ledger.lock`. Acquire it for each initial ledger read and each short read/modify/write; never hold it during UI interaction or a network request. Contention waits at most approximately five seconds; never delete another writer's lock automatically. After a GET, the downloader re-reads the latest ledger, checks the same identity/remote ID/metadata/URL/current manifest, and updates only that matching row. New submissions and unrelated publisher fields survive. Its atomic replacement also checks that the locked ledger bytes have not changed. All writers must honor this protocol; an unsynchronized writer cannot be made safe by atomic replacement alone.

`scripts/test-download-platform-030.py` uses only temporary fixtures and mocked HTTP responses, including concurrent publisher additions, changed targets, bad hashes, unsafe redirects, atomic-write failure and verified-cache recovery. Running the tests makes no actual CDN requests.
