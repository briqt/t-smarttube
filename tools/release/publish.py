#!/usr/bin/env python3
"""Publish verified versioned assets, then atomically promote the existing JSON feed."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
import integration
import upstream

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "universal")
InvalidRelease = upstream.InvalidRelease


class Conflict(upstream.InvalidRelease):
    pass


def parse_json(content):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            upstream.require(key not in result, "duplicate-json-key")
            result[key] = value
        return result
    try:
        upstream.require(len(content) <= 8 * 1024 * 1024, "json-too-large")
        return json.loads(content, object_pairs_hook=unique)
    except upstream.InvalidRelease:
        raise
    except (ValueError, UnicodeError):
        raise upstream.InvalidRelease("invalid-json") from None


def asset_name(plan, abi):
    upstream.require(abi in ABIS, "unsupported-abi")
    return "t-SmartTube_" + plan["channel"] + "_" + plan["versionName"] + "_" + abi + ".apk"


def asset_url(plan, abi):
    return "https://github.com/" + integration.REPOSITORY + "/releases/download/" + plan["releaseTag"] + "/" + asset_name(plan, abi)


def validate_report(plan, report):
    integration.validate_plan(plan)
    upstream.require(type(report) is dict and report.get("schemaVersion") == 1 and report.get("verified") is True,
                     "unverified-artifacts")
    for key in ("candidateId", "applicationId", "versionCode", "versionName"):
        upstream.require(report.get(key) == plan[key], "artifact-plan-mismatch")
    cert = report.get("certificateSha256")
    upstream.require(type(cert) is str and integration.HEX256.fullmatch(cert), "invalid-certificate-digest")
    validate_assets(plan, report.get("assets"))
    return report


def validate_assets(plan, assets):
    upstream.require(type(assets) is dict and set(assets) == set(ABIS), "incomplete-artifact-set")
    for abi, item in assets.items():
        upstream.require(type(item) is dict and item.get("name") == asset_name(plan, abi), "wrong-asset-name")
        upstream.require(type(item.get("size")) is int and item["size"] > 1000000, "invalid-apk-size")
        upstream.require(type(item.get("sha256")) is str and integration.HEX256.fullmatch(item["sha256"]),
                         "invalid-apk-digest")
    return assets


def feed_version(feed):
    if feed is None:
        return 0
    upstream.require(type(feed) is dict and type(feed.get("package")) is dict, "invalid-feed")
    package = feed["package"]
    upstream.require(package, "empty-download-package")
    for key, value in package.items():
        upstream.require(key in {"downloadUrl", "downloadUrlList", *["downloadUrlList_" + abi for abi in ABIS]},
                         "unsupported-feed-download-field")
        urls = [value] if key == "downloadUrl" else value
        upstream.require(type(urls) is list and urls and all(type(url) is str and url.startswith(
            "https://github.com/" + integration.REPOSITORY + "/releases/download/t-") for url in urls),
                         "invalid-feed-download-url")
    versions = []
    for name, item in feed.items():
        if name == "package":
            continue
        upstream.require(type(item) is dict and type(item.get("versionCode")) is int
                         and 0 < item["versionCode"] <= 2147483647, "invalid-feed-version")
        upstream.require(type(item.get("changelog")) is list
                         and all(type(line) is str for line in item["changelog"]), "invalid-feed-changelog")
        versions.append(item["versionCode"])
    upstream.require(versions and len(versions) == len(set(versions)), "empty-or-duplicate-feed-versions")
    return max(versions)


def build_feed(plan, report, previous=None):
    validate_report(plan, report)
    old_code = feed_version(previous)
    upstream.require(old_code <= plan["versionCode"], "feed-version-regression")
    feed = copy.deepcopy(previous) if previous else {}
    if old_code == plan["versionCode"]:
        upstream.require(plan["versionName"] in feed
                         and feed[plan["versionName"]]["versionCode"] == old_code, "same-code-different-version")
    feed["package"] = {"downloadUrlList": [asset_url(plan, "universal")]}
    for abi in ABIS:
        if abi != "universal":
            feed["package"]["downloadUrlList_" + abi] = [asset_url(plan, abi)]
    feed[plan["versionName"]] = {"versionCode": plan["versionCode"],
                                "changelog": ["Based on SmartTube " + plan["upstream"]["release"]["tag"],
                                              "Bilingual subtitle enhancement revision " + str(plan["enhancementRevision"])]}
    feed_version(feed)
    return feed


def check_state(plan, state, report=None):
    upstream.require(type(state) is dict and set(state) == {"head", "feed", "receipt"}, "invalid-update-state")
    head = state["head"]
    upstream.require(head is None or (type(head) is str and upstream.SHA.fullmatch(head)), "invalid-state-head")
    code = feed_version(state["feed"])
    if code > plan["versionCode"]:
        raise upstream.InvalidRelease("stale-candidate")
    if code:
        receipt = state["receipt"]
        upstream.require(type(receipt) is dict, "published-receipt-missing")
        old_plan, old_report = receipt.get("plan"), receipt.get("artifacts")
        integration.validate_plan(old_plan, publish=True)
        validate_report(old_plan, old_report)
        upstream.require(old_plan["channel"] == plan["channel"] and old_plan["versionCode"] == code
                         and receipt.get("candidateId") == old_plan["candidateId"], "receipt-plan-mismatch")
        upstream.require(build_feed(old_plan, old_report, state["feed"]) == state["feed"], "feed-receipt-mismatch")
        if report is not None:
            upstream.require(report["certificateSha256"] == old_report["certificateSha256"], "release-signer-changed")
        if code == plan["versionCode"]:
            upstream.require(receipt["candidateId"] == plan["candidateId"], "same-code-different-candidate")
            if report is not None:
                upstream.require(report["assets"] == old_report["assets"], "same-code-different-artifact")
            return True
    return False


def needs_build(plan, backend):
    integration.validate_plan(plan)
    state = backend.load_state(plan)
    if check_state(plan, state):
        backend.verify_public_feed(plan["channel"], state["feed"])
        return False
    return True


def publish(plan, report, backend):
    """The backend is the real I/O seam; tests inject faults into this exact controller."""
    integration.validate_plan(plan, publish=True)
    validate_report(plan, report)
    state = backend.load_state(plan)
    if check_state(plan, state, report):
        old = state["receipt"]
        backend.verify_upstream(old["plan"])
        backend.ensure_release(old["plan"], old["artifacts"], repair=False)
        backend.verify_public_feed(plan["channel"], state["feed"])
        return {"status": "ALREADY_APPLIED", "candidateId": plan["candidateId"]}
    backend.verify_upstream(plan)
    backend.ensure_release(plan, report, repair=True)
    # Re-read after potentially slow uploads; an older candidate must not win later.
    for attempt in range(3):
        state = backend.load_state(plan)
        if check_state(plan, state, report):
            backend.verify_public_feed(plan["channel"], state["feed"])
            return {"status": "ALREADY_APPLIED", "candidateId": plan["candidateId"]}
        feed = build_feed(plan, report, state["feed"])
        receipt = {"schemaVersion": 1, "candidateId": plan["candidateId"], "plan": plan, "artifacts": report}
        backend.verify_upstream(plan)
        try:
            head = backend.commit_state(state["head"], plan["channel"], feed, receipt)
        except Conflict:
            if attempt == 2:
                raise
            continue
        backend.verify_public_feed(plan["channel"], feed)
        return {"status": "PUBLISHED", "candidateId": plan["candidateId"], "updatesCommit": head}
    raise Conflict("feed-promotion-conflict")


class GitHub:
    """Only this adapter performs writes, and the CLI requires an explicit publish flag."""
    def __init__(self, directory=None):
        self.directory = Path(directory) if directory else None

    def api(self, path, method="GET", data=None, optional=False, repository=integration.REPOSITORY):
        command = ["gh", "api", "--hostname", "github.com", "--method", method,
                   "repos/" + repository + "/" + path]
        if data is not None:
            command += ["--input", "-"]
        try:
            result = subprocess.run(command, input=json.dumps(data).encode() if data is not None else None,
                                    capture_output=True, timeout=90)
        except (OSError, subprocess.SubprocessError):
            raise upstream.InvalidRelease("github-command-failed") from None
        if result.returncode:
            if optional and re.search(rb"\(HTTP 404\)", result.stderr):
                return None
            if method in ("PATCH", "POST") and re.search(rb"\(HTTP (409|422)\)", result.stderr):
                raise Conflict("github-write-conflict")
            raise upstream.InvalidRelease("github-api-failed")
        return parse_json(result.stdout) if result.stdout.strip() else None

    def read_file(self, path, ref, optional=False):
        value = self.api("contents/" + path + "?ref=" + ref, optional=optional)
        if value is None:
            return None
        upstream.require(type(value) is dict and value.get("encoding") == "base64", "invalid-github-content")
        try:
            return parse_json(base64.b64decode(value["content"], validate=False))
        except (ValueError, KeyError):
            raise upstream.InvalidRelease("invalid-github-content") from None

    def load_state(self, plan):
        ref = self.api("git/ref/heads/updates", optional=True)
        if ref is None:
            return {"head": None, "feed": None, "receipt": None}
        head = ref["object"]["sha"]
        feed = self.read_file(plan["channel"] + ".json", head, optional=True)
        code = feed_version(feed)
        receipt = self.read_file("receipts/" + plan["channel"] + "/" + str(code) + ".json", head, optional=True) if code else None
        return {"head": head, "feed": feed, "receipt": receipt}

    def verify_upstream(self, plan):
        release = plan["upstream"]["release"]
        actual = self.api("releases/" + str(release["releaseId"]), repository=upstream.REPOSITORY)
        upstream.require(actual.get("tag_name") == release["tag"] and actual.get("draft") is False
                         and actual.get("prerelease") == (plan["channel"] == "beta"), "upstream-release-changed")
        upstream.require(self.tag_commit(release["tag"], upstream.REPOSITORY) == plan["upstream"]["commit"],
                         "upstream-tag-moved")

    def tag_commit(self, name, repository, optional=False):
        ref = self.api("git/ref/tags/" + name, repository=repository, optional=optional)
        if ref is None and optional:
            return None
        upstream.require(type(ref) is dict and type(ref.get("object")) is dict, "invalid-git-tag")
        obj, seen = ref["object"], set()
        while obj.get("type") == "tag":
            sha = obj.get("sha")
            upstream.require(type(sha) is str and upstream.SHA.fullmatch(sha) and sha not in seen and len(seen) < 8,
                             "invalid-git-tag")
            seen.add(sha)
            tag = self.api("git/tags/" + sha, repository=repository)
            upstream.require(type(tag) is dict and tag.get("sha") == sha, "git-tag-object-mismatch")
            obj = tag.get("object")
            upstream.require(type(obj) is dict, "invalid-git-tag")
        upstream.require(obj.get("type") == "commit" and type(obj.get("sha")) is str
                         and upstream.SHA.fullmatch(obj["sha"]), "invalid-git-tag")
        return obj["sha"]

    def public_bytes(self, url, cap):
        try:
            with urlopen(url, timeout=60) as response:
                content = response.read(cap + 1)
                upstream.require(response.status == 200 and len(content) <= cap, "invalid-public-download")
                return content
        except (HTTPError, URLError, OSError):
            raise upstream.InvalidRelease("public-readback-failed") from None

    def ensure_release(self, plan, report, repair):
        tag = plan["releaseTag"]
        release = self.api("releases/tags/" + tag, optional=True)
        target = self.tag_commit(tag, integration.REPOSITORY, optional=True)
        upstream.require(target is None or target == plan["enhancement"]["commit"], "release-tag-target-mismatch")
        if release is not None and release.get("draft") is False:
            upstream.require(target == plan["enhancement"]["commit"], "release-tag-target-mismatch")
        if release is None:
            upstream.require(repair, "published-release-missing")
            release = self.api("releases", "POST", {"tag_name": tag, "target_commitish": plan["enhancement"]["commit"],
                               "name": "t-SmartTube " + plan["channel"] + " " + plan["versionName"], "draft": True,
                               "prerelease": plan["channel"] == "beta",
                               "body": "Built from SmartTube " + plan["upstream"]["release"]["tag"]
                               + " with the shared bilingual subtitle enhancement. Source inputs: source-lock.json."})
        upstream.require(release.get("tag_name") == tag and release.get("prerelease") == (plan["channel"] == "beta"),
                         "release-identity-conflict")
        files = {item["name"]: item for item in report["assets"].values()}
        lock_bytes = (json.dumps(plan, sort_keys=True, indent=2) + "\n").encode()
        files["source-lock.json"] = {"name": "source-lock.json", "size": len(lock_bytes),
                                      "sha256": hashlib.sha256(lock_bytes).hexdigest()}
        with tempfile.TemporaryDirectory(prefix="tsmarttube-release-check-") as directory:
            local = Path(directory)
            (local / "source-lock.json").write_bytes(lock_bytes)
            assets = {}
            for item in release.get("assets", []):
                upstream.require(item["name"] not in assets, "duplicate-release-asset")
                assets[item["name"]] = item
            for name, expected in files.items():
                if name not in assets:
                    upstream.require(repair and release.get("draft") is True, "immutable-release-asset-missing")
                    source = local / name if name == "source-lock.json" else self.directory / name
                    upstream.require(source.is_file() and integration.sha256(source) == expected["sha256"],
                                     "local-asset-digest-mismatch")
                    integration.run(["gh", "release", "upload", tag, str(source), "--repo", "github.com/" + integration.REPOSITORY])
                target = local / ("check-" + name)
                integration.run(["gh", "release", "download", tag, "--repo", "github.com/" + integration.REPOSITORY,
                                 "--pattern", name, "--output", str(target)])
                upstream.require(target.stat().st_size == expected["size"]
                                 and integration.sha256(target) == expected["sha256"], "release-asset-readback-mismatch")
            if release.get("draft") is True:
                upstream.require(repair, "release-still-draft")
                self.api("releases/" + str(release["id"]), "PATCH", {"draft": False})
            for name, expected in files.items():
                url = "https://github.com/" + integration.REPOSITORY + "/releases/download/" + tag + "/" + name
                content = self.public_bytes(url, expected["size"])
                upstream.require(len(content) == expected["size"]
                                 and hashlib.sha256(content).hexdigest() == expected["sha256"],
                                 "public-asset-readback-mismatch")
            upstream.require(self.tag_commit(tag, integration.REPOSITORY, optional=True)
                             == plan["enhancement"]["commit"], "release-tag-target-mismatch")

    def commit_state(self, expected_head, channel, feed, receipt):
        tree_input = {"tree": [
            {"path": channel + ".json", "mode": "100644", "type": "blob", "content": upstream.canonical(feed) + "\n"},
            {"path": "receipts/" + channel + "/" + str(receipt["plan"]["versionCode"]) + ".json",
             "mode": "100644", "type": "blob", "content": upstream.canonical(receipt) + "\n"}]}
        if expected_head is not None:
            parent = self.api("git/commits/" + expected_head)
            tree_input["base_tree"] = parent["tree"]["sha"]
        tree = self.api("git/trees", "POST", tree_input)
        commit = self.api("git/commits", "POST", {"message": "Update " + channel + " to " + receipt["plan"]["versionName"],
                          "tree": tree["sha"], "parents": [expected_head] if expected_head else []})
        if expected_head is None:
            self.api("git/refs", "POST", {"ref": "refs/heads/updates", "sha": commit["sha"]})
        else:
            # A concurrent sibling commit is not a fast-forward; never force it.
            self.api("git/refs/heads/updates", "PATCH", {"sha": commit["sha"], "force": False})
        return commit["sha"]

    def verify_public_feed(self, channel, expected):
        content = self.public_bytes("https://raw.githubusercontent.com/" + integration.REPOSITORY
                                    + "/updates/" + channel + ".json", 1024 * 1024)
        upstream.require(parse_json(content) == expected, "feed-public-readback-pending-or-mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--publish", action="store_true", help="Explicitly allow remote writes")
    args = parser.parse_args()
    try:
        plan, report = parse_json(args.plan.read_bytes()), parse_json(args.report.read_bytes())
        integration.validate_plan(plan, publish=True)
        validate_report(plan, report)
        upstream.require(args.certificate_sha256.lower() == report["certificateSha256"], "unexpected-release-certificate")
        upstream.require(args.publish, "publish-not-authorized-use-explicit-flag")
        # Inspect the actual signed bytes again in the publisher's clean runner.
        import artifacts
        verified = artifacts.verify_directory(plan, args.apk_dir, report["certificateSha256"])
        upstream.require(verified == report, "publisher-artifact-verification-mismatch")
        print(json.dumps(publish(plan, report, GitHub(args.apk_dir)), indent=2))
    except (upstream.InvalidRelease, OSError, ValueError, KeyError, TypeError) as error:
        code = str(error) if isinstance(error, upstream.InvalidRelease) else "invalid-publication-input"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
