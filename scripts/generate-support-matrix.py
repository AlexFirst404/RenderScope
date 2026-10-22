"""Report actual artifacts/tests and hash-matched runtime evidence; never infer support from metadata."""
from __future__ import annotations
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("build_all", ROOT / "scripts/build-all.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def inspect_runtime(entry: dict, sha256: str) -> list[dict]:
    development_scope = entry["loader"] + "-development-client"

    def matches_identity(record: dict, environment: str | None = None) -> bool:
        loader = record.get("loader")
        # The eight modern Fabric development harnesses report their actual
        # FabricLoader version and explicit scope without a separate loader key.
        omitted_fabric_development = ("loader" not in record and entry["loader"] == "fabric"
                                       and environment == "fabric-development-client")
        return (record.get("success") is True and record.get("minecraftVersion") == entry["minecraft"]
                and record.get("loaderVersion") == entry["loaderVersion"]
                and (omitted_fabric_development or isinstance(loader, str) and loader.lower() == entry["loader"]))

    def matches_hash(record: dict) -> bool:
        values = [record[key] for key in ("primaryJarSha256", "artifactSha256") if key in record]
        return bool(values) and all(value == sha256 for value in values)

    def is_world_result(record: dict) -> bool:
        environment = record.get("environment")
        return (environment in ("installed-production", development_scope)
                and matches_identity(record, environment) and matches_hash(record)
                and (environment != "installed-production" or record.get("primaryJarSha256") == sha256))

    accepted = []
    for relative in entry.get("runtimeEvidence", []):
        path = ROOT / relative
        if not path.is_file():
            continue
        evidence = json.loads(path.read_text(encoding="utf-8"))
        result_name = evidence.get("resultFile")
        if result_name:
            if (not isinstance(result_name, str) or not matches_identity(evidence, evidence.get("environment"))
                    or not matches_hash(evidence)):
                continue
            result = path.parent / result_name
            if Path(result_name).name != result_name or not result.is_file() or builder.digest(result) != evidence.get("resultSha256"):
                continue
            details = json.loads(result.read_text(encoding="utf-8"))
            if not is_world_result(details):
                continue
            environment = details["environment"]
            if "environment" in evidence and evidence["environment"] != environment:
                continue
        else:
            if not is_world_result(evidence):
                continue
            environment = evidence["environment"]
        accepted.append({"path": relative, "sha256": builder.digest(path), "environment": environment})
    return accepted


def main() -> None:
    catalogue = json.loads((ROOT / "docs/compatibility/requested-releases.json").read_text(encoding="utf-8"))
    entries = []
    for target in builder.targets():
        entry = {key: target[key] for key in ("minecraft", "loader", "loaderVersion", "java", "project", "artifact")}
        try:
            proof = builder.audit(target)
            entry.update(proof)
            packaged = ROOT / "distribution/0.3.0" / target["loader"] / Path(target["artifact"]).name
            if packaged.is_file() and builder.digest(packaged) == proof["sha256"]:
                entry["packagedArtifact"] = packaged.relative_to(ROOT).as_posix()
            entry["status"] = "artifact_and_unit_tests_verified"
            entry["runtimeEvidence"] = inspect_runtime(target, proof["sha256"])
            if entry["runtimeEvidence"]:
                entry["status"] = "world_tested"
        except Exception as error:
            entry["status"] = "pending_or_failed"
            entry["reason"] = str(error)
        entries.append(entry)
    by_id = {(entry["minecraft"], entry["loader"]): entry for entry in entries}
    output = ROOT / "docs/compatibility/support-matrix.json"
    output.write_text(json.dumps({"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
        "renderscopeVersion": "0.3.0", "stableMinecraftOnly": True,
        "meaning": "A built artifact and unit tests do not establish installed-client or optional-mod runtime compatibility. Runtime evidence must match the current artifact SHA-256.",
        "targets": entries}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# RenderScope 0.3.0: verified build matrix", "",
        "This table is generated from actual JARs, loader metadata, legal notices, bytecode, unit results and hash-matched runtime evidence. All Minecraft targets are stable releases. A loader marked beta remains a beta loader.", "",
        "**Built** means artifact and unit tests verified. **World** adds a real integrated-world test; its linked evidence explicitly distinguishes an installed production client from a development client. These checks do not claim that every optional mod or shader combination was tested.", "",
        "| Minecraft | Fabric | Forge | NeoForge |", "| --- | --- | --- | --- |"]
    for requested in catalogue["targets"]:
        version = requested["minecraft"]
        cells = [version]
        for loader in ("fabric", "forge", "neoforge"):
            entry = by_id.get((version, loader))
            if entry is None:
                cells.append("Upstream loader unavailable")
            elif entry["status"] == "pending_or_failed":
                cells.append("Pending")
            else:
                label = "World" if entry["status"] == "world_tested" else "Built"
                runtime = entry.get("runtimeEvidence", [])
                link = "../../" + (runtime[0]["path"] if runtime else entry.get("packagedArtifact", entry["artifact"]))
                cells.append(f"[{label}]({link}) · {entry['loaderVersion']}")
        lines.append("| " + " | ".join(cells) + " |")
    lines.extend(["", "## Measurement coverage", "",
        "Minecraft 1.20.1 has version-gated native Sodium/Embeddium/Rubidium vertex counters, Iris/Oculus shadow and buffer-source observation, supported Flywheel visual CPU work, and Mekanism deferred renderer attribution. Exact optional-mod combinations and limitations are recorded in [the integration notes](../OPTIONAL-RENDERER-COMPATIBILITY.md).", "",
        "Later Minecraft adapters observe real entity/block-entity rendering APIs, including extraction and submission where Minecraft separates them. Unavailable vertex/GPU metrics are shown as unavailable. Shared terrain, shader and instancing work is not fabricated or divided among mods.", "",
        "## Русский", "",
        "**Built** — проверены JAR, точная версия Minecraft, метаданные загрузчика, лицензия, версия байткода и unit-тесты. **World** — дополнительно пройден тест в настоящем игровом мире; в JSON указано, использован ли установленный JAR или среда разработки. Хеш доказательства должен совпадать с текущим артефактом. **Pending** — сборка ещё не готова либо проверка нашла ошибку.", "",
        "Проверка базового мира не означает проверку всех сочетаний Sodium/Iris/Create и других модов. Для таких сочетаний указаны отдельные точные версии и результаты.", "",
        "Regenerate: `python scripts/generate-support-matrix.py`.", ""])
    (ROOT / "docs/compatibility/SUPPORT-MATRIX.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Audited {len(entries)} configured targets; {sum(entry['status'] != 'pending_or_failed' for entry in entries)} built, {sum(entry['status'] == 'world_tested' for entry in entries)} hash-matched world tests.")


if __name__ == "__main__":
    main()
