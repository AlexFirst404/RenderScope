"""Prepare eight official modern Fabric profiles; --launch alone runs worlds serially.

This does not replace the working development-client runner. A preparation pass
is not runtime evidence. Never invoke --launch without the team's GPU handover.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
import importlib.util
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def installed_output(target):
    """Keep the launcher output and the shared publication evidence path aligned."""
    relative = f"modern/validation/installed/{target['minecraft']}/installed-verification.json"
    if relative not in target.get("runtimeEvidence", []):
        raise RuntimeError("Installed Fabric evidence is absent from the shared target manifest: " + relative)
    return (ROOT / relative).parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", nargs="+")
    parser.add_argument("--launch", action="store_true")
    parser.add_argument("--java21", help="Optional JDK21 home; otherwise use the shared verified build helper's discovery")
    parser.add_argument("--java25", help="Optional JDK25 home")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("modern_installed_builder", ROOT / "scripts/build-all.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    java_homes = {21: builder.java_home(21, args.java21), 25: builder.java_home(25, args.java25)}
    targets = json.loads((ROOT / "modern/targets.json").read_text(encoding="utf-8"))["targets"]
    dependencies = {row["minecraft"]: row["version"] for row in json.loads(
        (ROOT / "distribution/platforms/0.3.0/fabric-api-dependencies.json").read_text(encoding="utf-8"))["targets"]}
    if args.minecraft:
        if set(args.minecraft) - {target["minecraft"] for target in targets}:
            parser.error("Requested Minecraft version is outside the exact eight supported targets")
        targets = sorted([t for t in targets if t["minecraft"] in args.minecraft], key=lambda t: args.minecraft.index(t["minecraft"]))
    rows = []
    mode = "world" if args.launch else "prepare"

    def run(target):
        mc = target["minecraft"]
        primary = ROOT / target["artifact"]
        harness = primary.with_name(primary.stem + "-installed-smoke-harness.jar")
        java = java_homes[25 if mc.startswith("26.") else 21]
        output = installed_output(target)
        log = ROOT / f".tools/modern-fabric-installed-{mode}-{mc}.log"
        command = [sys.executable, str(ROOT / "scripts/test-fabric-client.py"), "--minecraft", mc,
            "--loader", target["loaderVersion"], "--artifact", str(primary), "--harness", str(harness),
            "--modern-smoke", "--fabric-api-version", dependencies[mc], "--java", str(java),
            "--game-dir", str(ROOT / ("run-modern-installed-" + mc)), "--output", str(output)]
        if args.launch:
            command.append("--launch")
        print(f"{mode}: Fabric {mc}", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        proof = {"minecraft": mc, "success": result.returncode == 0, "mode": mode,
                 "log": log.relative_to(ROOT).as_posix(), "completedAt": datetime.now(timezone.utc).isoformat()}
        if result.returncode == 0 and args.launch:
            proof["verification"] = json.loads((output / "installed-verification.json").read_text(encoding="utf-8"))
        print(f"Fabric {mc}: {'PASS' if proof['success'] else 'FAIL'}", flush=True)
        return proof

    def save():
        output = ROOT / "modern/validation/installed" / ("runtime-batch.json" if args.launch else "preparation-batch.json")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes((json.dumps({"scope": "installed production worlds" if args.launch else "profile preparation only; no game launched", "targets": rows}, indent=2) + "\n").encode("utf-8"))

    if args.launch:
        for target in targets:
            proof = run(target)
            rows.append(proof)
            save()
            if not proof["success"]:
                raise SystemExit(1)
    else:
        with ThreadPoolExecutor(max_workers=2) as executor:
            for future in as_completed([executor.submit(run, target) for target in targets]):
                rows.append(future.result())
                save()
        if any(not row["success"] for row in rows):
            raise SystemExit(1)


if __name__ == "__main__":
    main()
