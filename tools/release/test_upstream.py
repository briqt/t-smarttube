"""Offline fixtures for source discovery, not a simulated successful release."""
import copy
import unittest
from unittest.mock import patch

import upstream


def release(tag, ident=1, prerelease=None):
    return {"id": ident, "tag_name": tag, "draft": False,
            "prerelease": not tag.endswith("s") if prerelease is None else prerelease,
            "target_commitish": "master", "assets": []}


def releases():
    return [release("32.68", 1), release("32.68s", 2)]


def source_fixture():
    candidate = upstream.discover([releases()])["stable"]
    ref = {"ref": "refs/tags/32.68s", "object": {"type": "commit", "sha": "a" * 40}}
    commit = {"sha": "a" * 40, "tree": {"sha": "b" * 40}}
    tree = {"sha": "b" * 40, "truncated": False, "tree": [
        {"path": "SharedModules", "mode": "160000", "type": "commit", "sha": "c" * 40},
        {"path": "MediaServiceCore", "mode": "160000", "type": "commit", "sha": "d" * 40}]}
    return [candidate, ref, [], commit, tree]


class DiscoveryTest(unittest.TestCase):
    def assertRejected(self, code, pages, page_size=100):
        with self.assertRaisesRegex(upstream.InvalidRelease, "^" + code + "$"):
            upstream.discover(pages, page_size)

    def test_channels_are_distinct(self):
        selected = upstream.discover([releases()])
        self.assertEqual(selected["stable"]["tag"], "32.68s")
        self.assertEqual(selected["beta"]["tag"], "32.68")
        self.assertEqual(selected["stable"]["versionName"], "32.68")

    def test_numeric_version_not_lexical_order(self):
        data = [release("32.9", 1), release("32.10", 2), release("32.10s", 3)]
        self.assertEqual(upstream.discover([data])["beta"]["tag"], "32.10")

    def test_order_and_dates_do_not_pick_older_source(self):
        data = releases() + [release("32.56s", 3), release("32.56", 4)]
        expected = upstream.discover([data])
        for item in data:
            item.update(name="Newest stable 999", published_at="2099-01-01T00:00:00Z")
        self.assertEqual(expected, upstream.discover([list(reversed(data))]))

    def test_aggregates_and_drafts_are_not_candidates(self):
        data = releases() + [release("latest", 3), release("testing", 4), release("99.99s", 5)]
        data[-1]["draft"] = True
        self.assertEqual(upstream.discover([data]), upstream.discover([releases()]))

    def test_pages_are_all_consumed(self):
        data = [release("latest", 3), release("32.68", 1), release("32.68s", 2)]
        self.assertEqual(upstream.discover([data[:2], data[2:]], 2)["stable"]["tag"], "32.68s")

    def test_full_final_page_is_not_a_complete_snapshot(self):
        self.assertRejected("incomplete-pagination", [releases()], 2)

    def test_explicit_empty_terminal_page_is_allowed(self):
        self.assertEqual(set(upstream.discover([releases(), []], 2)), {"stable", "beta"})

    def test_page_after_short_page_is_rejected(self):
        self.assertRejected("page-after-terminal-page", [[releases()[0]], [releases()[1]]], 2)

    def test_api_error_is_not_no_change(self):
        self.assertRejected("invalid-page", [{"message": "rate limit exceeded"}])

    def test_absent_channel_is_not_no_change(self):
        self.assertRejected("missing-channel", [[release("32.68", 1)]])

    def test_channel_disagreement_blocks_instead_of_falling_back(self):
        self.assertRejected("channel-tag-mismatch", [releases() + [release("32.69s", 3, True)]])

    def test_historical_channel_mismatch_does_not_block_new_consistent_releases(self):
        data = releases() + [release("30.95s", 3, True)]
        self.assertEqual(upstream.discover([data]), upstream.discover([releases()]))

    def test_historical_non_prerelease_beta_does_not_hide_current_beta(self):
        data = releases() + [release("16.52", 3, False)]
        self.assertEqual(upstream.discover([data])["beta"]["tag"], "32.68")

    def test_duplicate_id_blocks_inconsistent_snapshot(self):
        self.assertRejected("duplicate-release-id", [releases() + [release("32.69", 1)]])

    def test_duplicate_tag_blocks_inconsistent_snapshot(self):
        self.assertRejected("duplicate-release-tag", [releases() + [release("32.68", 3)]])

    def test_boolean_is_not_a_release_id(self):
        data = releases()
        data[0]["id"] = True
        self.assertRejected("invalid-release-id", [data])

    def test_source_build_does_not_require_upstream_apks(self):
        data = releases() + [release("32.69s", 3)]
        self.assertEqual(upstream.discover([data])["stable"]["tag"], "32.69s")

    def test_notes_and_asset_replacement_do_not_change_source_candidate(self):
        data = releases()
        expected = upstream.discover([data])
        data[0].update(body="Changed notes", assets=[{"name": "Replaced.apk", "digest": None}])
        self.assertEqual(expected, upstream.discover([data]))

    def test_malformed_or_oversized_page_rejected(self):
        self.assertRejected("invalid-page", [releases()], 1)
        self.assertRejected("missing-pages", [])

    def test_draft_flag_is_not_truthy_coerced(self):
        data = releases()
        data[0]["draft"] = "false"
        self.assertRejected("invalid-draft-state", [data])


class SourcePinTest(unittest.TestCase):
    def assertRejected(self, code, data):
        with self.assertRaisesRegex(upstream.InvalidRelease, "^" + code + "$"):
            upstream.pin_source(*data)

    def test_lightweight_tag_binds_commit_and_exact_tree(self):
        data = source_fixture()
        lock = upstream.pin_source(*data)
        self.assertEqual(lock["source"]["commit"], "a" * 40)
        self.assertEqual(lock["source"]["tree"], "b" * 40)
        self.assertEqual(lock["source"]["rootGitlinks"]["MediaServiceCore"], "d" * 40)
        self.assertFalse(lock["releaseReady"])
        self.assertEqual(lock["scope"], "upstream-root-source-only")

    def test_annotated_tag_is_peeled(self):
        data = source_fixture()
        data[1]["object"] = {"type": "tag", "sha": "e" * 40}
        data[2].append({"sha": "e" * 40, "object": {"type": "commit", "sha": "a" * 40}})
        self.assertEqual(upstream.pin_source(*data)["source"]["commit"], "a" * 40)

    def test_unresolved_annotated_tag_cannot_be_a_commit(self):
        data = source_fixture()
        data[1]["object"]["type"] = "tag"
        self.assertRejected("unresolved-tag", data)

    def test_tag_object_must_match_its_requested_sha(self):
        data = source_fixture()
        data[1]["object"] = {"type": "tag", "sha": "e" * 40}
        data[2].append({"sha": "f" * 40, "object": {"type": "commit", "sha": "a" * 40}})
        self.assertRejected("tag-object-mismatch", data)

    def test_cyclic_tag_chain_rejected(self):
        data = source_fixture()
        obj = {"type": "tag", "sha": "e" * 40}
        data[1]["object"] = obj
        data[2].extend([{"sha": "e" * 40, "object": obj}] * 2)
        self.assertRejected("cyclic-tag-chain", data)

    def test_wrong_tag_rejected(self):
        data = source_fixture()
        data[1]["ref"] = "refs/tags/32.68"
        self.assertRejected("wrong-tag-ref", data)

    def test_target_master_does_not_substitute_for_resolved_commit(self):
        data = source_fixture()
        data[3]["sha"] = "f" * 40
        self.assertRejected("commit-mismatch", data)

    def test_commit_sha_is_not_used_as_tree_sha(self):
        data = source_fixture()
        data[4]["sha"] = data[3]["sha"]
        self.assertRejected("tree-mismatch", data)

    def test_truncated_tree_not_accepted_as_complete(self):
        data = source_fixture()
        data[4]["truncated"] = True
        self.assertRejected("incomplete-tree", data)

    def test_missing_gitlink_rejected(self):
        data = source_fixture()
        data[4]["tree"].pop()
        self.assertRejected("missing-required-gitlink", data)

    def test_invalid_gitlink_identity_rejected(self):
        data = source_fixture()
        data[4]["tree"][0]["sha"] = "master"
        self.assertRejected("invalid-gitlink-sha", data)

    def test_duplicate_path_cannot_replace_gitlink(self):
        data = source_fixture()
        data[4]["tree"].append(copy.deepcopy(data[4]["tree"][0]))
        self.assertRejected("duplicate-or-invalid-tree-path", data)

    def test_gitlink_path_cannot_escape_checkout(self):
        data = source_fixture()
        data[4]["tree"][0]["path"] = "../SharedModules"
        self.assertRejected("invalid-root-gitlink-path", data)

    def test_actual_commit_and_gitlinks_affect_source_fingerprint(self):
        first = source_fixture()
        second = copy.deepcopy(first)
        second[4]["tree"][0]["sha"] = "f" * 40
        self.assertNotEqual(upstream.pin_source(*first)["sourceFingerprint"],
                            upstream.pin_source(*second)["sourceFingerprint"])

    def test_tree_order_does_not_affect_source_fingerprint(self):
        first = source_fixture()
        second = copy.deepcopy(first)
        second[4]["tree"].reverse()
        self.assertEqual(upstream.pin_source(*first), upstream.pin_source(*second))

    def test_candidate_cannot_smuggle_unchecked_fields(self):
        data = source_fixture()
        data[0]["versionName"] = "99.99"
        self.assertRejected("invalid-candidate-fields", data)
        data = source_fixture()
        data[0]["approved"] = True
        self.assertRejected("invalid-candidate-fields", data)

    def test_wrong_repository_is_not_accepted(self):
        data = source_fixture()
        data[0]["repository"] = "example/other"
        self.assertRejected("wrong-repository", data)

    def test_lock_is_not_aliased_to_caller_mutable_candidate(self):
        data = source_fixture()
        result = upstream.pin_source(*data)
        data[0]["tag"] = "99.99s"
        self.assertEqual(result["source"]["release"]["tag"], "32.68s")

    def test_deliberately_bypassing_commit_check_is_caught(self):
        data = source_fixture()
        data[3]["sha"] = "f" * 40
        original = upstream.require
        with patch.object(upstream, "require", side_effect=lambda cond, code:
                          None if code == "commit-mismatch" else original(cond, code)):
            result = upstream.pin_source(*data)
            self.assertNotEqual(result["source"]["commit"], data[3]["sha"])
        # With the production check restored, that same corruption is rejected.
        self.assertRejected("commit-mismatch", data)


if __name__ == "__main__":
    unittest.main()
