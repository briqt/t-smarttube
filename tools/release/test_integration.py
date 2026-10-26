"""Disposable local Git histories test the real enhancement exporter and integrator."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import integration
import pipeline
import upstream


class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tsmarttube-git-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.repo = self.directory / "control"
        self.repo.mkdir()
        self.git("init", "-b", "master")
        self.git("config", "user.name", "Release fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.write("smarttubetv/build.gradle", 'versionCode 2458\nversionName "32.68"\n')
        self.write("common/Bridge.java", "class Bridge {\n    int value = 1;\n}\n")
        self.write(".gitignore", ".doc/\n")
        self.commit("upstream fixture")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return integration.git(self.repo, *args).decode()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-m", message)

    def lock(self, commit):
        source = {"release": {"repository": upstream.REPOSITORY, "releaseId": 1, "channel": "beta",
                              "tag": "32.68", "versionName": "32.68"}, "commit": commit,
                  "tree": self.git("rev-parse", commit + "^{tree}").strip(), "rootGitlinks": {}}
        return {"scope": "upstream-root-source-only", "source": source,
                "sourceFingerprint": upstream.fingerprint(source)}

    def enhancement(self):
        self.write("common/Caption.java", "class Caption {}\n")
        self.commit("one shared enhancement")
        return integration.export_delta(self.repo, self.base, self.directory / "delta")

    def test_one_delta_applies_to_two_different_upstream_snapshots(self):
        manifest = self.enhancement()
        self.git("checkout", "--detach", self.base)
        self.write("common/UpstreamFix.java", "class UpstreamFix {}\n")
        self.commit("later upstream fix")
        later = self.git("rev-parse", "HEAD").strip()
        for name, commit in (("beta", self.base), ("stable", later)):
            receipt = integration.prepare(self.repo, self.lock(commit), self.directory / "delta",
                                          self.directory / name / "src", initialize_submodules=False)
            self.assertFalse(receipt["submodulesVerified"])
            self.assertEqual((self.directory / name / "src/common/Caption.java").read_text(), "class Caption {}\n")
            self.assertEqual((self.directory / name / "src/common/UpstreamFix.java").exists(), name == "stable")
        self.assertEqual(manifest["patchSha256"], integration.sha256(self.directory / "delta/enhancement.patch"))

    def test_dirty_source_requires_explicit_nonpublishable_snapshot(self):
        self.write("common/Caption.java", "class Caption {}\n")
        with self.assertRaisesRegex(upstream.InvalidRelease, "uncommitted-enhancement"):
            integration.export_delta(self.repo, self.base, self.directory / "refused")
        before = hashlib.sha256((self.repo / ".git/index").read_bytes()).hexdigest()
        manifest = integration.export_delta(self.repo, self.base, self.directory / "snapshot", include_worktree=True)
        self.assertTrue(manifest["workingTreeSnapshot"])
        self.assertEqual(hashlib.sha256((self.repo / ".git/index").read_bytes()).hexdigest(), before)
        self.assertEqual(self.git("diff", "--cached", "--name-only"), "")

    def test_private_ignored_material_and_release_controller_are_not_app_delta(self):
        self.write("common/Caption.java", "class Caption {}\n")
        self.write(".doc/private.txt", "synthetic private marker\n")
        self.write("tools/release/controller.py", "# fixture control code\n")
        integration.export_delta(self.repo, self.base, self.directory / "snapshot", include_worktree=True)
        patch = (self.directory / "snapshot/enhancement.patch").read_text()
        self.assertIn("common/Caption.java", patch)
        self.assertNotIn("synthetic private marker", patch)
        self.assertNotIn("tools/release/controller.py", patch)

    def test_unsupported_build_input_cannot_silently_disappear_from_delta(self):
        self.write("common/Caption.java", "class Caption {}\n")
        self.write("settings.gradle", "// new module requiring explicit inclusion\n")
        with self.assertRaisesRegex(upstream.InvalidRelease, "unreviewed-enhancement-path"):
            integration.export_delta(self.repo, self.base, self.directory / "snapshot", include_worktree=True)

    def test_symlink_cannot_smuggle_outside_material_into_app_delta(self):
        (self.repo / "common/Caption.java").symlink_to("../.doc/private.txt")
        with self.assertRaisesRegex(upstream.InvalidRelease, "nonregular-enhancement-file"):
            integration.export_delta(self.repo, self.base, self.directory / "snapshot", include_worktree=True)

    def test_conflicting_hook_stops_without_changing_original_worktree(self):
        self.write("common/Bridge.java", "class Bridge {\n    int value = 2;\n}\n")
        self.commit("enhancement hook")
        integration.export_delta(self.repo, self.base, self.directory / "delta")
        self.git("checkout", "--detach", self.base)
        self.write("common/Bridge.java", "class Bridge {\n    int value = 3;\n}\n")
        self.commit("upstream changed same hook")
        before = self.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(upstream.InvalidRelease, "command-failed"):
            integration.prepare(self.repo, self.lock(before.strip()), self.directory / "delta",
                                self.directory / "candidate/src", initialize_submodules=False)
        self.assertEqual(self.git("rev-parse", "HEAD"), before)
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_patch_cannot_overwrite_upstream_version(self):
        self.write("smarttubetv/build.gradle", 'versionCode 9999\nversionName "99.99"\n')
        self.commit("bad enhancement version edit")
        integration.export_delta(self.repo, self.base, self.directory / "delta")
        with self.assertRaisesRegex(upstream.InvalidRelease, "enhancement-overwrote-upstream-version"):
            integration.prepare(self.repo, self.lock(self.base), self.directory / "delta",
                                self.directory / "candidate/src", initialize_submodules=False)

    def test_tampered_patch_is_rejected_before_candidate_checkout(self):
        self.enhancement()
        with (self.directory / "delta/enhancement.patch").open("ab") as file:
            file.write(b"\nchanged\n")
        with self.assertRaisesRegex(upstream.InvalidRelease, "patch-digest-mismatch"):
            integration.prepare(self.repo, self.lock(self.base), self.directory / "delta", self.directory / "candidate")
        self.assertFalse((self.directory / "candidate").exists())

    def test_module_urls_and_paths_are_data_not_shell_or_local_transports(self):
        for path, url in (("../SharedModules", "https://github.com/yuliskov/SharedModules"),
                          ("SharedModules", "file:///tmp/untrusted"),
                          ("SharedModules", "https://example.invalid/SharedModules"),
                          ("SharedModules", "ext::arbitrary program")):
            text = '[submodule "fixture"]\npath = ' + path + '\nurl = ' + url + '\n'
            with self.subTest(path=path, url=url), self.assertRaises(upstream.InvalidRelease):
                integration.modules(text, integration.UPSTREAM_URL)
        good = '[submodule "SharedModules"]\npath = SharedModules\nurl = ../SharedModules.git\n'
        self.assertEqual(integration.modules(good, integration.UPSTREAM_URL)["SharedModules"],
                         "https://github.com/yuliskov/SharedModules.git")

    def diverged_release_fixture(self):
        self.git("checkout", "-b", "release", self.base)
        self.write("common/ReleaseOnly.java", "class ReleaseOnly {}\n")
        self.commit("upstream release branch diverged from master")
        release = self.git("rev-parse", "HEAD").strip()
        self.write("common/Caption.java", "class Caption {}\n")
        config = {"schemaVersion": 1, "enhancementRevision": 0, "buildRecipeVersion": 1, "upstreamBase": release}
        self.write("tools/release/config.json", json.dumps(config))
        self.commit("enhancement based on release branch")
        beta, stable = self.lock(self.base), self.lock(release)
        stable["source"]["release"].update(channel="stable", tag="32.68s")
        stable["sourceFingerprint"] = upstream.fingerprint(stable["source"])
        return release, {"channels": {"beta": beta, "stable": stable}}

    def test_master_merge_base_cannot_override_the_actual_enhancement_baseline(self):
        release, observation = self.diverged_release_fixture()
        master_base = self.git("merge-base", "HEAD", "master").strip()
        self.assertNotEqual(master_base, release)
        with self.assertRaisesRegex(upstream.InvalidRelease, "enhancement-base-mismatch"):
            pipeline.plan_releases(self.repo, observation, master_base, self.directory / "plans")

    def test_one_pinned_baseline_excludes_release_branch_changes_from_enhancement(self):
        release, observation = self.diverged_release_fixture()
        pipeline.plan_releases(self.repo, observation, release, self.directory / "plans")
        patch = (self.directory / "plans/delta/enhancement.patch").read_text()
        self.assertIn("common/Caption.java", patch)
        self.assertNotIn("common/ReleaseOnly.java", patch)

    def test_shallow_local_cache_is_cloned_not_used_as_invalid_reference(self):
        cache_source = self.directory / "cache-source"
        integration.git(self.directory, "clone", "--", str(self.repo), str(cache_source))
        sha = integration.git(cache_source, "rev-parse", "HEAD").decode().strip()
        cache_root = self.directory / "cache"
        cache_root.mkdir()
        integration.git(self.directory, "clone", "--no-local", "--depth=1", "--", str(cache_source), str(cache_root / "SharedModules"))
        integration.git(cache_root / "SharedModules", "remote", "set-url", "origin", "https://github.com/yuliskov/SharedModules")
        self.write(".gitmodules", '[submodule "SharedModules"]\npath = SharedModules\nurl = https://github.com/yuliskov/SharedModules\n')
        self.git("add", ".gitmodules")
        self.git("update-index", "--add", "--cacheinfo", "160000," + sha + ",SharedModules")
        self.git("commit", "-m", "locked fixture submodule")
        receipt = integration.lock_submodules(self.repo, reference_root=cache_root)
        self.assertEqual(receipt["SharedModules"]["commit"], sha)
        self.assertEqual(integration.git(self.repo / "SharedModules", "rev-parse", "HEAD").decode().strip(), sha)


if __name__ == "__main__":
    unittest.main()
