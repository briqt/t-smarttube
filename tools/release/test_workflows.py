"""Structural regression checks for these workflows, not a general YAML/security parser."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def job(text, name):
    match = re.search(r"(?ms)^  " + name + r":\n(.*?)(?=^  [a-zA-Z_][a-zA-Z0-9_-]*:|\Z)", text)
    return match[1] if match else ""


def run_bodies(text):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not re.match(r"^[ \t]+run:", line):
            continue
        indent = len(line) - len(line.lstrip())
        body = [line.split("run:", 1)[1]]
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            body.append(following)
        yield "\n".join(body)


def violations(follow, channel):
    errors = []
    build, sign, publisher = (job(channel, name) for name in ("build", "sign", "publish"))
    if not all((build, sign, publisher)):
        errors.append("missing-isolated-job")
    for name, block in (("build", build), ("sign", sign)):
        if not re.search(r"(?m)^    permissions:\n      contents: read$", block):
            errors.append(name + "-not-read-only")
    if not re.search(r"(?m)^    permissions:\n      contents: write$", publisher):
        errors.append("publisher-write-permission-missing")
    if re.search(r"(?m)^    environment:", build + publisher) or "TSMARTTUBE_SIGNING_KEY" in build + publisher:
        errors.append("signing-environment-leaked")
    if "    environment: release-signing" not in sign:
        errors.append("signing-environment-missing")
    if re.search(r"secrets:\s*inherit", follow + channel):
        errors.append("secrets-inherited-to-build")
    if ("inputs.allow_publish" not in sign or "TSMARTTUBE_RELEASES_ENABLED == 'true'" not in sign
            or "TSMARTTUBE_RELEASES_ENABLED == 'true'" not in publisher):
        errors.append("publication-switch-missing")
    if "github.repository == 'briqt/t-smarttube'" not in job(follow, "tools") or "github.ref == 'refs/heads/master'" not in build:
        errors.append("trusted-source-guard-missing")
    if not re.search(r"(?m)^        default: false$", follow):
        errors.append("dispatch-not-validation-by-default")
    if "cancel-in-progress: false" not in follow or "fail-fast: false" not in follow:
        errors.append("channel-cancellation-boundary-changed")
    if "    needs: build" not in sign or "    needs: sign" not in publisher:
        errors.append("validation-before-publication-order-broken")
    if "--publish" not in publisher or "--certificate-sha256" not in publisher:
        errors.append("explicit-publication-or-certificate-check-missing")
    if "artifact-ids:" not in sign or "artifact-ids:" not in publisher:
        errors.append("artifact-id-binding-missing")
    if "--include-worktree" in follow + channel or "merge-base" in follow:
        errors.append("untrusted-or-inferred-baseline")
    if "cache: gradle" in sign + publisher or "./gradlew" in sign + publisher or "pipeline.py build" in sign + publisher:
        errors.append("candidate-execution-in-privileged-runner")
    for text in (follow, channel):
        uses = re.findall(r"uses:\s*(actions/[^\s]+)", text)
        if any(not re.fullmatch(r"actions/[^@\s]+@[0-9a-f]{40}", value) for value in uses):
            errors.append("unpinned-action")
        if any("${{" in body for body in run_bodies(text)):
            errors.append("expression-interpolated-into-shell")
        for match in re.finditer(r"uses: actions/checkout@[0-9a-f]{40}\n(.*?)(?=\n      -|\Z)", text, re.DOTALL):
            if "persist-credentials: false" not in match[1]:
                errors.append("checkout-credentials-persisted")
    return errors


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.follow = (ROOT / ".github/workflows/t-smarttube-follow.yml").read_text()
        self.channel = (ROOT / ".github/workflows/t-smarttube-channel.yml").read_text()

    def test_authored_workflow_contract(self):
        self.assertEqual(violations(self.follow, self.channel), [])

    def test_caller_binds_only_nonsecret_environment_placeholders(self):
        caller = job(self.follow, "channel")
        bindings = re.findall(r"(?m)^      (TSMARTTUBE_[A-Z_]+): (.+)$", caller)
        self.assertEqual(dict(bindings), {
            "TSMARTTUBE_SIGNING_KEY": "environment-only",
            "TSMARTTUBE_STORE_PASSWORD": "environment-only",
            "TSMARTTUBE_KEY_PASSWORD": "environment-only",
            "TSMARTTUBE_KEY_ALIAS": "environment-only",
        })
        for name, _ in bindings:
            self.assertIn("      " + name + ":\n        required: true", self.channel)

    def test_each_fresh_runner_initializes_android_before_sdkmanager(self):
        for name in ("build", "sign", "publish"):
            block = job(self.channel, name)
            setup = re.search(r"uses: android-actions/setup-android@[0-9a-f]{40}", block)
            self.assertIsNotNone(setup, name)
            self.assertLess(setup.start(), block.index("sdkmanager"), name)

    def test_bad_permission_mutation_is_detected(self):
        changed = self.channel.replace("      contents: read", "      contents: write", 1)
        self.assertIn("build-not-read-only", violations(self.follow, changed))

    def test_signing_key_in_build_mutation_is_detected(self):
        changed = self.channel.replace("  build:\n", "  build:\n    environment: release-signing\n", 1)
        self.assertIn("signing-environment-leaked", violations(self.follow, changed))

    def test_secret_inheritance_mutation_is_detected(self):
        self.assertIn("secrets-inherited-to-build", violations(self.follow + "\n    secrets: inherit\n", self.channel))

    def test_shell_expression_mutation_is_detected(self):
        changed = self.channel.replace("run: sdkmanager", 'run: echo "${{ inputs.channel }}"; sdkmanager', 1)
        self.assertIn("expression-interpolated-into-shell", violations(self.follow, changed))

    def test_worktree_and_base_inference_mutations_are_detected(self):
        for suffix in (" --include-worktree", ' --base "$(git merge-base HEAD FETCH_HEAD)"'):
            changed = self.follow.replace("--fetch-upstream --read-release-state", "--fetch-upstream --read-release-state" + suffix)
            self.assertNotEqual(changed, self.follow)
            self.assertIn("untrusted-or-inferred-baseline", violations(changed, self.channel))

    def test_actions_are_not_floating_tags(self):
        changed = re.sub(r"uses: actions/checkout@[0-9a-f]{40}", "uses: actions/checkout@main", self.follow, count=1)
        self.assertIn("unpinned-action", violations(changed, self.channel))

    def test_privileged_runner_cannot_execute_gradle(self):
        old = job(self.channel, "sign")
        changed = self.channel.replace(old, old.replace("    steps:\n", "    steps:\n      - run: ./gradlew assembleRelease\n", 1), 1)
        self.assertNotEqual(changed, self.channel)
        self.assertIn("candidate-execution-in-privileged-runner", violations(self.follow, changed))


if __name__ == "__main__":
    unittest.main()
