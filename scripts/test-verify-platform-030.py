"""Offline platform-ledger fixtures in temporary folders; never actual records."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("platform_verifier", Path(__file__).with_name("verify-platform-030.py"))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class PlatformVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="renderscope-platform-offline-")
        self.root = Path(self.temporary.name).resolve()
        versions = [f"1.20.{i}" for i in range(1, 7)] + ["1.21"] + [f"1.21.{i}" for i in range(1, 12)] + ["26.1", "26.1.1", "26.1.2", "26.2", "26.3"]
        rows = []
        for minecraft in versions:
            for loader in ("fabric", "forge", "neoforge"):
                if loader == "forge" and minecraft in ("1.20.5", "1.21.2"):
                    continue
                name = f"renderscope-{loader}-0.3.0+mc{minecraft}.jar"
                data = ("offline JAR fixture, never a release: " + loader + minecraft).encode()
                artifact = "build/" + name
                packaged = "distribution/" + name
                for relative in (artifact, packaged):
                    path = self.root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                rows.append({"loader": loader, "minecraft": minecraft, "artifact": artifact, "packagedArtifact": packaged,
                    "fileName": name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                    "clientSide": "required", "serverSide": "unsupported", "releaseChannel": "release",
                    "javaRuntime": 25 if minecraft.startswith("26.") else 21,
                    "versionNumber": f"0.3.0+mc{minecraft}-{loader}",
                    "modrinth": {"projectId": "LyIIxCef"}, "curseforge": {"projectId": 1756581},
                    "fabricApi": {"modrinthProjectId": "P7dR8mSH", "modrinthVersionId": "fixture-api-" + minecraft,
                                  "curseforgeProjectId": 306612} if loader == "fabric" else None})
        self.manifest = {"renderscopeVersion": "0.3.0", "publicationReady": True, "releaseChannel": "release", "artifactCount": 67, "targets": rows}
        self.manifest_sha = hashlib.sha256(json.dumps(self.manifest).encode()).hexdigest()
        self.ledger = self.complete("modrinth")

    def tearDown(self):
        self.temporary.cleanup()

    def complete(self, platform, downloads=False):
        files = []
        for i, target in enumerate(self.manifest["targets"]):
            dependency = []
            if target["loader"] == "fabric":
                dependency = [{"projectId": "P7dR8mSH", "versionId": target["fabricApi"]["modrinthVersionId"]}] if platform == "modrinth" else [{"projectId": 306612}]
            observed = {"gameVersions": [target["minecraft"]], "loaders": [target["loader"]], "clientOnly": True,
                        "releaseChannel": "release", "fileName": target["fileName"], "requiredDependencies": dependency}
            if platform == "modrinth":
                observed["versionNumber"] = target["versionNumber"]
            else:
                observed["javaRuntime"] = target["javaRuntime"]
            remote_id = "fixture" + str(i) if platform == "modrinth" else str(100000 + i)
            record = {"identity": target["loader"] + ":" + target["minecraft"], "loader": target["loader"], "mc": target["minecraft"],
                "sha256": target["sha256"], "bytes": target["bytes"], "file": target["fileName"], "packagedArtifact": target["packagedArtifact"],
                "expectedGameVersions": [target["minecraft"]], "expectedLoaders": [target["loader"]], "javaRuntime": target["javaRuntime"],
                "clientOnly": True, "expectedFabricApi": copy.deepcopy(target["fabricApi"]), "expectedReleaseChannel": "release",
                "submissionStartedAt": "2026-10-22T20:00:00Z", "submissionState": "submitted", "remoteId": remote_id,
                "remoteUrl": f"https://{platform}.com/offline-fixture/{remote_id}", "metadataVerified": True,
                "metadataVerifiedAt": "2026-10-22T20:01:00Z", "metadataSource": "post-save-ui", "observedMetadata": observed,
                "observedStatus": "Listed" if platform == "modrinth" else "Processing"}
            if downloads:
                record.update({"downloadUrl": "https://cdn.example.invalid/offline/" + target["fileName"], "downloadedSha256": target["sha256"],
                               "downloadedBytes": target["bytes"], "downloadVerifiedAt": "2026-10-22T20:02:00Z"})
            files.append(record)
        return {"schemaVersion": 1, "renderscopeVersion": "0.3.0", "platform": platform,
                "projectId": self.manifest["targets"][0][platform]["projectId"], "manifestSha256": self.manifest_sha, "files": files}

    def verify(self, platform="modrinth", ledger=None, require_downloads=False):
        return v.verify_platform(platform, self.manifest, self.manifest_sha, self.ledger if ledger is None else ledger,
                                 self.root, require_downloads)

    def test_all_submitted_and_saved_metadata_can_pass_with_downloads_explicitly_pending(self):
        result = self.verify()
        self.assertTrue(result["success"])
        self.assertEqual(67, result["submittedCount"])
        self.assertEqual(67, result["metadataVerifiedCount"])
        self.assertEqual(0, result["downloadVerifiedCount"])
        self.assertFalse(self.verify(require_downloads=True)["success"])

    def test_exact_download_hashes_pass_and_present_cache_is_rehashed(self):
        ledger = self.complete("modrinth", downloads=True)
        target = self.manifest["targets"][0]
        cache = self.root / ".tools/platform-downloads-030/modrinth/fixture.jar"
        cache.parent.mkdir(parents=True)
        cache.write_bytes((self.root / target["packagedArtifact"]).read_bytes())
        ledger["files"][0]["downloadedArtifact"] = cache.relative_to(self.root).as_posix()
        result = self.verify(ledger=ledger, require_downloads=True)
        self.assertTrue(result["success"])
        self.assertEqual(67, result["downloadVerifiedCount"])
        self.assertEqual(1, result["downloadCacheRehashedCount"])
        cache.unlink()
        result = self.verify(ledger=ledger, require_downloads=True)
        self.assertTrue(result["success"])
        self.assertEqual(0, result["downloadCacheRehashedCount"])
        self.assertIn("historical", result["files"][0]["download"]["cacheNote"])

    def test_processing_is_raw_status_and_never_inferred_as_moderation_approval(self):
        result = self.verify("curseforge", self.complete("curseforge"))
        self.assertTrue(result["success"])
        self.assertEqual({"Processing": 67}, result["observedStatuses"])
        self.assertFalse(result["moderationApprovalInferred"])
        self.assertNotIn("approved", result)

    def test_missing_target_or_unfinished_intent_is_not_a_complete_submission(self):
        self.ledger["files"].pop()
        result = self.verify()
        self.assertFalse(result["success"])
        self.assertEqual(66, result["submittedCount"])
        self.assertEqual(1, len(result["missingIdentities"]))
        self.ledger = self.complete("modrinth")
        self.ledger["files"][0]["submissionState"] = "submitting"
        self.assertEqual(66, self.verify()["submittedCount"])

    def test_duplicate_identity_and_remote_id_are_rejected(self):
        self.ledger["files"].append(copy.deepcopy(self.ledger["files"][0]))
        self.assertFalse(self.verify()["success"])
        self.ledger = self.complete("modrinth")
        self.ledger["files"][1]["remoteId"] = self.ledger["files"][0]["remoteId"]
        self.assertFalse(self.verify()["success"])

    def test_old_manifest_release_platform_or_project_ledger_is_rejected(self):
        for field, value in (("manifestSha256", "0" * 64), ("platform", "curseforge"), ("renderscopeVersion", "0.2.0"), ("projectId", "different")):
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                ledger[field] = value
                self.assertFalse(self.verify(ledger=ledger)["success"])
        self.manifest["publicationReady"] = False
        self.assertFalse(self.verify()["success"])

    def test_changed_main_or_packaged_jar_bytes_are_rejected(self):
        for field in ("artifact", "packagedArtifact"):
            with self.subTest(field=field):
                path = self.root / self.manifest["targets"][0][field]
                saved = path.read_bytes()
                path.write_bytes(b"x" * len(saved))
                self.assertFalse(self.verify()["success"])
                path.write_bytes(saved)

    def test_changed_expected_identity_hash_size_file_and_scope_are_rejected(self):
        changes = {"identity": "fabric:unknown", "sha256": "0" * 64, "bytes": 1, "file": "wrong.jar", "packagedArtifact": "wrong.jar",
                   "expectedGameVersions": ["1.20.1", "1.20.2"], "expectedLoaders": ["fabric", "forge"], "clientOnly": False,
                   "javaRuntime": 17, "expectedReleaseChannel": "alpha", "expectedFabricApi": None}
        for field, value in changes.items():
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                ledger["files"][0][field] = value
                self.assertFalse(self.verify(ledger=ledger)["success"])

    def test_only_post_save_readback_can_verify_metadata(self):
        for field, value in (("metadataSource", "pre-save-form"), ("metadataVerifiedAt", None), ("metadataVerified", False)):
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                ledger["files"][0][field] = value
                result = self.verify(ledger=ledger)
                self.assertFalse(result["success"])
                self.assertEqual(66, result["metadataVerifiedCount"])

    def test_saved_single_minecraft_loader_client_release_filename_and_version_are_exact(self):
        changes = {"gameVersions": ["1.20.1", "1.20.2"], "loaders": ["forge"], "clientOnly": False,
                   "releaseChannel": "alpha", "fileName": "other.jar", "versionNumber": "0.2.0"}
        for field, value in changes.items():
            with self.subTest(field=field):
                ledger = copy.deepcopy(self.ledger)
                ledger["files"][0]["observedMetadata"][field] = value
                self.assertFalse(self.verify(ledger=ledger)["success"])

    def test_modrinth_requires_exact_fabric_api_project_version_and_required_relation(self):
        for dependency in ({"projectId": "wrong", "versionId": "wrong"}, {"projectId": "P7dR8mSH", "versionId": "wrong"},
                           {"projectId": "P7dR8mSH", "versionId": "fixture-api-1.20.1", "dependencyType": "optional"}):
            with self.subTest(dependency=dependency):
                ledger = copy.deepcopy(self.ledger)
                ledger["files"][0]["observedMetadata"]["requiredDependencies"] = [dependency]
                self.assertFalse(self.verify(ledger=ledger)["success"])

    def test_curseforge_requires_project_relation_and_java_tag_but_cannot_pin_version(self):
        good = self.complete("curseforge")
        for dependency in ({"projectId": 1}, {"projectId": 306612, "versionId": "pretend-pin"},
                           {"projectId": 306612, "version": "pretend-pin"}, {"projectId": 306612, "dependencyType": "optional"}):
            with self.subTest(dependency=dependency):
                ledger = copy.deepcopy(good)
                ledger["files"][0]["observedMetadata"]["requiredDependencies"] = [dependency]
                self.assertFalse(self.verify("curseforge", ledger)["success"])
        good["files"][0]["observedMetadata"]["javaRuntime"] = 17
        self.assertFalse(self.verify("curseforge", good)["success"])

    def test_non_fabric_cannot_gain_an_unexpected_required_project(self):
        self.ledger["files"][1]["observedMetadata"]["requiredDependencies"] = [{"projectId": "P7dR8mSH"}]
        self.assertFalse(self.verify()["success"])

    def test_wrong_download_hash_size_or_retained_bytes_fail_even_when_downloads_optional(self):
        for field, value in (("downloadedSha256", "0" * 64), ("downloadedBytes", 1), ("downloadVerifiedAt", "not-a-time")):
            with self.subTest(field=field):
                ledger = self.complete("modrinth", downloads=True)
                ledger["files"][0][field] = value
                self.assertFalse(self.verify(ledger=ledger)["success"])
        ledger = self.complete("modrinth", downloads=True)
        cache = self.root / "downloaded.jar"
        cache.write_bytes(b"wrong download")
        ledger["files"][0]["downloadedArtifact"] = "downloaded.jar"
        self.assertFalse(self.verify(ledger=ledger)["success"])

    def test_download_path_escape_or_wrong_remote_platform_is_rejected(self):
        ledger = self.complete("modrinth", downloads=True)
        ledger["files"][0]["downloadedArtifact"] = "../outside.jar"
        self.assertFalse(self.verify(ledger=ledger)["success"])
        ledger = self.complete("modrinth")
        ledger["files"][0]["remoteUrl"] = "https://curseforge.com/wrong-platform"
        self.assertFalse(self.verify(ledger=ledger)["success"])

    def test_malformed_or_duplicated_manifest_target_cannot_satisfy_exact_67(self):
        self.ledger["files"].append(None)
        self.assertFalse(self.verify()["success"])
        self.manifest["targets"][-1] = copy.deepcopy(self.manifest["targets"][0])
        self.assertFalse(self.verify()["success"])


if __name__ == "__main__":
    unittest.main()
