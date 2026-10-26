#!/usr/bin/env python3
"""Read-only GitHub source discovery. No checkout, build, signing, or remote writes."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess

# Resolve the adjacent trusted implementation explicitly; also works under python -I.
_spec = importlib.util.spec_from_file_location("upstream", Path(__file__).with_name("upstream.py"))
upstream = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(upstream)


def github_get(path):
    try:
        result = subprocess.run(["gh", "api", "--hostname", "github.com", "--method", "GET",
                                 "repos/" + upstream.REPOSITORY + "/" + path],
                                capture_output=True, timeout=30, check=True)
        return json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError):
        raise upstream.InvalidRelease("github-read-failed") from None


def observe(fetch=github_get, page_limit=100):
    """Return observations, not an approval or an immutable GitHub-wide snapshot."""
    upstream.require(type(page_limit) is int and 1 <= page_limit <= 100, "invalid-page-limit")
    pages = []
    for page in range(1, page_limit + 1):
        response = fetch(f"releases?per_page=100&page={page}")
        upstream.require(type(response) is list and len(response) <= 100, "invalid-release-page")
        safe_page = []
        for item in response:
            upstream.require(type(item) is dict, "invalid-release-object")
            safe_page.append({key: item.get(key) for key in ("id", "tag_name", "draft", "prerelease")})
        pages.append(safe_page)
        if len(response) < 100:
            break
    else:
        raise upstream.InvalidRelease("pagination-limit-reached")
    candidates = upstream.discover(pages)
    observations = {}
    for channel, candidate in candidates.items():
        ref_path = "git/ref/tags/" + candidate["tag"]
        ref = fetch(ref_path)
        upstream.require(type(ref) is dict, "invalid-tag-ref")
        obj = ref.get("object")
        tags = []
        visited = set()
        while type(obj) is dict and obj.get("type") == "tag":
            sha = obj.get("sha")
            upstream.require(type(sha) is str and upstream.SHA.fullmatch(sha) is not None,
                             "invalid-tag-sha")
            upstream.require(sha not in visited and len(tags) < 8, "invalid-tag-chain")
            visited.add(sha)
            tag = fetch("git/tags/" + sha)
            upstream.require(type(tag) is dict and tag.get("sha") == sha, "tag-object-mismatch")
            tags.append(tag)
            obj = tag.get("object")
        upstream.require(type(obj) is dict and obj.get("type") == "commit", "unresolved-tag")
        sha = obj.get("sha")
        upstream.require(type(sha) is str and upstream.SHA.fullmatch(sha) is not None,
                         "invalid-commit-sha")
        commit = fetch("git/commits/" + sha)
        upstream.require(type(commit) is dict and commit.get("sha") == sha, "commit-mismatch")
        tree_ref = commit.get("tree")
        upstream.require(type(tree_ref) is dict, "invalid-commit-tree")
        tree_sha = tree_ref.get("sha")
        upstream.require(type(tree_sha) is str and upstream.SHA.fullmatch(tree_sha) is not None,
                         "invalid-tree-sha")
        tree = fetch("git/trees/" + tree_sha)
        lock = upstream.pin_source(candidate, ref, tags, commit, tree)
        # An observation can race an upstream retag. Reject changes already visible
        # here; the eventual release controller must recheck again before promotion.
        upstream.require(fetch(ref_path) == ref, "tag-moved-during-observation")
        observations[channel] = lock
    return {"schemaVersion": 1, "repository": upstream.REPOSITORY, "pages": pages,
            "channels": observations, "releaseReady": False,
            "scope": "upstream-root-source-only"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="A new directory for sanitized observations; never overwrites an old run")
    args = parser.parse_args()
    try:
        args.output_dir.mkdir(parents=True, exist_ok=False)
        observations = observe()
        with (args.output_dir / "observation.json").open("x", encoding="utf-8") as file:
            json.dump(observations, file, sort_keys=True, indent=2)
        print(json.dumps({"channels": observations["channels"], "releaseReady": False}, indent=2))
    except (upstream.InvalidRelease, OSError) as error:
        code = str(error) if isinstance(error, upstream.InvalidRelease) else "observation-output-failed"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
