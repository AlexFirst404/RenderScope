"""Prepare native production profiles in parallel, or explicitly test them serially.

Preparation never starts Minecraft. --launch always uses exactly one client at a
time and stops at the first failure so a real compatibility bug can be repaired.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", action="append", type=Path)
    parser.add_argument("--minecraft", nargs="+")
    parser.add_argument("--loader", nargs="+", choices=["forge", "neoforge"])
    parser.add_argument("--exclude", nargs="*", default=[], help="Explicit loader:minecraft identities to leave to another QA batch")
    parser.add_argument("--java")
    parser.add_argument("--launch", action="store_true")
    parser.add_argument("--workers", type=int, choices=[1, 2, 3], default=2)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    manifests = args.manifest or [ROOT / "loader21/targets.json", ROOT / "docs/compatibility/build-targets.json"]
    selected = {}
    for manifest in manifests:
        for target in json.loads(manifest.read_text(encoding="utf-8"))["targets"]:
            key = target["loader"] + ":" + target["minecraft"]
            if target["loader"] not in ("forge", "neoforge") or key in args.exclude:
                continue
            if args.minecraft and target["minecraft"] not in args.minecraft:
                continue
            if args.loader and target["loader"] not in args.loader:
                continue
            selected[key] = target
    entries = list(selected.values())
    if args.minecraft:
        entries.sort(key=lambda value: args.minecraft.index(value["minecraft"]))
    if not entries:
        parser.error("No native targets match the selected manifests")
    mode = "world" if args.launch else "prepare"
    results = []
    report = ROOT / ".tools" / ("native-matrix-" + mode + ".json")

    def run(target: dict) -> dict:
        mc, loader = target["minecraft"], target["loader"]
        primary = ROOT / target["artifact"]
        harness = ROOT / target["harnessArtifact"] if target.get("harnessArtifact") else primary.with_name(primary.stem + "-smoke-harness.jar")
        output = ROOT / f"distribution/{loader}/{mc}/installed"
        log = ROOT / f".tools/native-{mode}-{loader}-{mc}.log"
        command = [sys.executable, str(ROOT / "scripts/test-native-client.py"), "--minecraft", mc,
            "--loader", loader, "--loader-version", target["loaderVersion"], "--artifact", str(primary),
            "--harness", str(harness), "--game-dir", str(ROOT / f"run-native-{loader}-{mc}"), "--output", str(output),
            "--timeout", str(args.timeout)]
        if args.java:
            command += ["--java", args.java]
        if args.launch:
            command += ["--launch"]
        print(f"{mode}: {loader} Minecraft {mc}", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        result = {"loader": loader, "minecraft": mc, "success": completed.returncode == 0,
                  "mode": mode, "log": str(log.relative_to(ROOT)), "completedAt": datetime.now(timezone.utc).isoformat()}
        if completed.returncode == 0 and args.launch:
            result["verification"] = json.loads((output / "installed-verification.json").read_text())
        print(f"{loader} {mc}: {'PASS' if result['success'] else 'FAIL'} ({result['log']})", flush=True)
        return result

    def save() -> None:
        report.write_text(json.dumps({"mode": mode, "targets": results}, indent=2) + "\n", encoding="utf-8")

    if args.launch:
        for target in entries:
            result = run(target); results.append(result); save()
            if not result["success"]:
                raise SystemExit(1)
    else:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            pending = [pool.submit(run, target) for target in entries]
            for completed in as_completed(pending):
                results.append(completed.result()); save()
        if any(not result["success"] for result in results):
            raise SystemExit(1)


if __name__ == "__main__":
    main()
