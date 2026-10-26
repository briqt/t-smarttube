#!/usr/bin/env python3
"""Apply one enhancement delta to an exact upstream release in a new directory."""
import argparse
import configparser
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import tempfile
from urllib.parse import urljoin

sys.path.insert(0, str(Path(__file__).resolve().parent))
import upstream

REPOSITORY = "briqt/t-smarttube"
UPSTREAM_URL = "https://github.com/yuliskov/SmartTube"
APPLICATIONS = {"stable": "io.github.briqt.tsmarttube.stable", "beta": "io.github.briqt.tsmarttube.beta"}
APP_PATHS = ("common", "smarttubetv", "gradle/fork-identity.gradle", "gradle/subtitle-tests.gradle")
HEX256 = re.compile(r"[0-9a-f]{64}")


def run(command, cwd=None, env=None, input=None, timeout=120):
    try:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            stdout, _ = process.communicate(input=input, timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
            raise upstream.InvalidRelease("command-timeout") from None
        upstream.require(process.returncode == 0, "command-failed")
        return stdout
    except OSError:
        raise upstream.InvalidRelease("command-start-failed") from None


def git(repo, *args, env=None, input=None):
    timeout = 600 if any(arg in ("clone", "fetch", "submodule") for arg in args) else 120
    return run(["git", "-c", "core.hooksPath=/dev/null", "-c", "protocol.ext.allow=never", *args],
               cwd=repo, env=env, input=input, timeout=timeout)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    with Path(path).open("x", encoding="utf-8") as file:
        json.dump(value, file, sort_keys=True, indent=2)
        file.write("\n")


def version_literals(text):
    codes = re.findall(r"^\s*versionCode\s+([0-9]+)\s*$", text, re.MULTILINE)
    names = re.findall(r'^\s*versionName\s+"([0-9]+\.[0-9]+)"\s*$', text, re.MULTILINE)
    upstream.require(len(codes) == len(names) == 1, "ambiguous-upstream-version")
    code = int(codes[0])
    upstream.require(code > 0, "invalid-upstream-version")
    return code, names[0]


def revision_config(config):
    upstream.require(type(config) is dict and config.get("schemaVersion") == 1, "invalid-config")
    revision = config.get("enhancementRevision")
    upstream.require(type(revision) is int and 0 <= revision <= 999, "invalid-enhancement-revision")
    recipe = config.get("buildRecipeVersion")
    upstream.require(type(recipe) is int and recipe > 0, "invalid-build-recipe")
    return revision, recipe


def export_delta(repo, base, output, include_worktree=False):
    """Use a temporary index: never stage, commit, or reset the developer's worktree."""
    repo = Path(repo).resolve()
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    base = git(repo, "rev-parse", "--verify", base + "^{commit}").decode().strip()
    git(repo, "merge-base", "--is-ancestor", base, head)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="tsmarttube-index-") as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))
        git(repo, "read-tree", head, env=env)
        if include_worktree:
            paths = [name for name in APP_PATHS if (repo / name).exists()
                     or git(repo, "ls-files", "--", name).strip()]
            git(repo, "add", "-A", "--", *paths, env=env)
        else:
            dirty = git(repo, "status", "--porcelain", "--untracked-files=all")
            upstream.require(not dirty.strip(), "uncommitted-enhancement-use-explicit-worktree-snapshot")
        changed = set(git(repo, "diff", "--cached", "--name-only", "-z", base, env=env).decode().strip("\0").split("\0"))
        if include_worktree:
            changed.update(git(repo, "diff", "--name-only", "-z", "HEAD").decode().strip("\0").split("\0"))
            changed.update(git(repo, "ls-files", "--others", "--exclude-standard", "-z").decode().strip("\0").split("\0"))
        for path in changed - {""}:
            app_path = any(path == prefix or path.startswith(prefix + "/") for prefix in APP_PATHS)
            control_path = path == ".gitignore" or path.startswith((".github/", "tools/release/")) or path.endswith(".md")
            upstream.require((app_path or control_path) and not path.startswith(".doc/"), "unreviewed-enhancement-path")
        listing = git(repo, "diff", "--cached", "--name-status", "-z", base, "--", *APP_PATHS, env=env)
        upstream.require(listing.strip(b"\0"), "empty-enhancement")
        raw = git(repo, "diff", "--cached", "--raw", "--no-renames", "-z", base, "--", *APP_PATHS, env=env)
        for metadata in raw.split(b"\0")[::2]:
            if metadata:
                upstream.require(metadata.split()[1] in (b"000000", b"100644", b"100755"), "nonregular-enhancement-file")
        patch = git(repo, "diff", "--cached", "--binary", "--full-index", "--no-ext-diff",
                    "--no-textconv", "--no-renames", base, "--", *APP_PATHS, env=env)
    (output / "enhancement.patch").write_bytes(patch)
    manifest = {"schemaVersion": 1, "base": base, "commit": head,
                "patchSha256": hashlib.sha256(patch).hexdigest(),
                "workingTreeSnapshot": bool(include_worktree)}
    write_json(output / "enhancement.json", manifest)
    return manifest


def make_plan(lock, enhancement, config, source_text, timestamp):
    revision, recipe = revision_config(config)
    upstream.require(lock.get("scope") == "upstream-root-source-only", "invalid-source-lock")
    source = lock.get("source")
    upstream.require(type(source) is dict and upstream.fingerprint(source) == lock.get("sourceFingerprint"),
                     "source-lock-digest-mismatch")
    release = source["release"]
    channel = release["channel"]
    upstream.require(channel in APPLICATIONS and release["repository"] == upstream.REPOSITORY,
                     "invalid-source-channel")
    code, name = version_literals(source_text)
    upstream.require(name == release["versionName"], "source-version-tag-mismatch")
    combined = code * 1000 + revision
    upstream.require(combined <= 2147483647, "version-code-overflow")
    upstream.require(type(timestamp) is int and timestamp > 0, "invalid-build-timestamp")
    for key in ("base", "commit"):
        upstream.require(type(enhancement.get(key)) is str and upstream.SHA.fullmatch(enhancement[key]),
                         "invalid-enhancement-commit")
    upstream.require(type(enhancement.get("patchSha256")) is str
                     and HEX256.fullmatch(enhancement["patchSha256"]), "invalid-patch-digest")
    upstream.require(type(enhancement.get("workingTreeSnapshot")) is bool, "invalid-snapshot-state")
    identity = {"sourceFingerprint": lock["sourceFingerprint"], "patchSha256": enhancement["patchSha256"],
                "enhancementRevision": revision, "buildRecipeVersion": recipe,
                "buildTimestamp": timestamp, "upstreamVersionCode": code}
    return {"schemaVersion": 1, "repository": REPOSITORY, "channel": channel,
            "candidateId": upstream.fingerprint(identity), "identity": identity,
            "upstream": source, "enhancement": dict(enhancement),
            "applicationId": APPLICATIONS[channel], "versionCode": combined, "upstreamVersionCode": code,
            "versionName": name + "-t" + str(revision), "enhancementRevision": revision,
            "releaseTag": "t-" + channel + "-" + name + "-r" + str(revision),
            "buildTimestamp": timestamp, "buildRecipeVersion": recipe}


def validate_plan(plan, publish=False):
    upstream.require(type(plan) is dict and plan.get("schemaVersion") == 1, "invalid-plan")
    upstream.require(plan.get("repository") == REPOSITORY, "wrong-downstream-repository")
    channel = plan.get("channel")
    upstream.require(channel in APPLICATIONS and plan.get("applicationId") == APPLICATIONS[channel],
                     "wrong-application-id")
    revision, recipe = revision_config({"schemaVersion": 1, "enhancementRevision": plan.get("enhancementRevision"),
                                        "buildRecipeVersion": plan.get("buildRecipeVersion")})
    source = plan.get("upstream")
    upstream.require(type(source) is dict and type(source.get("release")) is dict, "invalid-plan-source")
    release = source["release"]
    upstream.require(release.get("channel") == channel and release.get("repository") == upstream.REPOSITORY,
                     "wrong-source-channel")
    name = release.get("versionName")
    upstream.require(type(name) is str and upstream.TAG.fullmatch(name) and not name.endswith("s"),
                     "invalid-plan-version")
    upstream.require(release.get("tag") == name + ("s" if channel == "stable" else ""), "wrong-source-tag")
    identity = plan.get("identity")
    upstream.require(type(identity) is dict and identity.get("sourceFingerprint") == upstream.fingerprint(source),
                     "plan-source-digest-mismatch")
    enhancement = plan.get("enhancement")
    upstream.require(type(enhancement) is dict and type(enhancement.get("workingTreeSnapshot")) is bool,
                     "invalid-plan-enhancement")
    for key in ("base", "commit"):
        upstream.require(type(enhancement.get(key)) is str and upstream.SHA.fullmatch(enhancement[key]),
                         "invalid-plan-enhancement-commit")
    upstream.require(identity.get("patchSha256") == enhancement.get("patchSha256")
                     and type(identity.get("patchSha256")) is str and HEX256.fullmatch(identity["patchSha256"]),
                     "plan-patch-digest-mismatch")
    upstream.require(identity.get("enhancementRevision") == revision and identity.get("buildRecipeVersion") == recipe
                     and identity.get("buildTimestamp") == plan.get("buildTimestamp"), "plan-identity-mismatch")
    upstream.require(type(plan.get("buildTimestamp")) is int and plan["buildTimestamp"] > 0, "invalid-build-timestamp")
    upstream.require(plan.get("candidateId") == upstream.fingerprint(identity), "candidate-digest-mismatch")
    upstream.require(type(plan.get("upstreamVersionCode")) is int and plan["upstreamVersionCode"] > 0
                     and identity.get("upstreamVersionCode") == plan["upstreamVersionCode"], "invalid-upstream-version-code")
    upstream.require(type(plan.get("versionCode")) is int and 0 < plan["versionCode"] <= 2147483647
                     and plan["versionCode"] == plan["upstreamVersionCode"] * 1000 + revision, "invalid-plan-version-code")
    upstream.require(plan.get("versionName") == name + "-t" + str(revision), "wrong-version-name")
    upstream.require(plan.get("releaseTag") == "t-" + channel + "-" + name + "-r" + str(revision), "wrong-release-tag")
    if publish:
        upstream.require(enhancement["workingTreeSnapshot"] is False, "worktree-snapshot-cannot-publish")
    return plan


def modules(text, repository_url):
    config = configparser.ConfigParser(interpolation=None, strict=True)
    try:
        config.read_string(text)
    except configparser.Error:
        raise upstream.InvalidRelease("invalid-gitmodules") from None
    upstream.require(not config.defaults(), "gitmodules-defaults-not-allowed")
    result = {}
    for section in config.sections():
        upstream.require(section.startswith('submodule "') and section.endswith('"'), "invalid-submodule-section")
        values = dict(config[section])
        upstream.require(set(values) == {"path", "url"}, "unsupported-submodule-options")
        path, url = values["path"], values["url"]
        upstream.require(path in ("SharedModules", "MediaServiceCore") and path not in result,
                         "unexpected-submodule-path")
        if url.startswith("../"):
            url = urljoin(repository_url.rstrip("/") + "/", url)
        upstream.require(url.removesuffix(".git").rstrip("/") == "https://github.com/yuliskov/" + path,
                         "unapproved-submodule-url")
        result[path] = url
    return result


def lock_submodules(repo, repository_url=UPSTREAM_URL, prefix="", depth=0, reference_root=None):
    upstream.require(depth <= 3, "submodule-depth-exceeded")
    repo = Path(repo)
    raw = git(repo, "ls-tree", "-z", "HEAD")
    links = {}
    for line in raw.split(b"\0"):
        if not line:
            continue
        metadata, path = line.split(b"\t", 1)
        mode, kind, sha = metadata.decode().split()
        if mode == "160000":
            upstream.require(kind == "commit", "invalid-gitlink")
            links[path.decode()] = sha
    module_file = repo / ".gitmodules"
    definitions = modules(module_file.read_text(), repository_url) if module_file.exists() else {}
    upstream.require(set(links) == set(definitions), "gitmodules-gitlinks-disagree")
    locked = {}
    for path, sha in sorted(links.items()):
        child = repo / path
        reference = Path(reference_root) / path if reference_root else None
        cached = False
        if reference is not None and (reference / ".git").exists():
            url = git(reference, "remote", "get-url", "origin").decode().strip()
            cached = url.removesuffix(".git").rstrip("/") == definitions[path].removesuffix(".git").rstrip("/")
        if cached and not (child / ".git").exists():
            # --reference cannot use shallow repos. Clone the local object cache
            # through Git instead, then verify/check out the locked official SHA.
            git(repo, "submodule", "init", "--", path)
            git(repo, "clone", "--no-local", "--depth=1", "--no-checkout", "--", str(reference), str(child))
            git(child, "remote", "set-url", "origin", definitions[path])
            if git(reference, "rev-parse", "HEAD").decode().strip() != sha:
                git(child, "fetch", "--no-tags", "--depth=1", "origin", sha)
            git(child, "checkout", "--detach", sha)
        else:
            git(repo, "-c", "protocol.file.allow=never", "submodule", "update", "--init", "--depth=1", "--", path)
        upstream.require(git(child, "rev-parse", "HEAD").decode().strip() == sha, "submodule-head-mismatch")
        full_path = prefix + path
        locked[full_path] = {"commit": sha, "repository": definitions[path]}
        locked.update(lock_submodules(child, definitions[path], full_path + "/", depth + 1, reference_root))
    return locked


def prepare(repo, lock, delta_dir, output, initialize_submodules=True):
    repo, output, delta_dir = Path(repo).resolve(), Path(output).resolve(), Path(delta_dir).resolve()
    enhancement = json.loads((delta_dir / "enhancement.json").read_text())
    upstream.require(sha256(delta_dir / "enhancement.patch") == enhancement["patchSha256"], "patch-digest-mismatch")
    source = lock["source"]
    upstream.require(upstream.fingerprint(source) == lock["sourceFingerprint"], "source-lock-digest-mismatch")
    upstream.require(type(source.get("commit")) is str and upstream.SHA.fullmatch(source["commit"]), "invalid-source-sha")
    upstream.require(not output.exists(), "candidate-directory-already-exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    git(repo, "clone", "--shared", "--no-checkout", "--", str(repo), str(output))
    git(output, "remote", "set-url", "origin", UPSTREAM_URL)
    git(output, "config", "remote.origin.promisor", "true")
    git(output, "config", "remote.origin.partialclonefilter", "blob:none")
    if initialize_submodules:
        git(output, "fetch", "--no-tags", "--filter=blob:none", "origin", source["commit"])
    git(output, "checkout", "--detach", source["commit"])
    upstream.require(git(output, "rev-parse", "HEAD^{tree}").decode().strip() == source["tree"], "candidate-tree-mismatch")
    before = version_literals((output / "smarttubetv/build.gradle").read_text())
    git(output, "apply", "--3way", "--index", "--", str(delta_dir / "enhancement.patch"))
    upstream.require(version_literals((output / "smarttubetv/build.gradle").read_text()) == before,
                     "enhancement-overwrote-upstream-version")
    for name in ("SharedModules", "MediaServiceCore"):
        upstream.require(not (output.parent / name).exists(), "neighbor-project-shadow")
    locked = lock_submodules(output, reference_root=repo) if initialize_submodules else None
    tree = git(output, "write-tree").decode().strip()
    return {"integratedTree": tree, "submodules": locked, "sourceVersionCode": before[0],
            "sourceVersionName": before[1], "submodulesVerified": initialize_submodules}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    export.add_argument("--repo", type=Path, default=Path.cwd())
    export.add_argument("--base", required=True)
    export.add_argument("--output-dir", type=Path, required=True)
    export.add_argument("--include-worktree", action="store_true")
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--repo", type=Path, default=Path.cwd())
    prepare_parser.add_argument("--lock", type=Path, required=True)
    prepare_parser.add_argument("--delta-dir", type=Path, required=True)
    prepare_parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "export":
            result = export_delta(args.repo, args.base, args.output_dir, args.include_worktree)
        else:
            result = prepare(args.repo, json.loads(args.lock.read_text()), args.delta_dir, args.output_dir)
        print(json.dumps(result, indent=2, sort_keys=True))
    except (upstream.InvalidRelease, OSError, ValueError, KeyError) as error:
        code = str(error) if isinstance(error, upstream.InvalidRelease) else "invalid-integration-input"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
