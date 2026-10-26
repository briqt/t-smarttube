"""Unsigned-byte binding and resource-boundary fixtures; no real key or network."""
import base64
import copy
import json
import os
import stat
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import artifacts
import integration
import publish
import upstream
from test_publish import fixture


class ArtifactEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tsmarttube-evidence-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.plan, report = fixture(revision=0)
        self.assets = {}
        for index, abi in enumerate(publish.ABIS):
            name = publish.asset_name(self.plan, abi)
            path = self.directory / name
            path.write_bytes(bytes([index + 1]) * 1000001)
            self.assets[abi] = {"name": name, "size": path.stat().st_size, "sha256": integration.sha256(path)}
        self.evidence = {"schemaVersion": 1, "candidateId": self.plan["candidateId"], "lintPassed": True,
                         "tests": {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, "suites": [{"name": "fixture"}]},
                         "integration": {"submodulesVerified": True, "sourceVersionCode": self.plan["upstreamVersionCode"],
                                         "sourceVersionName": "32.68", "submodules": {
                                             name: {"commit": sha} for name, sha in self.plan["upstream"]["rootGitlinks"].items()}},
                         "unsignedArtifacts": self.assets}
        self.save()

    def save(self):
        (self.directory / "build-receipt.json").write_text(json.dumps(self.evidence))

    def test_matching_unsigned_bytes_are_accepted(self):
        self.assertEqual(artifacts.build_evidence(self.plan, self.directory), self.evidence)

    def test_swapping_same_size_apk_cannot_borrow_another_builds_receipt(self):
        path = self.directory / self.assets["x86"]["name"]
        path.write_bytes(b"B" * path.stat().st_size)
        with self.assertRaisesRegex(upstream.InvalidRelease, "unsigned-apk-receipt-mismatch"):
            artifacts.build_evidence(self.plan, self.directory)

    def test_missing_unsigned_hashes_are_not_build_evidence(self):
        self.evidence.pop("unsignedArtifacts")
        self.save()
        with self.assertRaises(upstream.InvalidRelease):
            artifacts.build_evidence(self.plan, self.directory)

    def test_changed_size_and_symlink_are_rejected(self):
        path = self.directory / self.assets["x86"]["name"]
        path.write_bytes(b"short")
        with self.assertRaises(upstream.InvalidRelease):
            artifacts.build_evidence(self.plan, self.directory)
        path.unlink()
        path.symlink_to(self.directory / self.assets["arm64-v8a"]["name"])
        with self.assertRaises(upstream.InvalidRelease):
            artifacts.build_evidence(self.plan, self.directory)

    def test_invalid_candidate_or_failed_tests_are_rejected(self):
        original = copy.deepcopy(self.evidence)
        for mutate in (lambda e: e.update(candidateId="0" * 64), lambda e: e.update(lintPassed=False),
                       lambda e: e["tests"].update(failures=1), lambda e: e["tests"].update(skipped=1)):
            self.evidence = copy.deepcopy(original)
            mutate(self.evidence)
            self.save()
            with self.assertRaises(upstream.InvalidRelease):
                artifacts.build_evidence(self.plan, self.directory)

    def test_resource_bag_ends_before_next_type_specs(self):
        text = ('        resource 0x7f000001 app:array/update_urls: <bag>\n'
                '          Parent=0x00000000, Count=1\n'
                '          #0: (string8) "https://example.invalid/feed"\n'
                '    type 3 configCount=1\n'
                '      spec resource 0x7f000002 app:attr/unrelated: flags=0\n')
        blocks = list(artifacts.resource_blocks(text, "array/update_urls"))
        self.assertEqual(len(blocks), 1)
        self.assertNotIn("unrelated", blocks[0])

    def test_wrapped_base64_is_decoded_privately_with_mocked_signer(self):
        fixture_bytes = b"synthetic fixture, not a real keystore"
        values = {"TSMARTTUBE_SIGNING_KEY": base64.encodebytes(fixture_bytes).decode(),
                  "TSMARTTUBE_STORE_PASSWORD": "fixture-only", "TSMARTTUBE_KEY_PASSWORD": "fixture-only",
                  "TSMARTTUBE_KEY_ALIAS": "fixture"}
        keys = []
        def fake_sign(command):
            key = Path(command[command.index("--ks") + 1])
            keys.append(key)
            self.assertEqual(key.read_bytes(), fixture_bytes)
            self.assertEqual(stat.S_IMODE(key.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(key.parent.stat().st_mode), 0o700)
            self.assertIn("env:TSMARTTUBE_STORE_PASSWORD", command)
            self.assertNotIn("fixture-only", command)
            return b""
        with patch.dict(os.environ, values), patch.object(artifacts, "tools_dir", return_value=Path("/fixture")), \
                patch.object(artifacts, "verify_directory", side_effect=[{}, {"fixture": "mocked-signature-tool"}]), \
                patch.object(integration, "run", side_effect=fake_sign):
            try:
                report = artifacts.sign_directory(self.plan, self.directory, self.directory / "output", "d" * 64)
            except upstream.InvalidRelease as error:
                self.fail("Wrapped fixture base64 must be accepted: " + str(error))
        self.assertEqual(report, {"fixture": "mocked-signature-tool"})
        self.assertEqual(len(keys), 4)
        self.assertTrue(all(not key.exists() for key in keys))

    def test_worktree_snapshot_is_rejected_before_signing_or_key_access(self):
        self.plan["enhancement"]["workingTreeSnapshot"] = True
        output = self.directory / "must-not-exist"
        with self.assertRaisesRegex(upstream.InvalidRelease, "worktree-snapshot-cannot-publish"):
            artifacts.sign_directory(self.plan, self.directory, output, "d" * 64)
        self.assertFalse(output.exists())


class NativeAbiTest(unittest.TestCase):
    def inspect_fixture(self, machine, elf_class):
        plan, _ = fixture(revision=0)
        badge = ("package: name='" + plan["applicationId"] + "' versionCode='" + str(plan["versionCode"])
                 + "' versionName='" + plan["versionName"] + "'\nsdkVersion:'17'\n")
        resource = ('        resource 0x7f000001 app:array/update_urls: <bag>\n'
                    '          Parent=0, Count=1\n'
                    '          #0: (string8) "https://raw.githubusercontent.com/briqt/t-smarttube/updates/stable.json"\n'
                    '        resource 0x7f000002 app:xml/searchable:\n'
                    '          (string8) "res/xml/searchable.xml"\n')
        search = ('A: android:searchSuggestAuthority(0x1)="' + plan["applicationId"] + '"\n'
                  'A: android:searchSuggestIntentData(0x2)="content://' + plan["applicationId"] + '/video"\n')
        def fake_aapt(command):
            if "badging" in command:
                return badge.encode()
            return (search if "xmltree" in command else resource).encode()
        header = bytearray(64)
        header[:4] = b"\x7fELF"
        header[4:6] = bytes((elf_class, 1))
        header[18:20] = machine.to_bytes(2, "little")
        with tempfile.TemporaryDirectory(prefix="tsmarttube-elf-fixture-") as directory:
            apk = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libfixture.so", header)
                archive.writestr("assets/padding", b"x" * 1000001)
                for name in ("index.html", "pair.js", "pair.css"):
                    archive.writestr("assets/caption-pairing/" + name, "fixture")
            with patch.object(integration, "run", side_effect=fake_aapt):
                return artifacts.inspect_apk(plan, apk, "arm64-v8a", tools=Path("/fixture-tools"))

    def test_native_header_matches_its_packaged_architecture(self):
        self.assertEqual(self.inspect_fixture(183, 2)["nativeAbis"], ["arm64-v8a"])

    def test_arm32_library_cannot_masquerade_under_arm64_directory(self):
        with self.assertRaisesRegex(upstream.InvalidRelease, "apk-native-machine-mismatch"):
            self.inspect_fixture(40, 1)


if __name__ == "__main__":
    unittest.main()
