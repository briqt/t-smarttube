#!/usr/bin/env python3
"""Inspect actual APKs and optionally sign them outside the Gradle build runner."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import integration
import publish
import upstream


def tools_dir():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    upstream.require(bool(sdk), "android-sdk-not-configured")
    tools = Path(sdk) / "build-tools" / "30.0.3"
    upstream.require(all((tools / name).is_file() for name in ("aapt", "apksigner")), "android-tools-missing")
    return tools


def resource_blocks(text, name):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.lstrip().startswith("resource ") and ":" + name + ":" in line:
            indent = len(line) - len(line.lstrip())
            block = [line]
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= indent:
                    break
                block.append(following)
            yield "\n".join(block)


def inspect_apk(plan, path, abi, certificate=None, tools=None):
    integration.validate_plan(plan)
    tools = tools or tools_dir()
    path = Path(path)
    upstream.require(path.is_file() and not path.is_symlink() and path.stat().st_size > 1000000, "invalid-apk-file")
    badging = integration.run([str(tools / "aapt"), "dump", "badging", str(path)]).decode()
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    upstream.require(package is not None and package[1] == plan["applicationId"]
                     and int(package[2]) == plan["versionCode"] and package[3] == plan["versionName"],
                     "apk-identity-mismatch")
    upstream.require("application-debuggable" not in badging and "testOnly" not in badging, "debug-or-test-apk")
    upstream.require("sdkVersion:'17'" in badging, "apk-minimum-sdk-changed")
    resources = integration.run([str(tools / "aapt"), "dump", "--values", "resources", str(path)]).decode()
    blocks = list(resource_blocks(resources, "array/update_urls"))
    expected_feed = "https://raw.githubusercontent.com/" + integration.REPOSITORY + "/updates/" + plan["channel"] + ".json"
    upstream.require(blocks, "apk-feed-missing")
    for block in blocks:
        values = re.findall(r'\(string(?:8|16)\) "([^"\n]*)"', block)
        upstream.require(values == [expected_feed] and "Count=1" in block, "apk-feed-mismatch")
    search_blocks = list(resource_blocks(resources, "xml/searchable"))
    upstream.require(len(search_blocks) == 1, "apk-search-resource-missing")
    search_path = re.search(r'\(string(?:8|16)\) "([^"\n]+)"', search_blocks[0])
    upstream.require(search_path is not None, "apk-search-path-missing")
    search = integration.run([str(tools / "aapt"), "dump", "xmltree", str(path), search_path[1]]).decode()
    string_values = {}
    for match in re.finditer(r'(?m)^[ \t]*resource (0x[0-9a-f]+) \S+:string/[^:]+:[^\n]*\n[ \t]*\(string(?:8|16)\) "([^"\n]*)"', resources):
        string_values[match[1]] = match[2]
    def attribute(name):
        lines = [line for line in search.splitlines() if "android:" + name + "(" in line]
        upstream.require(len(lines) == 1, "apk-search-attribute-missing")
        ref = re.search(r"=@(0x[0-9a-f]+)", lines[0])
        if ref:
            return string_values.get(ref[1])
        literal = re.search(r'="([^"]*)"', lines[0])
        return literal[1] if literal else None
    upstream.require(attribute("searchSuggestAuthority") == plan["applicationId"]
                     and attribute("searchSuggestIntentData") == "content://" + plan["applicationId"] + "/video",
                     "apk-search-identity-mismatch")
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            upstream.require(len(names) == len(set(names)), "duplicate-apk-entry")
            libraries = [name for name in names if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)]
            machines = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}
            for name in libraries:
                machine = machines.get(name.split("/")[1])
                upstream.require(machine is not None, "unsupported-native-abi")
                with archive.open(name) as library:
                    header = library.read(20)
                upstream.require(len(header) == 20 and header[:4] == b"\x7fELF" and header[4] == machine[0]
                                 and header[5] == 1 and int.from_bytes(header[18:20], "little") == machine[1],
                                 "apk-native-machine-mismatch")
            native = sorted({name.split("/")[1] for name in libraries})
            expected = {"armeabi-v7a", "arm64-v8a"} if abi == "universal" else {abi}
            upstream.require(expected <= set(native) and (abi == "universal" or set(native) == expected), "apk-abi-mismatch")
            upstream.require(all("assets/caption-pairing/" + name in names for name in ("index.html", "pair.js", "pair.css")),
                             "apk-enhancement-assets-missing")
    except (zipfile.BadZipFile, OSError):
        raise upstream.InvalidRelease("invalid-apk-archive") from None
    if certificate is not None:
        upstream.require(type(certificate) is str and integration.HEX256.fullmatch(certificate), "invalid-certificate-digest")
        output = integration.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(path)]).decode()
        signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", output, re.MULTILINE)
        upstream.require([value.lower() for value in signers] == [certificate], "apk-signer-mismatch")
        upstream.require("Verified using v1 scheme (JAR signing): true" in output, "apk-api17-signature-missing")
    return {"name": publish.asset_name(plan, abi), "size": path.stat().st_size,
            "sha256": integration.sha256(path), "nativeAbis": native}


def build_evidence(plan, directory, verify_unsigned=True):
    path = Path(directory) / "build-receipt.json"
    upstream.require(path.is_file() and not path.is_symlink(), "build-evidence-missing")
    evidence = publish.parse_json(path.read_bytes())
    upstream.require(type(evidence) is dict and evidence.get("schemaVersion") == 1
                     and evidence.get("candidateId") == plan["candidateId"] and evidence.get("lintPassed") is True,
                     "invalid-build-evidence")
    tests, integrated = evidence.get("tests"), evidence.get("integration")
    upstream.require(type(tests) is dict and type(tests.get("tests")) is int and tests["tests"] > 0
                     and all(type(tests.get(key)) is int and tests[key] == 0 for key in ("failures", "errors", "skipped"))
                     and type(tests.get("suites")) is list and tests["suites"], "tests-not-verified")
    upstream.require(type(integrated) is dict and integrated.get("submodulesVerified") is True
                     and integrated.get("sourceVersionCode") == plan["upstreamVersionCode"]
                     and integrated.get("sourceVersionName") == plan["upstream"]["release"]["versionName"],
                     "integration-not-verified")
    modules = integrated.get("submodules")
    upstream.require(type(modules) is dict and all(type(modules.get(name)) is dict
                     and modules[name].get("commit") == sha
                     for name, sha in plan["upstream"]["rootGitlinks"].items()), "root-gitlinks-not-verified")
    unsigned = publish.validate_assets(plan, evidence.get("unsignedArtifacts"))
    if verify_unsigned:
        for item in unsigned.values():
            apk = Path(directory) / item["name"]
            upstream.require(apk.is_file() and not apk.is_symlink() and apk.stat().st_size == item["size"]
                             and integration.sha256(apk) == item["sha256"], "unsigned-apk-receipt-mismatch")
    return evidence


def verify_directory(plan, directory, certificate=None):
    directory = Path(directory)
    expected = {publish.asset_name(plan, abi) for abi in publish.ABIS}
    upstream.require({path.name for path in directory.glob("*.apk")} == expected, "incomplete-or-extra-apk-set")
    assets = {abi: inspect_apk(plan, directory / publish.asset_name(plan, abi), abi, certificate)
              for abi in publish.ABIS}
    report = {"schemaVersion": 1, "verified": certificate is not None, "candidateId": plan["candidateId"],
              "applicationId": plan["applicationId"], "versionCode": plan["versionCode"],
              "versionName": plan["versionName"], "certificateSha256": certificate, "assets": assets}
    if certificate is not None:
        report["buildEvidence"] = build_evidence(plan, directory, verify_unsigned=False)
    return report


def sign_directory(plan, directory, output, certificate):
    integration.validate_plan(plan, publish=True)
    verify_directory(plan, directory)
    build_evidence(plan, directory)
    secret_names = ("TSMARTTUBE_SIGNING_KEY", "TSMARTTUBE_STORE_PASSWORD", "TSMARTTUBE_KEY_PASSWORD", "TSMARTTUBE_KEY_ALIAS")
    upstream.require(all(os.environ.get(name) for name in secret_names), "release-signing-not-configured")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    tools = tools_dir()
    with tempfile.TemporaryDirectory(prefix="tsmarttube-signing-") as temporary:
        key = Path(temporary) / "release.jks"
        try:
            encoded = "".join(os.environ["TSMARTTUBE_SIGNING_KEY"].split())
            data = base64.b64decode(encoded, validate=True)
            with key.open("xb") as file:
                os.chmod(key, 0o600)
                file.write(data)
        except (ValueError, OSError):
            raise upstream.InvalidRelease("invalid-release-signing-material") from None
        for abi in publish.ABIS:
            name = publish.asset_name(plan, abi)
            integration.run([str(tools / "apksigner"), "sign", "--ks", str(key),
                             "--ks-key-alias", os.environ["TSMARTTUBE_KEY_ALIAS"],
                             "--ks-pass", "env:TSMARTTUBE_STORE_PASSWORD",
                             "--key-pass", "env:TSMARTTUBE_KEY_PASSWORD", "--min-sdk-version", "17",
                             "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                             "--out", str(output / name), str(Path(directory) / name)])
    shutil.copyfile(Path(directory) / "build-receipt.json", output / "build-receipt.json")
    report = verify_directory(plan, output, certificate)
    integration.write_json(output / "artifacts.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--certificate-sha256")
    parser.add_argument("--sign-output", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        plan = publish.parse_json(args.plan.read_bytes())
        if args.sign_output:
            upstream.require(args.certificate_sha256 is not None, "expected-release-certificate-required")
            report = sign_directory(plan, args.apk_dir, args.sign_output, args.certificate_sha256.lower())
        else:
            report = verify_directory(plan, args.apk_dir,
                                      args.certificate_sha256.lower() if args.certificate_sha256 else None)
            if args.report:
                integration.write_json(args.report, report)
        print(json.dumps(report, indent=2, sort_keys=True))
    except (upstream.InvalidRelease, OSError, ValueError, KeyError) as error:
        code = str(error) if isinstance(error, upstream.InvalidRelease) else "invalid-artifact-input"
        parser.exit(1, code + "\n")


if __name__ == "__main__":
    main()
