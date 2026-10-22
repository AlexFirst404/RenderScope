"""Offline regression tests: no authenticated commands or network are invoked."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-github-030.py"))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def fixture():
    assets = []
    raw = {}
    for index in range(67):
        name = f"renderscope-test-{index}.jar"
        raw[name] = f"actual jar {index}".encode()
        assets.append({"name": name, "path": "ignored/" + name, "bytes": len(raw[name]),
                       "sha256": p.fingerprint(raw[name]), "kind": "primary JAR"})
    raw["SHA256SUMS"] = b"test flat checksums\n"
    assets.append({"name": "SHA256SUMS", "bytes": len(raw["SHA256SUMS"]),
                   "sha256": p.fingerprint(raw["SHA256SUMS"]), "content": raw["SHA256SUMS"]})
    return {"ready": True, "errors": [], "assets": assets, "notes": "Final notes\n\n## Русский\nГотово\n".encode(),
            "primaryJarCount": 67, "worldVerifiedTargets": 67, "tag": p.TAG, "tagCommit": "a" * 40,
            "repository": p.REPO, "manifestSha256": "b" * 64, "sourceNotesSha256": "c" * 64,
            "scopes": ["installed-production", "fabric-development-client"]}, raw


def remote(asset, index=1):
    return {"name": asset["name"], "id": index, "size": asset["bytes"], "state": "uploaded",
            "digest": "sha256:" + asset["sha256"], "browser_download_url": "https://example.invalid/" + asset["name"]}


class FakeGitHub:
    def __init__(self, plan, existing=None, draft=True, exists=True):
        self.plan = plan
        self.commands = []
        self.files = copy.deepcopy(existing or [])
        self.exists = exists
        self.record = {"id": 7, "tag_name": p.TAG, "name": "RenderScope 0.3.0", "draft": draft,
                       "prerelease": False, "body": plan["notes"].decode(), "html_url": "https://example.invalid/release"}

    def release(self):
        return copy.deepcopy(self.record) if self.exists else None

    def assets(self, release):
        return copy.deepcopy(self.files)

    def download(self, asset):
        raise AssertionError("All fake assets have API digests; no download expected")

    def call(self, *args):
        self.commands.append(args)
        if args[:2] == ("release", "create"):
            assert "--draft" in args and "--verify-tag" in args
            self.exists = True
        elif args[:2] == ("release", "upload"):
            name = Path(args[3]).name
            entry = next(a for a in self.plan["assets"] if a["name"] == name)
            self.files.append(remote(entry, len(self.files) + 1))
        elif args[:2] == ("release", "edit"):
            assert "--draft=false" in args and "--prerelease=false" in args
            assert len(self.files) == 68, "Do not publish a partial release"
            self.record["draft"] = False
            self.record["body"] = Path(args[args.index("--notes-file") + 1]).read_text(encoding="utf-8")
        else:
            raise AssertionError("Unexpected mutation: " + str(args))
        return b""


class PublisherTest(unittest.TestCase):
    def github_client(self):
        with patch.object(p.shutil, "which", return_value="gh"):
            return p.GitHub()

    def api_result(self, value=None, status=0, stderr=b""):
        return p.subprocess.CompletedProcess([], status, b"" if value is None else json.dumps(value).encode(), stderr)

    def test_by_tag_404_discovers_the_existing_authenticated_draft(self):
        draft = {"id": 421227848, "tag_name": p.TAG, "draft": True}
        with patch.object(p.subprocess, "run", side_effect=[self.api_result(status=1, stderr=b"gh: Not Found (HTTP 404)"),
                self.api_result([draft])]) as cli:
            self.assertEqual(draft, self.github_client().release())
        self.assertEqual(2, cli.call_count)
        self.assertEqual(f"repos/{p.REPO}/releases?per_page=100&page=1", cli.call_args_list[1].args[0][-1])
        self.assertEqual(("gh", "api", "--hostname", "github.com"), cli.call_args_list[1].args[0][:4])

    def test_draft_discovery_exhausts_pages_even_after_finding_the_tag(self):
        draft = {"id": 421227848, "tag_name": p.TAG, "draft": True}
        first = [{"id": index, "tag_name": f"older-{index}"} for index in range(100)]
        second = [draft] + [{"id": index + 100, "tag_name": f"older-next-{index}"} for index in range(99)]
        with patch.object(p.subprocess, "run", side_effect=[self.api_result(status=1, stderr=b"HTTP 404"),
                self.api_result(first), self.api_result(second), self.api_result([])]) as cli:
            self.assertEqual(draft, self.github_client().release())
        self.assertEqual(4, cli.call_count)
        self.assertTrue(cli.call_args_list[-1].args[0][-1].endswith("page=3"))

    def test_draft_absence_requires_all_pages_and_an_exact_case_sensitive_tag(self):
        first = [{"id": index, "tag_name": f"older-{index}"} for index in range(100)]
        similar = [{"id": 101, "tag_name": p.TAG + "-old"}, {"id": 102, "tag_name": "V0.3.0"}]
        with patch.object(p.subprocess, "run", side_effect=[self.api_result(status=1, stderr=b"HTTP 404"),
                self.api_result(first), self.api_result(similar)]) as cli:
            self.assertIsNone(self.github_client().release())
        self.assertEqual(3, cli.call_count)

    def test_duplicate_exact_tags_on_different_pages_are_rejected(self):
        draft = {"id": 421227848, "tag_name": p.TAG, "draft": True}
        first = [draft] + [{"id": index, "tag_name": f"older-{index}"} for index in range(99)]
        with patch.object(p.subprocess, "run", side_effect=[self.api_result(status=1, stderr=b"HTTP 404"),
                self.api_result(first), self.api_result([dict(draft, id=421227849)])]) as cli:
            with self.assertRaisesRegex(RuntimeError, "Multiple GitHub releases"):
                self.github_client().release()
        self.assertEqual(3, cli.call_count)
        self.assertTrue(all(call.args[0][1] == "api" for call in cli.call_args_list))

    def test_non_404_lookup_errors_never_fall_back_or_become_absence(self):
        for status in (403, 500):
            with self.subTest(status=status), patch.object(p.subprocess, "run",
                    return_value=self.api_result(status=1, stderr=f"HTTP {status}".encode())) as cli:
                with self.assertRaisesRegex(RuntimeError, f"HTTP {status}"):
                    self.github_client().release()
                self.assertEqual(1, cli.call_count)

    def test_authenticated_listing_failure_does_not_authorize_a_new_draft(self):
        with patch.object(p.subprocess, "run", side_effect=[self.api_result(status=1, stderr=b"HTTP 404"),
                self.api_result(status=1, stderr=b"HTTP 403")]):
            with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                self.github_client().release()

    def test_malformed_release_listing_is_not_treated_as_absence(self):
        for invalid in ({"message": "not a release list"}, [None]):
            with self.subTest(invalid=invalid), patch.object(p.subprocess, "run", side_effect=[
                    self.api_result(status=1, stderr=b"HTTP 404"), self.api_result(invalid)]):
                with self.assertRaisesRegex(RuntimeError, "release array"):
                    self.github_client().release()

    def test_discovered_complete_draft_is_resumed_without_creating_or_overwriting(self):
        plan, _ = fixture()
        github = self.github_client()
        release = {"id": 421227848, "tag_name": p.TAG, "draft": True, "prerelease": False,
                   "name": "RenderScope 0.3.0", "body": plan["notes"].decode(), "html_url": "https://example.invalid/release"}
        commands = []
        def call(*args):
            commands.append(args)
            if args[0] == "api":
                return json.dumps([release]).encode()
            self.assertEqual(("release", "edit"), args[:2])
            release["draft"] = False
            return b""
        with patch.object(p.subprocess, "run", return_value=self.api_result(status=1, stderr=b"HTTP 404")), \
                patch.object(github, "call", side_effect=call), \
                patch.object(github, "assets", return_value=[remote(asset, index) for index, asset in enumerate(plan["assets"])]):
            record = p.publish(plan, github)
        self.assertEqual(421227848, record["releaseId"])
        self.assertTrue(record["published"])
        self.assertEqual(1, sum(command[:2] == ("release", "edit") for command in commands))
        self.assertFalse(any(command[:2] in (("release", "create"), ("release", "upload")) for command in commands))
        self.assertTrue(all("--clobber" not in command for command in commands))

    def test_numerical_ready_flag_cannot_replace_a_current_manual_visual_review(self):
        review = {"passed": True, "manifestSha256": "a" * 64, "screenshots": [{"sha256": "b" * 64}]}
        row = {"visuallyVerified": True, "manualVisualReview": review}
        p.verify_manual_review(row, copy.deepcopy(review))
        for changed in ({"passed": False}, dict(review, manifestSha256="c" * 64),
                        dict(review, screenshots=[{"sha256": "d" * 64}])):
            with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, "Current manual visual review"):
                p.verify_manual_review(row, changed)

    def test_saved_review_requires_explicit_visual_approval(self):
        review = {"passed": True}
        with self.assertRaisesRegex(RuntimeError, "Current manual visual review"):
            p.verify_manual_review({"manualVisualReview": review}, review)

    def test_same_size_wrong_api_digest_is_rejected_without_fallback(self):
        plan, _ = fixture()
        asset = remote(plan["assets"][0])
        asset["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
            p.verify_remote_assets(plan["assets"], [asset], lambda _: self.fail("Digest mismatch must not fall back"))

    def test_missing_digest_requires_actual_downloaded_bytes(self):
        plan, raw = fixture()
        asset = remote(plan["assets"][0])
        asset["digest"] = None
        missing, verified = p.verify_remote_assets(plan["assets"], [asset], lambda a: raw[a["name"]])
        self.assertEqual(67, len(missing))
        self.assertEqual("downloaded bytes SHA-256", verified[0]["verifiedBy"])
        with self.assertRaisesRegex(RuntimeError, "Downloaded remote bytes mismatch"):
            p.verify_remote_assets(plan["assets"], [asset], lambda _: b"wrong bytes")

    def test_duplicate_extra_and_incomplete_upload_assets_are_rejected(self):
        plan, _ = fixture()
        valid = remote(plan["assets"][0])
        for invalid in ([valid, valid], [dict(valid, name="unexpected.jar")], [dict(valid, state="starter")], [dict(valid, size=0)]):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                p.verify_remote_assets(plan["assets"], invalid, lambda _: self.fail())

    def test_draft_resume_uploads_only_missing_asset_then_publishes(self):
        plan, _ = fixture()
        github = FakeGitHub(plan, [remote(a, i + 1) for i, a in enumerate(plan["assets"][1:])])
        with contextlib.redirect_stdout(io.StringIO()):
            record = p.publish(plan, github)
        uploads = [c for c in github.commands if c[:2] == ("release", "upload")]
        self.assertEqual(1, len(uploads))
        self.assertEqual(plan["assets"][0]["name"], Path(uploads[0][3]).name)
        self.assertTrue(all("--clobber" not in c and "delete" not in c for c in github.commands))
        self.assertTrue(record["published"])
        self.assertEqual(68, record["assetCount"])

    def test_first_release_is_draft_until_all_68_assets_are_verified(self):
        plan, _ = fixture()
        github = FakeGitHub(plan, exists=False)
        with contextlib.redirect_stdout(io.StringIO()):
            record = p.publish(plan, github)
        self.assertEqual(("release", "create"), github.commands[0][:2])
        self.assertEqual(68, sum(c[:2] == ("release", "upload") for c in github.commands))
        self.assertEqual(("release", "edit"), github.commands[-1][:2])
        self.assertTrue(record["success"])

    def test_complete_public_release_is_verified_without_mutation(self):
        plan, _ = fixture()
        github = FakeGitHub(plan, [remote(a, i + 1) for i, a in enumerate(plan["assets"])], draft=False)
        record = p.publish(plan, github)
        self.assertEqual([], github.commands)
        self.assertEqual(67, record["primaryJarCount"])

    def test_incomplete_public_release_is_not_modified(self):
        plan, _ = fixture()
        github = FakeGitHub(plan, [remote(plan["assets"][0])], draft=False)
        with self.assertRaisesRegex(RuntimeError, "Existing public release differs"):
            p.publish(plan, github)
        self.assertEqual([], github.commands)

    def test_default_dry_run_with_blocked_gate_never_constructs_remote_client(self):
        plan, _ = fixture()
        plan["ready"] = False
        plan["errors"] = ["World proofs are incomplete"]
        with patch.object(p, "local_plan", return_value=plan), patch.object(p, "GitHub") as github, \
                patch.object(p, "publish") as publish, patch("sys.argv", ["publish-github-030.py"]), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2, p.main())
        github.assert_not_called()
        publish.assert_not_called()

    def test_tag_notes_relative_links_become_exact_tag_urls(self):
        notes = p.release_notes(b"[Dependencies](DEPENDENCIES.md)\r\n[Evidence](release-manifest.json)\r\n")
        self.assertNotIn(b"\r", notes)
        self.assertNotIn(b"](DEPENDENCIES.md)", notes)
        self.assertIn(b"/blob/v0.3.0/distribution/platforms/0.3.0/release-manifest.json)", notes)


if __name__ == "__main__":
    unittest.main()
