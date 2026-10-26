"""Offline black-box contract tests; deliberately do not import publisher internals."""
import copy
import unittest

import integration
import publish
import upstream
from test_upstream import source_fixture

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "universal")


def fixture(revision=1, channel="stable", timestamp=1700000000000, snapshot=False):
    lock = upstream.pin_source(*source_fixture())
    source = lock["source"]
    source["release"].update(channel=channel, tag="32.68s" if channel == "stable" else "32.68")
    lock["sourceFingerprint"] = upstream.fingerprint(source)
    enhancement = {"base": "a" * 40, "commit": "b" * 40, "patchSha256": "c" * 64,
                   "workingTreeSnapshot": snapshot}
    config = {"schemaVersion": 1, "enhancementRevision": revision, "buildRecipeVersion": 1}
    plan = integration.make_plan(lock, enhancement, config,
                                 '    versionCode 2458\n    versionName "32.68"\n', timestamp)
    report = dict(schemaVersion=1, verified=True, certificateSha256="d" * 64,
                  **{key: plan[key] for key in ("candidateId", "applicationId", "versionCode", "versionName")})
    report["assets"] = {abi: {"name": f't-SmartTube_{channel}_{plan["versionName"]}_{abi}.apk',
                             "size": 1000001, "sha256": str(index + 1) * 64}
                        for index, abi in enumerate(ABIS)}
    return plan, report


class InjectedFailure(publish.InvalidRelease):
    pass


class Backend:
    """Storage/transport seam only: no release selection or publish algorithm."""
    def __init__(self):
        self.head, self.serial = None, 0
        self.channels, self.assets, self.events = {}, {}, []
        self.fail = self.before_commit = self.before_public = None
        self.always_conflict = False
        self.conflicts = 0

    def event(self, stage, channel):
        self.events.append((stage, channel))
        if self.fail == stage:
            raise InjectedFailure("injected-" + stage)

    def load_state(self, plan):
        self.event("load", plan["channel"])
        feed, receipt = self.channels.get(plan["channel"], (None, None))
        return copy.deepcopy(dict(head=self.head, feed=feed, receipt=receipt))

    def verify_upstream(self, plan):
        self.event("upstream", plan["channel"])

    def ensure_release(self, plan, report, repair):
        self.event("ensure", plan["channel"])
        key = plan["releaseTag"]
        if key in self.assets and self.assets[key] != report["assets"]:
            raise InjectedFailure("artifact-mismatch")
        self.assets.setdefault(key, copy.deepcopy(report["assets"]))

    def commit_state(self, expected_head, channel, feed, receipt):
        self.event("commit", channel)
        if self.before_commit:
            hook, self.before_commit = self.before_commit, None
            hook()
        if self.always_conflict or expected_head != self.head:
            self.conflicts += 1
            if self.conflicts > 12:
                raise AssertionError("CAS retry must stop within 12 conflicts")
            raise publish.Conflict("injected-cas-conflict")
        self.serial += 1
        self.head = f"{self.serial:040x}"
        self.channels[channel] = copy.deepcopy((feed, receipt))
        return self.head

    def verify_public_feed(self, channel, expected):
        self.event("public", channel)
        if self.before_public:
            hook, self.before_public = self.before_public, None
            hook()
        if self.channels[channel][0] != expected:
            raise InjectedFailure("public-feed-mismatch")


class PublishTest(unittest.TestCase):
    def seeded(self):
        backend = Backend()
        self.assertEqual(publish.publish(*fixture(), backend)["status"], "PUBLISHED")
        backend.events.clear()
        return backend

    def names(self, backend):
        return [stage for stage, _ in backend.events]

    def untouched(self, backend, before, assets):
        self.assertEqual((backend.head, backend.channels), before)
        for key, value in assets.items():
            self.assertEqual(backend.assets[key], value)

    def reject_unchanged(self, backend, pair):
        before, assets = copy.deepcopy((backend.head, backend.channels)), copy.deepcopy(backend.assets)
        with self.assertRaises(publish.InvalidRelease):
            publish.publish(*pair, backend)
        self.assertNotIn("commit", self.names(backend))
        self.untouched(backend, before, assets)
    def test_first_publish_then_identical_retry_is_read_only(self):
        backend, (plan, report) = Backend(), fixture()
        self.assertEqual(publish.publish(plan, report, backend)["status"], "PUBLISHED")
        names = self.names(backend)
        self.assertEqual(names.count("commit"), 1)
        for earlier, later in (("load", "commit"), ("upstream", "ensure"),
                               ("ensure", "commit"), ("commit", "public")):
            self.assertLess(names.index(earlier), names.index(later))
        self.assertEqual(publish.feed_version(backend.channels["stable"][0]), plan["versionCode"])
        before = copy.deepcopy((backend.head, backend.channels, backend.assets))
        backend.events.clear()
        self.assertEqual(publish.publish(plan, report, backend)["status"], "ALREADY_APPLIED")
        self.assertEqual((backend.head, backend.channels, backend.assets), before)
        self.assertNotIn("commit", self.names(backend))
        self.assertIn("ensure", self.names(backend))
        self.assertIn("public", self.names(backend))
    def test_each_pre_promotion_failure_preserves_old_feed_and_assets(self):
        order = ["load", "upstream", "ensure", "commit"]
        for stage in order:
            with self.subTest(stage=stage):
                backend = self.seeded()
                before, assets = copy.deepcopy((backend.head, backend.channels)), copy.deepcopy(backend.assets)
                backend.fail = stage
                with self.assertRaisesRegex(InjectedFailure, "^injected-" + stage + "$"):
                    publish.publish(*fixture(2), backend)
                names = self.names(backend)
                self.assertEqual(names[-1], stage)
                self.assertNotIn("public", names)
                if stage != "commit":
                    self.assertNotIn("commit", names)
                if stage in ("load", "upstream"):
                    self.assertNotIn("ensure", names)
                self.untouched(backend, before, assets)
    def test_idempotent_lookup_cannot_hide_verification_errors(self):
        for stage in ("load", "upstream", "ensure", "public"):
            with self.subTest(stage=stage):
                backend = self.seeded()
                before, assets = copy.deepcopy((backend.head, backend.channels)), copy.deepcopy(backend.assets)
                backend.fail = stage
                with self.assertRaisesRegex(InjectedFailure, "^injected-" + stage + "$"):
                    publish.publish(*fixture(), backend)
                self.assertEqual(self.names(backend)[-1], stage)
                self.assertNotIn("commit", self.names(backend))
                self.untouched(backend, before, assets)
    def test_invalid_reports_are_rejected_before_release_mutation(self):
        mutations = [lambda r: r.update(applicationId="wrong.package"),
                     lambda r: r.pop("certificateSha256"), lambda r: r.update(certificateSha256="z" * 64),
                     lambda r: r["assets"].pop("x86"), lambda r: r.update(verified=False),
                     lambda r: r.update(candidateId="0" * 64), lambda r: r.update(versionCode=1),
                     lambda r: r.update(versionName="wrong"),
                     lambda r: r["assets"]["x86"].update(
                         name=r["assets"]["x86"]["name"].replace("_stable_", "_beta_")),
                     lambda r: r["assets"]["x86"].update(size=1000000),
                     lambda r: r["assets"]["x86"].update(sha256="z" * 64)]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                backend, (plan, report) = Backend(), fixture()
                mutate(report)
                self.reject_unchanged(backend, (plan, report))
                self.assertTrue(set(self.names(backend)) <= {"load", "upstream"})
                self.assertEqual(backend.assets, {})
    def test_wrong_plan_channel_is_rejected_before_mutation(self):
        backend, (plan, report) = Backend(), fixture()
        plan["channel"] = "beta"
        self.reject_unchanged(backend, (plan, report))
        self.assertNotIn("ensure", self.names(backend))
        self.assertEqual(backend.assets, {})
    def test_local_worktree_snapshot_is_not_a_formal_release(self):
        backend = Backend()
        self.reject_unchanged(backend, fixture(snapshot=True))
        self.assertNotIn("ensure", self.names(backend))
        self.assertEqual(backend.assets, {})
    def test_same_version_different_candidate_is_not_idempotent(self):
        backend = self.seeded()
        self.reject_unchanged(backend, fixture(timestamp=1700000000001))
        self.assertNotIn("ensure", self.names(backend))
    def test_receipt_binds_plan_not_just_candidate_id(self):
        backend, (plan, report) = self.seeded(), fixture()
        plan["versionCode"] += 1000
        report["versionCode"] = plan["versionCode"]
        self.reject_unchanged(backend, (plan, report))
    def test_changed_artifact_cannot_reuse_success_receipt(self):
        backend, (plan, report) = self.seeded(), fixture()
        report["assets"]["x86"]["sha256"] = "f" * 64
        self.reject_unchanged(backend, (plan, report))
    def test_feed_preserves_legacy_shape_and_correct_channel_urls(self):
        for channel in ("stable", "beta"):
            plan, report = fixture(channel=channel)
            feed = publish.build_feed(plan, report)
            self.assertEqual(publish.feed_version(feed), plan["versionCode"])
            for abi in ABIS:
                key = "downloadUrlList" + ("_" + abi if abi != "universal" else "")
                expected = ("https://github.com/briqt/t-smarttube/releases/download/"
                            + plan["releaseTag"] + "/" + report["assets"][abi]["name"])
                self.assertEqual(feed["package"][key], [expected])
            self.assertGreater(len(feed), 1)
            for key, value in feed.items():
                if key != "package":
                    self.assertEqual(value["versionCode"], plan["versionCode"])
                    self.assertIn("changelog", value)
    def test_empty_abi_array_cannot_be_hidden_by_generic_fallback(self):
        for abi in ABIS[:-1]:
            with self.subTest(abi=abi):
                backend = self.seeded()
                backend.channels["stable"][0]["package"]["downloadUrlList_" + abi] = []
                with self.assertRaises(publish.InvalidRelease):
                    publish.feed_version(backend.channels["stable"][0])
                self.reject_unchanged(backend, fixture())
    def test_receipt_rejects_candidate_plan_and_artifact_substitution(self):
        for field in ("candidateId", "plan", "artifacts"):
            with self.subTest(field=field):
                backend = self.seeded()
                old_receipt = copy.deepcopy(backend.channels["stable"][1])
                self.assertEqual(publish.publish(*fixture(2), backend)["status"], "PUBLISHED")
                backend.channels["stable"][1][field] = old_receipt[field]
                backend.events.clear()
                self.reject_unchanged(backend, fixture(2))
    def test_already_published_newer_version_blocks_stale_candidate(self):
        backend = Backend()
        self.assertEqual(publish.publish(*fixture(3), backend)["status"], "PUBLISHED")
        backend.events.clear()
        self.reject_unchanged(backend, fixture(2))
        self.assertNotIn("ensure", self.names(backend))
    def test_slow_old_candidate_loses_to_newer_same_channel(self):
        backend = self.seeded()
        newest = fixture(3)
        backend.before_commit = lambda: self.assertEqual(publish.publish(*newest, backend)["status"], "PUBLISHED")
        with self.assertRaises(publish.InvalidRelease):
            publish.publish(*fixture(2), backend)
        self.assertEqual(publish.feed_version(backend.channels["stable"][0]), newest[0]["versionCode"])
        self.assertEqual(backend.serial, 2)
        self.assertGreaterEqual(backend.events.count(("load", "stable")), 3)
    def test_other_channel_cas_conflict_reloads_without_losing_either_channel(self):
        backend, beta = self.seeded(), fixture(channel="beta")
        backend.before_commit = lambda: self.assertEqual(publish.publish(*beta, backend)["status"], "PUBLISHED")
        self.assertEqual(publish.publish(*fixture(2), backend)["status"], "PUBLISHED")
        for channel, expected in (("stable", fixture(2)[0]), ("beta", beta[0])):
            self.assertEqual(publish.feed_version(backend.channels[channel][0]), expected["versionCode"])
        self.assertEqual(backend.conflicts, 1)
        self.assertGreaterEqual(backend.events.count(("load", "stable")), 2)
    def test_permanent_cas_conflict_stops_with_explicit_failure(self):
        backend = self.seeded()
        before, assets = copy.deepcopy((backend.head, backend.channels)), copy.deepcopy(backend.assets)
        backend.always_conflict = True
        with self.assertRaises(publish.InvalidRelease):
            publish.publish(*fixture(2), backend)
        self.assertTrue(1 <= backend.conflicts <= 12)
        self.assertNotIn("public", self.names(backend))
        self.untouched(backend, before, assets)
    def test_post_promotion_read_failure_is_not_success(self):
        backend = self.seeded()
        backend.fail = "public"
        with self.assertRaisesRegex(InjectedFailure, "^injected-public$"):
            publish.publish(*fixture(2), backend)
        names = self.names(backend)
        self.assertLess(names.index("ensure"), names.index("commit"))
        self.assertLess(names.index("commit"), names.index("public"))
    def test_failed_readback_never_rolls_back_concurrent_new_publication(self):
        backend, newest = self.seeded(), fixture(3)
        def advance_then_fail():
            self.assertEqual(publish.publish(*newest, backend)["status"], "PUBLISHED")
            raise InjectedFailure("injected-readback-after-race")
        backend.before_public = advance_then_fail
        with self.assertRaisesRegex(InjectedFailure, "^injected-readback-after-race$"):
            publish.publish(*fixture(2), backend)
        self.assertEqual(publish.feed_version(backend.channels["stable"][0]), newest[0]["versionCode"])
        self.assertEqual(backend.serial, 3)


if __name__ == "__main__":
    unittest.main()
