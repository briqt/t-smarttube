#!/usr/bin/env python3
"""Small entry points for discovery planning and the existing Gradle validation tasks."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import artifacts
import integration
import publish
import upstream


def plan_releases(repo, observation, base, output, channel="all", include_worktree=False, backend=None, fetch_upstream=False):
    repo, output = Path(repo).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    upstream.require(type(observation) is dict and type(observation.get("channels")) is dict
                     and set(observation["channels"]) == set(integration.APPLICATIONS), "invalid-observation-channels")
    upstream.require(channel in ("all", *integration.APPLICATIONS), "invalid-requested-channel")
    config = publish.parse_json((repo / "tools/release/config.json").read_bytes())
    configured_base = config.get("upstreamBase")
    upstream.require(type(configured_base) is str and upstream.SHA.fullmatch(configured_base), "invalid-enhancement-base")
    upstream.require(base is None or base == configured_base, "enhancement-base-mismatch")
    if fetch_upstream:
        integration.git(repo, "fetch", "--no-tags", "--filter=blob:none", integration.UPSTREAM_URL, configured_base)
    enhancement = integration.export_delta(repo, configured_base, output / "delta", include_worktree)
    matrix, decisions = [], []
    for name, lock in observation["channels"].items():
        if channel not in ("all", name):
            continue
        commit = lock["source"]["commit"]
        upstream.require(type(commit) is str and upstream.SHA.fullmatch(commit), "invalid-source-sha")
        if fetch_upstream:
            integration.git(repo, "fetch", "--no-tags", "--filter=blob:none", integration.UPSTREAM_URL, commit)
        # Only exact SHA arguments; fetched objects remain pinned to the observation.
        source = integration.git(repo, "show", commit + ":smarttubetv/build.gradle").decode()
        timestamp = int(integration.git(repo, "show", "-s", "--format=%ct", commit).decode().strip()) * 1000
        plan = integration.make_plan(lock, enhancement, config, source, timestamp)
        needed = publish.needs_build(plan, backend) if backend is not None else True
        integration.write_json(output / (name + "-plan.json"), plan)
        integration.write_json(output / (name + "-source.json"), lock)
        decisions.append({"channel": name, "build": needed, "candidateId": plan["candidateId"]})
        if needed:
            matrix.append({"channel": name})
    upstream.require(decisions, "no-channel-selected")
    result = {"include": matrix}
    integration.write_json(output / "matrix.json", result)
    integration.write_json(output / "decisions.json", decisions)
    return result


def collect_tests(root, flavor, started):
    expected = {str(path.relative_to(root / "common/src/test/java")).replace("/", ".")[:-5]
                for path in (root / "common/src/test/java/com/liskovsoft/smartyoutubetv2/common/subtitle").rglob("*Test.java")}
    expected.add("com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppPreferenceManagerTest")
    suites = []
    for module in ("common", "smarttubetv"):
        directory = root / module / ("build/test-results/test" + flavor + "DebugUnitTest")
        for path in directory.glob("TEST-*.xml"):
            if path.stat().st_mtime < started:
                continue
            try:
                suite = ET.parse(path).getroot()
                suites.append({key: suite.attrib[key] for key in ("name", "tests", "failures", "errors", "skipped")})
            except (ET.ParseError, KeyError):
                raise upstream.InvalidRelease("invalid-test-receipt") from None
    upstream.require(expected and {suite["name"] for suite in suites} == expected
                     and len(suites) == len(expected), "missing-or-extra-required-test-suite")
    counts = {key: sum(int(suite[key]) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    upstream.require(counts["tests"] > 0 and counts["failures"] == counts["errors"] == counts["skipped"] == 0,
                     "required-tests-not-passed")
    return {"scope": "subtitle-and-native-preference", "suites": suites, **counts}


def build(root, plan, integration_receipt, output, offline=False, robolectric_sdk=None):
    root, output = Path(root).resolve(), Path(output).resolve()
    integration.validate_plan(plan)
    upstream.require(integration_receipt.get("submodulesVerified") is True, "recursive-submodules-not-verified")
    upstream.require(integration.git(root, "rev-parse", "HEAD").decode().strip() == plan["upstream"]["commit"],
                     "build-head-mismatch")
    upstream.require(integration.git(root, "write-tree").decode().strip() == integration_receipt.get("integratedTree"),
                     "integrated-tree-changed-before-build")
    upstream.require(not (root / "keystore.properties").exists(), "signing-material-in-build-tree")
    for name in ("SharedModules", "MediaServiceCore"):
        upstream.require(not (root.parent / name).exists(), "neighbor-project-shadow")
    output.mkdir(parents=True, exist_ok=False)
    flavor = "St" + plan["channel"]
    command = ["bash", "./gradlew", "--no-daemon", "--console=plain", "--max-workers=1",
               "-Dorg.gradle.jvmargs=-Xmx2048m", "-Pkotlin.compiler.execution.strategy=in-process",
               "-PenhancementRevision=" + str(plan["enhancementRevision"]),
               "-PforkBuildTimestamp=" + str(plan["buildTimestamp"])]
    if offline:
        command.append("--offline")
    if robolectric_sdk:
        command.append("-ProbolectricSdkDir=" + str(Path(robolectric_sdk).resolve()))
    command += [":smarttubetv:lint" + flavor + "Release", ":smarttubetv:assemble" + flavor + "Release",
                ":common:cleanTest" + flavor + "DebugUnitTest", ":common:test" + flavor + "DebugUnitTest",
                "--tests", "com.liskovsoft.smartyoutubetv2.common.subtitle.*",
                ":smarttubetv:cleanTest" + flavor + "DebugUnitTest", ":smarttubetv:test" + flavor + "DebugUnitTest",
                "--tests", "com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppPreferenceManagerTest"]
    started = datetime.now().astimezone()
    with (output / "gradle.log").open("xb") as log:
        process = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    upstream.require(process.returncode == 0, "gradle-validation-failed-see-gradle-log")
    tests = collect_tests(root, flavor, started.timestamp())
    lint_file = root / "smarttubetv/build/reports" / ("lint-results-st" + plan["channel"] + "Release.xml")
    upstream.require(lint_file.is_file() and lint_file.stat().st_mtime >= started.timestamp(), "lint-receipt-missing")
    try:
        lint = ET.parse(lint_file).getroot()
        upstream.require(not any(issue.get("severity") in ("Fatal", "Error") for issue in lint.findall("issue")),
                         "lint-errors-present")
    except ET.ParseError:
        raise upstream.InvalidRelease("invalid-lint-receipt") from None
    apks = root / "smarttubetv/build/outputs/apk" / ("st" + plan["channel"]) / "release"
    unsigned = output / "unsigned"
    unsigned.mkdir()
    for abi in publish.ABIS:
        name = publish.asset_name(plan, abi)
        upstream.require((apks / name).is_file(), "built-apk-missing")
        shutil.copyfile(apks / name, unsigned / name)
    report = artifacts.verify_directory(plan, unsigned)
    evidence = {"schemaVersion": 1, "candidateId": plan["candidateId"], "lintPassed": True,
                "tests": tests, "integration": integration_receipt, "unsignedArtifacts": report["assets"],
                "sourceVersionCode": plan["upstreamVersionCode"], "sourceVersionName": plan["upstream"]["release"]["versionName"],
                "startedAt": started.isoformat(), "finishedAt": datetime.now().astimezone().isoformat()}
    integration.write_json(unsigned / "build-receipt.json", evidence)
    integration.write_json(output / "unsigned-artifacts.json", report)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    planner = commands.add_parser("plan")
    planner.add_argument("--repo", type=Path, default=Path.cwd())
    planner.add_argument("--observation", type=Path, required=True)
    planner.add_argument("--base", help="Optional assertion; must equal config.json upstreamBase")
    planner.add_argument("--output-dir", type=Path, required=True)
    planner.add_argument("--channel", choices=("all", "stable", "beta"), default="all")
    planner.add_argument("--include-worktree", action="store_true")
    planner.add_argument("--read-release-state", action="store_true")
    planner.add_argument("--fetch-upstream", action="store_true")
    builder = commands.add_parser("build")
    builder.add_argument("--root", type=Path, required=True)
    builder.add_argument("--plan", type=Path, required=True)
    builder.add_argument("--integration-receipt", type=Path, required=True)
    builder.add_argument("--output-dir", type=Path, required=True)
    builder.add_argument("--offline", action="store_true")
    builder.add_argument("--robolectric-sdk", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "plan":
            result = plan_releases(args.repo, publish.parse_json(args.observation.read_bytes()), args.base, args.output_dir,
                                   args.channel, args.include_worktree, publish.GitHub() if args.read_release_state else None,
                                   args.fetch_upstream)
            if os.environ.get("GITHUB_OUTPUT"):
                with Path(os.environ["GITHUB_OUTPUT"]).open("a") as file:
                    file.write("matrix=" + json.dumps(result, separators=(",", ":")) + "\n")
                    file.write("has_candidates=" + str(bool(result["include"])).lower() + "\n")
        else:
            result = build(args.root, publish.parse_json(args.plan.read_bytes()),
                           publish.parse_json(args.integration_receipt.read_bytes()), args.output_dir,
                           args.offline, args.robolectric_sdk)
        print(json.dumps(result, indent=2, sort_keys=True))
    except (upstream.InvalidRelease, OSError, ValueError, KeyError) as error:
        code = str(error) if isinstance(error, upstream.InvalidRelease) else "invalid-pipeline-input"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
