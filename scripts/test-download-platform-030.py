"""Offline inbound-download tests: temporary ledgers and mocked CDN responses only."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


d = load("platform_downloader", "download-platform-030.py")
fixtures = load("platform_download_fixture_support", "test-verify-platform-030.py")


class Response(io.BytesIO):
    def __init__(self, data, url, length=True):
        super().__init__(data)
        self.status = 200
        self.headers = {"Content-Length": str(len(data))} if length else {}
        self.url = url

    def geturl(self):
        return self.url


class PlatformDownloadTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PlatformVerificationTest()
        self.fixture.setUp()
        self.root = self.fixture.root
        self.manifest = self.fixture.manifest
        self.base = self.root / d.BASE
        self.base.mkdir(parents=True)
        (self.base / "release-manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.ledger = self.fixture.complete("modrinth")
        for row in self.ledger["files"]:
            row["downloadUrl"] = "https://cdn.modrinth.com/observed-fixture/" + row["file"]
        self.ledger["files"] = self.ledger["files"][:1]
        self.path = self.base / "modrinth-release-verification.json"
        self.save(self.ledger)
        self.target = self.manifest["targets"][0]
        self.raw = (self.root / self.target["packagedArtifact"]).read_bytes()
        self.opener = Mock(side_effect=lambda platform, url, timeout: Response(self.raw, url))

    def tearDown(self):
        self.fixture.tearDown()

    def save(self, document):
        self.path.write_text(json.dumps(document), encoding="utf-8")

    def read(self):
        return json.loads(self.path.read_text(encoding="utf-8"))

    def run_download(self, opener=None):
        return d.download_platform("modrinth", self.root, self.opener if opener is None else opener)

    def cache(self):
        return self.root / ".tools/platform-downloads-030/modrinth" / self.target["fileName"]

    def test_partial_actual_download_hashes_bytes_then_records_and_rehashes_resume(self):
        result = self.run_download()
        self.assertTrue(result["success"])
        self.assertEqual(1, result["downloaded"])
        saved = self.read()["files"][0]
        self.assertEqual(d.hashlib.sha256(self.raw).hexdigest(), saved["downloadedSha256"])
        self.assertEqual(len(self.raw), saved["downloadedBytes"])
        self.assertEqual(self.raw, self.cache().read_bytes())
        no_network = Mock(side_effect=AssertionError("verified cache must not request remote bytes"))
        result = self.run_download(no_network)
        self.assertTrue(result["success"])
        self.assertEqual(1, result["cacheRehashed"])
        no_network.assert_not_called()
        self.assertEqual(saved["downloadVerifiedAt"], self.read()["files"][0]["downloadVerifiedAt"])

    def test_unattested_matching_local_jar_is_not_misrepresented_as_remote_download(self):
        self.cache().parent.mkdir(parents=True)
        self.cache().write_bytes(self.raw)
        result = self.run_download()
        self.assertTrue(result["success"])
        self.opener.assert_called_once()
        self.assertEqual(1, result["downloaded"])
        self.assertEqual("actual-https-cdn-get", self.read()["files"][0]["downloadVerificationMethod"])

    def test_missing_url_unsaved_metadata_or_unsubmitted_intent_stays_pending_without_network(self):
        original = copy.deepcopy(self.ledger)
        for field, value in (("downloadUrl", None), ("metadataVerified", False), ("submissionState", "submitting")):
            with self.subTest(field=field):
                ledger = copy.deepcopy(original)
                ledger["files"][0][field] = value
                self.save(ledger)
                result = self.run_download()
                self.assertTrue(result["success"])
                self.assertEqual(1, len(result["pending"]))
        self.opener.assert_not_called()

    def test_wrong_download_hash_or_too_many_bytes_never_updates_ledger(self):
        previous = self.path.read_bytes()
        for data in (b"x" * len(self.raw), self.raw + b"extra"):
            with self.subTest(size=len(data)):
                opener = lambda platform, url, timeout: Response(data, url, length=False)
                self.assertFalse(self.run_download(opener)["success"])
                self.assertEqual(previous, self.path.read_bytes())
                self.assertFalse(self.cache().exists())
                self.assertFalse(list(self.cache().parent.glob("*.part")))

    def test_http_error_is_explicit_and_does_not_write_download_fingerprints(self):
        result = self.run_download(Mock(side_effect=OSError("CDN HTTP 404: not available yet")))
        self.assertFalse(result["success"])
        self.assertIn("404", result["errors"][0])
        self.assertNotIn("downloadedSha256", self.read()["files"][0])

    def test_only_exact_https_platform_cdn_hosts_and_final_response_are_accepted(self):
        for url in ("http://cdn.modrinth.com/file", "https://cdn.modrinth.com.evil.invalid/file", "https://edge.forgecdn.net/file"):
            with self.subTest(url=url):
                ledger = copy.deepcopy(self.ledger)
                ledger["files"][0]["downloadUrl"] = url
                self.save(ledger)
                self.assertFalse(self.run_download()["success"])
        self.opener.assert_not_called()
        self.save(self.ledger)
        self.assertFalse(self.run_download(lambda platform, url, timeout: Response(self.raw, "https://untrusted.invalid/file"))["success"])

    def test_redirect_handler_blocks_untrusted_redirect_before_following_it(self):
        handler = d.CdnRedirects("modrinth")
        request = d.urllib.request.Request(self.ledger["files"][0]["downloadUrl"])
        with self.assertRaises(ValueError):
            handler.redirect_request(request, None, 302, "Found", {}, "https://untrusted.invalid/file")
        redirected = handler.redirect_request(request, None, 302, "Found", {}, "https://cdn.modrinth.com/observed-redirect/file")
        self.assertEqual("https://cdn.modrinth.com/observed-redirect/file", redirected.full_url)

    def test_root_addition_and_status_update_during_download_are_preserved(self):
        added = self.fixture.complete("modrinth")["files"][1]
        def network(platform, url, timeout):
            with d.ledger_lock(self.root, platform):
                latest = self.read()
                latest["files"].append(added)
                latest["files"][0]["observedStatus"] = "Changed by real publisher while GET runs"
                latest["publisherNote"] = "preserve this unrelated field"
                self.save(latest)
            return Response(self.raw, url)
        result = self.run_download(network)
        self.assertTrue(result["success"])
        latest = self.read()
        self.assertEqual(2, len(latest["files"]))
        self.assertEqual(added, latest["files"][1])
        self.assertEqual("preserve this unrelated field", latest["publisherNote"])
        self.assertIn("real publisher", latest["files"][0]["observedStatus"])
        self.assertIn("downloadedSha256", latest["files"][0])

    def test_changed_remote_url_or_current_manifest_during_get_aborts_proof_update(self):
        def changed_url(platform, url, timeout):
            with d.ledger_lock(self.root, platform):
                latest = self.read()
                latest["files"][0]["downloadUrl"] = "https://cdn.modrinth.com/new-observed-url"
                self.save(latest)
            return Response(self.raw, url)
        self.assertFalse(self.run_download(changed_url)["success"])
        self.assertNotIn("downloadedSha256", self.read()["files"][0])
        self.save(self.ledger)
        def changed_manifest(platform, url, timeout):
            (self.base / "release-manifest.json").write_text(json.dumps({**self.manifest, "changed": True}))
            return Response(self.raw, url)
        # Existing cache came from a real mocked GET but only for the old observed URL;
        # invalidate its provenance to force this second simulated network event.
        self.cache().with_name(self.cache().name + ".source.json").unlink()
        self.assertFalse(self.run_download(changed_manifest)["success"])
        self.assertNotIn("downloadedSha256", self.read()["files"][0])

    def test_crash_after_verified_get_can_resume_from_its_actual_provenance(self):
        with patch.object(d, "record_proof", side_effect=RuntimeError("simulated process failure before ledger write")):
            self.assertFalse(self.run_download()["success"])
        self.assertNotIn("downloadedSha256", self.read()["files"][0])
        self.assertTrue(self.cache().is_file())
        no_network = Mock(side_effect=AssertionError("actual prior GET provenance should resume"))
        result = self.run_download(no_network)
        self.assertTrue(result["success"])
        self.assertEqual(1, result["cacheRehashed"])
        no_network.assert_not_called()

    def test_corrupt_verified_cache_fails_instead_of_hiding_mismatch_with_new_get(self):
        self.assertTrue(self.run_download()["success"])
        previous = self.path.read_bytes()
        self.cache().write_bytes(b"corrupt cached remote JAR")
        no_network = Mock(side_effect=AssertionError("do not hide cache mismatch"))
        result = self.run_download(no_network)
        self.assertFalse(result["success"])
        self.assertEqual(previous, self.path.read_bytes())
        no_network.assert_not_called()

    def test_atomic_ledger_replace_failure_preserves_original_and_removes_temporary_file(self):
        previous = self.path.read_bytes()
        original_replace = d.os.replace
        def replace(source, destination):
            if Path(destination) == self.path:
                raise OSError("simulated atomic replace failure")
            return original_replace(source, destination)
        with patch.object(d.os, "replace", side_effect=replace):
            self.assertFalse(self.run_download()["success"])
        self.assertEqual(previous, self.path.read_bytes())
        self.assertFalse(list(self.base.glob("*.tmp")))

    def test_writer_lock_is_bounded_and_never_deleted_by_a_contending_reader(self):
        path = d.lock_path(self.root, "modrinth")
        path.parent.mkdir(parents=True)
        path.write_bytes(b"other writer owns this lock")
        with self.assertRaises(RuntimeError):
            with d.ledger_lock(self.root, "modrinth", attempts=2, delay=0):
                self.fail("must not acquire someone else's lock")
        self.assertEqual(b"other writer owns this lock", path.read_bytes())

    def test_curseforge_actual_recorded_forgecdn_url_uses_separate_cache(self):
        ledger = self.fixture.complete("curseforge")
        ledger["files"] = ledger["files"][:1]
        ledger["files"][0]["downloadUrl"] = "https://mediafilez.forgecdn.net/observed-fixture/file.jar"
        path = self.base / "curseforge-release-verification.json"
        path.write_text(json.dumps(ledger), encoding="utf-8")
        result = d.download_platform("curseforge", self.root, self.opener)
        self.assertTrue(result["success"])
        self.assertEqual(1, result["downloaded"])
        saved = json.loads(path.read_text())["files"][0]
        self.assertIn("platform-downloads-030/curseforge/", saved["downloadedArtifact"])
        self.assertEqual(self.raw, (self.root / saved["downloadedArtifact"]).read_bytes())

    def test_get_request_uses_no_authentication_cookies_or_discovered_url(self):
        url = self.ledger["files"][0]["downloadUrl"]
        mock_http = Mock()
        with patch.object(d.urllib.request, "build_opener", return_value=mock_http) as factory:
            d.open_download("modrinth", url, 45)
        request = mock_http.open.call_args.args[0]
        self.assertEqual("GET", request.get_method())
        self.assertEqual(url, request.full_url)
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Cookie"))
        self.assertIsInstance(factory.call_args.args[0], d.CdnRedirects)


if __name__ == "__main__":
    unittest.main()
