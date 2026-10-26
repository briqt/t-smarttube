"""Faults at the actual GitHub adapter boundary; no HTTP requests or credentials."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import integration
import publish
import upstream
from test_publish import fixture


class GitHubBoundaryTest(unittest.TestCase):
    def existing_release(self, plan, report):
        names = [item["name"] for item in report["assets"].values()] + ["source-lock.json"]
        return {"id": 1, "tag_name": plan["releaseTag"], "draft": False,
                "prerelease": plan["channel"] == "beta", "assets": [{"name": name} for name in names]}

    def assert_bad_tag_stops_before_assets(self, target):
        plan, report = fixture()
        backend = publish.GitHub()
        release = self.existing_release(plan, report)
        def api(path, method="GET", data=None, optional=False, repository=integration.REPOSITORY):
            self.assertEqual(method, "GET")
            if path == "releases/tags/" + plan["releaseTag"]:
                return copy.deepcopy(release)
            if path == "git/ref/tags/" + plan["releaseTag"]:
                return None if target is None else {"object": {"type": "commit", "sha": target}}
            self.fail("Unexpected API access in tag preflight")
        with patch.object(backend, "api", side_effect=api), patch.object(integration, "run", side_effect=AssertionError(
                "Asset I/O started before the conflicting release tag was rejected")) as asset_io:
            with self.assertRaisesRegex(upstream.InvalidRelease, "release-tag-target-mismatch"):
                backend.ensure_release(plan, report, repair=False)
        asset_io.assert_not_called()

    def test_existing_release_tag_cannot_point_at_another_commit(self):
        self.assert_bad_tag_stops_before_assets("f" * 40)

    def test_published_release_cannot_silently_have_no_source_tag(self):
        self.assert_bad_tag_stops_before_assets(None)

    def fake_publication(self, corrupt=False):
        plan, report = fixture()
        payloads, events = {}, []
        state = {"release": None}
        with tempfile.TemporaryDirectory(prefix="tsmarttube-github-fixture-") as directory:
            root = Path(directory)
            for index, item in enumerate(report["assets"].values()):
                content = bytes([index + 1]) * item["size"]
                (root / item["name"]).write_bytes(content)
                item["sha256"] = hashlib.sha256(content).hexdigest()
            backend = publish.GitHub(root)
            def api(path, method="GET", data=None, optional=False, repository=integration.REPOSITORY):
                self.assertEqual(repository, integration.REPOSITORY)
                if path.startswith("releases/tags/"):
                    return copy.deepcopy(state["release"])
                if path.startswith("git/ref/tags/"):
                    if state["release"] is None or state["release"]["draft"]:
                        return None
                    return {"object": {"type": "commit", "sha": plan["enhancement"]["commit"]}}
                if path == "releases" and method == "POST":
                    self.assertIs(data["draft"], True)
                    self.assertEqual(data["target_commitish"], plan["enhancement"]["commit"])
                    state["release"] = {"id": 1, "tag_name": plan["releaseTag"], "draft": True,
                                        "prerelease": False, "assets": []}
                    return copy.deepcopy(state["release"])
                if path == "releases/1" and method == "PATCH":
                    self.assertEqual(data, {"draft": False})
                    state["release"]["draft"] = False
                    events.append("publish")
                    return copy.deepcopy(state["release"])
                self.fail("Unexpected GitHub publication endpoint")
            def cli(command):
                self.assertEqual(command[command.index("--repo") + 1], "github.com/" + integration.REPOSITORY)
                if command[2] == "upload":
                    source = Path(command[4])
                    payloads[source.name] = source.read_bytes()
                    events.append("upload:" + source.name)
                elif command[2] == "download":
                    name = command[command.index("--pattern") + 1]
                    content = payloads[name]
                    if corrupt and "_x86.apk" in name:
                        content = b"wrong" + content[5:]
                    Path(command[command.index("--output") + 1]).write_bytes(content)
                    events.append("download:" + name)
                else:
                    self.fail("Unexpected gh subprocess")
                return b""
            def public(url, cap):
                self.assertFalse(state["release"]["draft"])
                self.assertTrue(url.startswith("https://github.com/" + integration.REPOSITORY + "/releases/download/"))
                content = payloads[url.rsplit("/", 1)[1]]
                self.assertLessEqual(len(content), cap)
                events.append("public")
                return content
            with patch.object(backend, "api", side_effect=api), patch.object(integration, "run", side_effect=cli), \
                    patch.object(backend, "public_bytes", side_effect=public):
                if corrupt:
                    with self.assertRaisesRegex(upstream.InvalidRelease, "release-asset-readback-mismatch"):
                        backend.ensure_release(plan, report, repair=True)
                    self.assertTrue(state["release"]["draft"])
                    self.assertNotIn("publish", events)
                else:
                    backend.ensure_release(plan, report, repair=True)
                    self.assertEqual(sum(event.startswith("upload:") for event in events), 5)
                    self.assertEqual(events.count("public"), 5)
                    self.assertGreater(events.index("publish"), max(i for i, event in enumerate(events) if event.startswith("upload:")))

    def test_real_adapter_creates_draft_checks_bytes_then_publishes(self):
        self.fake_publication()

    def test_real_adapter_does_not_publish_corrupt_uploaded_bytes(self):
        self.fake_publication(corrupt=True)

    def test_enhancement_commit_cannot_be_a_moving_branch(self):
        plan, _ = fixture()
        plan["enhancement"]["commit"] = "master"
        with self.assertRaisesRegex(upstream.InvalidRelease, "invalid-plan-enhancement-commit"):
            integration.validate_plan(plan, publish=True)


if __name__ == "__main__":
    unittest.main()
