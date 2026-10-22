"""Offline regression tests: no authenticated commands or network are invoked."""
import contextlib
import copy
import importlib.util
import io
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
