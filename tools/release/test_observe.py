"""Fake API transcripts drive the actual read-only observer, without network access."""
import copy
import subprocess
import unittest
from unittest.mock import patch

import observe
from test_upstream import releases


class FakeGitHub:
    def __init__(self):
        links = [{"path": "SharedModules", "mode": "160000", "type": "commit", "sha": "c" * 40},
                 {"path": "MediaServiceCore", "mode": "160000", "type": "commit", "sha": "d" * 40}]
        self.data = {"releases?per_page=100&page=1": releases(),
                     "git/ref/tags/32.68s": {"ref": "refs/tags/32.68s",
                                            "object": {"type": "commit", "sha": "a" * 40}},
                     "git/ref/tags/32.68": {"ref": "refs/tags/32.68",
                                           "object": {"type": "commit", "sha": "e" * 40}},
                     "git/commits/" + "a" * 40: {"sha": "a" * 40, "tree": {"sha": "b" * 40}},
                     "git/commits/" + "e" * 40: {"sha": "e" * 40, "tree": {"sha": "f" * 40}},
                     "git/trees/" + "b" * 40: {"sha": "b" * 40, "truncated": False, "tree": links},
                     "git/trees/" + "f" * 40: {"sha": "f" * 40, "truncated": False, "tree": links}}
        self.calls = []

    def __call__(self, path):
        self.calls.append(path)
        result = self.data[path]
        if callable(result):
            return result()
        return copy.deepcopy(result)


class ObserverTest(unittest.TestCase):
    def assertRejected(self, code, fake, **kwargs):
        with self.assertRaisesRegex(observe.upstream.InvalidRelease, "^" + code + "$"):
            observe.observe(fake, **kwargs)

    def test_actual_controller_pins_both_channels(self):
        fake = FakeGitHub()
        result = observe.observe(fake)
        self.assertEqual(result["channels"]["stable"]["source"]["commit"], "a" * 40)
        self.assertEqual(result["channels"]["beta"]["source"]["commit"], "e" * 40)
        self.assertFalse(result["releaseReady"])
        self.assertTrue(all(path.startswith(("releases?", "git/")) for path in fake.calls))
        self.assertEqual(fake.calls.count("git/ref/tags/32.68s"), 2)

    def test_missing_next_page_cannot_be_silently_ignored(self):
        fake = FakeGitHub()
        full = [dict(id=i + 100, tag_name="latest", draft=False, prerelease=False) for i in range(98)]
        fake.data["releases?per_page=100&page=1"] += full
        fake.data["releases?per_page=100&page=2"] = {"message": "rate limit"}
        self.assertRejected("invalid-release-page", fake)
        self.assertNotIn("git/ref/tags/32.68s", fake.calls)

    def test_second_page_is_fetched_and_used(self):
        fake = FakeGitHub()
        full = [dict(id=i + 100, tag_name="latest", draft=False, prerelease=False) for i in range(100)]
        fake.data["releases?per_page=100&page=1"] = full
        fake.data["releases?per_page=100&page=2"] = releases()
        result = observe.observe(fake)
        self.assertEqual(len(result["pages"]), 2)
        self.assertEqual(result["channels"]["stable"]["source"]["release"]["tag"], "32.68s")

    def test_pagination_limit_is_an_error_not_empty_success(self):
        fake = FakeGitHub()
        fake.data["releases?per_page=100&page=1"] *= 50
        self.assertRejected("pagination-limit-reached", fake, page_limit=1)

    def test_arbitrary_api_url_fields_are_never_followed(self):
        fake = FakeGitHub()
        fake.data["git/ref/tags/32.68s"]["object"]["url"] = "https://example.invalid/credential-sink"
        result = observe.observe(fake)
        self.assertFalse(result["releaseReady"])
        self.assertTrue(all("http" not in path for path in fake.calls))

    def test_notes_urls_and_asset_bodies_are_not_saved(self):
        fake = FakeGitHub()
        fake.data["releases?per_page=100&page=1"][0].update(
            body="arbitrary release text", assets=[{"url": "https://example.invalid/signed?secret=value"}])
        result = observe.observe(fake)
        self.assertEqual(set(result["pages"][0][0]), {"id", "tag_name", "draft", "prerelease"})
        self.assertNotIn("assets", result["channels"]["beta"]["source"]["release"])

    def test_tag_moving_during_read_is_not_accepted(self):
        fake = FakeGitHub()
        original = copy.deepcopy(fake.data["git/ref/tags/32.68s"])
        def moving():
            value = copy.deepcopy(original)
            if fake.calls.count("git/ref/tags/32.68s") > 1:
                value["object"]["sha"] = "9" * 40
            return value
        fake.data["git/ref/tags/32.68s"] = moving
        self.assertRejected("tag-moved-during-observation", fake)

    def test_wrong_tree_is_not_used_even_if_gitlinks_look_valid(self):
        fake = FakeGitHub()
        fake.data["git/trees/" + "b" * 40]["sha"] = "a" * 40
        self.assertRejected("tree-mismatch", fake)

    def test_annotated_tags_use_sha_not_url(self):
        fake = FakeGitHub()
        fake.data["git/ref/tags/32.68s"]["object"] = {"type": "tag", "sha": "8" * 40}
        fake.data["git/tags/" + "8" * 40] = {"sha": "8" * 40,
                                                   "object": {"type": "commit", "sha": "a" * 40}}
        result = observe.observe(fake)
        self.assertEqual(result["channels"]["stable"]["source"]["commit"], "a" * 40)
        self.assertIn("git/tags/" + "8" * 40, fake.calls)

    def test_annotated_tag_sha_is_checked_before_following_object(self):
        fake = FakeGitHub()
        fake.data["git/ref/tags/32.68s"]["object"] = {"type": "tag", "sha": "8" * 40}
        fake.data["git/tags/" + "8" * 40] = {"sha": "7" * 40,
                                                   "object": {"type": "commit", "sha": "a" * 40}}
        self.assertRejected("tag-object-mismatch", fake)

    def test_cyclic_tag_is_bounded(self):
        fake = FakeGitHub()
        obj = {"type": "tag", "sha": "8" * 40}
        fake.data["git/ref/tags/32.68s"]["object"] = obj
        fake.data["git/tags/" + "8" * 40] = {"sha": "8" * 40, "object": obj}
        self.assertRejected("invalid-tag-chain", fake)

    def test_api_failure_is_safe_and_never_no_change(self):
        with patch.object(observe.subprocess, "run", side_effect=subprocess.CalledProcessError(
                1, ["gh"], stderr=b"untrusted response body")):
            with self.assertRaisesRegex(observe.upstream.InvalidRelease, "^github-read-failed$"):
                observe.github_get("releases?per_page=100&page=1")

    def test_gh_uses_only_get_and_fixed_host_without_shell(self):
        with patch.object(observe.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"[]")) as run:
            self.assertEqual(observe.github_get("releases?per_page=100&page=1"), [])
        args, kwargs = run.call_args
        self.assertEqual(args[0][:7], ["gh", "api", "--hostname", "github.com", "--method", "GET",
                                      "repos/yuliskov/SmartTube/releases?per_page=100&page=1"])
        self.assertNotIn("shell", kwargs)


if __name__ == "__main__":
    unittest.main()
