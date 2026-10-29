#!/usr/bin/env python3
"""Updates the extension repository (the `repo` branch) with freshly built release APKs.

Modelled on Keiyoushi's publish step: the branch holds one APK per extension under apk/ and an index
that the website (and, later, the app) reads:

    apk/<package>-v<versionName>.apk
    index.json       pretty, for people
    index.min.json   the same, minified, for programs

An APK is only replaced when its versionCode changes, so rebuilding unchanged sources never offers
users an "update". Extensions that are no longer built are removed.

    publish-repo.py --repo <checkout of the repo branch> --signer <sha-256> [--aapt2 <path>] <apk>...
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

FEATURE = "dev.routerhub.extension"
API_KEY = "dev.routerhub.extension.api"
LABEL_PREFIX = "RouterHub: "


def find_aapt2() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("Set ANDROID_HOME or pass --aapt2")
    tools = sorted(Path(sdk, "build-tools").iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    name = "aapt2.exe" if os.name == "nt" else "aapt2"
    return str(tools[-1] / name)


def run(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True, encoding="utf-8").stdout


def inspect(aapt2: str, apk: Path) -> dict:
    badging = run(aapt2, "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    label = re.search(r"^application: label='([^']*)'", badging, re.M)
    if not package or not label:
        sys.exit(f"{apk}: aapt2 could not read the package or label")
    if f"uses-feature-not-required: name='{FEATURE}'" not in badging and f"uses-feature: name='{FEATURE}'" not in badging:
        sys.exit(f"{apk}: does not declare the {FEATURE} feature, so the app would not find it")

    # Meta-data is not part of badging; read it from the manifest tree. The value is an integer
    # (=3), or a string (="3") if the manifest quotes it in a way aapt2 keeps.
    tree = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk))
    api = re.search(
        r'A: \S*android:name\(0x[0-9a-f]+\)="' + re.escape(API_KEY) + r'"[^\n]*\n\s*A: \S*android:value\(0x[0-9a-f]+\)="?(\d+)',
        tree,
    )
    if not api:
        sys.exit(f"{apk}: no {API_KEY} meta-data")

    name, version_code, version_name = package.groups()
    return {
        "name": label.group(1).removeprefix(LABEL_PREFIX),
        "packageName": name,
        "versionCode": int(version_code),
        "versionName": version_name,
        "apiVersion": int(api.group(1)),
    }


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--signer", required=True, help="SHA-256 of the certificate the APKs are signed with")
    parser.add_argument("--aapt2")
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    aapt2 = args.aapt2 or find_aapt2()

    apk_dir = args.repo / "apk"
    apk_dir.mkdir(parents=True, exist_ok=True)
    old_index = args.repo / "index.json"
    previous = {}
    if old_index.exists():
        previous = {e["packageName"]: e for e in json.loads(old_index.read_text(encoding="utf-8"))["extensions"]}

    entries = []
    for apk in args.apks:
        info = inspect(aapt2, apk)
        target = apk_dir / f"{info['packageName']}-v{info['versionName']}.apk"
        old = previous.get(info["packageName"])
        if old and old["versionCode"] == info["versionCode"] and (args.repo / old["apk"]).exists():
            print(f"= {info['packageName']} {info['versionName']} (versionCode {info['versionCode']} unchanged)")
            entries.append(old)
            continue
        if old and old["versionCode"] > info["versionCode"]:
            sys.exit(f"{info['packageName']}: versionCode went down from {old['versionCode']} to {info['versionCode']}")
        if old:
            (args.repo / old["apk"]).unlink(missing_ok=True)
        shutil.copyfile(apk, target)
        print(f"+ {info['packageName']} {info['versionName']} (versionCode {info['versionCode']})")
        entries.append({**info, "apk": f"apk/{target.name}", "size": target.stat().st_size, "sha256": sha256(target)})

    kept = {Path(e["apk"]).name for e in entries}
    for stale in apk_dir.glob("*.apk"):
        if stale.name not in kept:
            print(f"- {stale.name}")
            stale.unlink()

    entries.sort(key=lambda e: e["name"].lower())
    index = {
        "name": "RouterHub extensions",
        "signingKey": args.signer,
        "extensions": entries,
    }
    old_index.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (args.repo / "index.min.json").write_text(json.dumps(index, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
