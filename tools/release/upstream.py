#!/usr/bin/env python3
"""Validate upstream release snapshots without building or publishing anything."""
import argparse
import hashlib
import json
from pathlib import Path
import re

REPOSITORY = "yuliskov/SmartTube"
TAG = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(s?)", re.ASCII)
SHA = re.compile(r"[0-9a-f]{40}", re.ASCII)


class InvalidRelease(ValueError):
    """A safe error code; never interpolate remote content into diagnostics."""


def require(condition, code):
    if not condition:
        raise InvalidRelease(code)


def positive_int(value):
    return type(value) is int and value > 0


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def release_identity(release):
    require(type(release) is dict, "invalid-release-object")
    require(type(release.get("draft")) is bool, "invalid-draft-state")
    if release["draft"]:
        return None
    name = release.get("tag_name")
    require(type(name) is str and len(name) <= 80, "invalid-tag-name")
    match = TAG.fullmatch(name)
    if match is None:
        return None  # Aggregates such as 'latest' are not versioned releases.
    require(type(release.get("prerelease")) is bool, "invalid-prerelease-state")
    channel = "stable" if match[3] else "beta"
    require(positive_int(release.get("id")), "invalid-release-id")
    return channel, (int(match[1]), int(match[2])), name.removesuffix("s")


def selected_release(release, identity):
    channel, _, version = identity
    # Historical releases used different flags. Validate the current candidate,
    # not every superseded record, and never fall back from a conflicting latest.
    require(release["prerelease"] == (channel == "beta"), "channel-tag-mismatch")
    # We build from source. Upstream APK availability and mutable release notes
    # are not source inputs or prerequisites for a downstream build.
    return {"repository": REPOSITORY, "releaseId": release["id"],
            "channel": channel, "tag": release["tag_name"], "versionName": version}


def discover(pages, page_size=100):
    """Require a complete paginated snapshot; do not silently fall back on an older release."""
    require(type(page_size) is int and 1 <= page_size <= 100, "invalid-page-size")
    require(type(pages) is list and len(pages) > 0, "missing-pages")
    selected = {}
    ids, tags = set(), set()
    for ordinal, page in enumerate(pages):
        require(type(page) is list and len(page) <= page_size, "invalid-page")
        if ordinal < len(pages) - 1:
            require(len(page) == page_size, "page-after-terminal-page")
        else:
            require(len(page) < page_size, "incomplete-pagination")
        for release in page:
            identity = release_identity(release)
            if identity is None:
                continue
            require(release["id"] not in ids, "duplicate-release-id")
            require(release["tag_name"] not in tags, "duplicate-release-tag")
            ids.add(release["id"])
            tags.add(release["tag_name"])
            channel, version, _ = identity
            if channel not in selected or version > selected[channel][1][1]:
                selected[channel] = release, identity
    require(set(selected) == {"stable", "beta"}, "missing-channel")
    return {channel: selected_release(*selected[channel]) for channel in ("stable", "beta")}


def pin_source(release, ref, tag_objects, commit, tree):
    """Pin the root source and gitlinks, not a recursive build or a release approval."""
    require(type(release) is dict and release.get("repository") == REPOSITORY, "wrong-repository")
    identity = release_identity({"id": release.get("releaseId"), "tag_name": release.get("tag"),
                                 "draft": False, "prerelease": release.get("channel") == "beta"})
    require(identity is not None and identity[0] == release.get("channel"), "invalid-candidate")
    require(set(release) == {"repository", "releaseId", "channel", "tag", "versionName"}
            and release["versionName"] == identity[2], "invalid-candidate-fields")
    require(type(ref) is dict and ref.get("ref") == "refs/tags/" + release["tag"], "wrong-tag-ref")
    require(type(tag_objects) is list and len(tag_objects) <= 8, "invalid-tag-chain")
    obj = ref.get("object")
    seen = set()
    for tag in tag_objects:
        require(type(obj) is dict and obj.get("type") == "tag", "unexpected-tag-object")
        require(type(obj.get("sha")) is str and SHA.fullmatch(obj["sha"]) is not None,
                "invalid-tag-sha")
        require(obj["sha"] not in seen, "cyclic-tag-chain")
        seen.add(obj["sha"])
        require(type(tag) is dict and tag.get("sha") == obj["sha"], "tag-object-mismatch")
        obj = tag.get("object")
    require(type(obj) is dict and obj.get("type") == "commit", "unresolved-tag")
    sha = obj.get("sha")
    require(type(sha) is str and SHA.fullmatch(sha) is not None, "invalid-commit-sha")
    require(type(commit) is dict and commit.get("sha") == sha, "commit-mismatch")
    commit_tree = commit.get("tree")
    require(type(commit_tree) is dict, "invalid-commit-tree")
    tree_sha = commit_tree.get("sha")
    require(type(tree_sha) is str and SHA.fullmatch(tree_sha) is not None, "invalid-tree-sha")
    require(type(tree) is dict and tree.get("sha") == tree_sha, "tree-mismatch")
    require(tree.get("truncated") is False, "incomplete-tree")
    entries = tree.get("tree")
    require(type(entries) is list, "invalid-tree")
    links = {}
    paths = set()
    for entry in entries:
        require(type(entry) is dict, "invalid-tree-entry")
        path = entry.get("path")
        require(type(path) is str and path not in paths, "duplicate-or-invalid-tree-path")
        paths.add(path)
        if entry.get("mode") != "160000":
            continue
        require(entry.get("type") == "commit", "invalid-gitlink-type")
        link = entry.get("sha")
        require(type(link) is str and SHA.fullmatch(link) is not None, "invalid-gitlink-sha")
        require(path and "/" not in path and "\\" not in path and path not in (".", ".."),
                "invalid-root-gitlink-path")
        links[path] = link
    require({"MediaServiceCore", "SharedModules"} <= set(links), "missing-required-gitlink")
    source = {"release": dict(release), "commit": sha, "tree": tree_sha,
              "rootGitlinks": dict(sorted(links.items()))}
    return {"schemaVersion": 1, "scope": "upstream-root-source-only", "releaseReady": False,
            "source": source, "sourceFingerprint": fingerprint(source)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--page", action="append", type=Path, required=True,
                        help="Complete GitHub release pages in order, including a final short/empty page")
    parser.add_argument("--page-size", type=int, default=100)
    args = parser.parse_args()
    try:
        pages = [json.loads(path.read_text(encoding="utf-8")) for path in args.page]
        print(json.dumps(discover(pages, args.page_size), indent=2, sort_keys=True))
    except (InvalidRelease, OSError, ValueError) as error:
        code = str(error) if isinstance(error, InvalidRelease) else "invalid-snapshot"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
