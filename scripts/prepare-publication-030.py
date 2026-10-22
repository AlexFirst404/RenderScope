"""Prepare local 0.3.0 publication material from audited binaries and real evidence.

No network writes, Gradle invocation or platform upload is performed. Refresh after
the package index and gameplay evidence change; publicationReady is deliberately
false until every exact target has a matching world result and packaged copy.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "distribution/platforms/0.3.0"
REPO_URL = "https://github.com/AlexFirst404/RenderScope"
RAW_URL = "https://raw.githubusercontent.com/AlexFirst404/RenderScope/main/"
SOURCE_REF = "v0.3.0"
LOADER_NAMES = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}
API_PROJECT = "P7dR8mSH"
API_URL = f"https://api.modrinth.com/v2/project/{API_PROJECT}/version"


def module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def write(relative: str, text: str) -> None:
    text = text.replace(REPO_URL + "/blob/main/", REPO_URL + "/blob/" + SOURCE_REF + "/")
    text = text.replace(RAW_URL, "https://raw.githubusercontent.com/AlexFirst404/RenderScope/" + SOURCE_REF + "/")
    path = DESTINATION / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def fabric_pins() -> dict[str, str]:
    pins = {"1.20.1": "0.92.12+1.20.1"}
    if "fabric-api:" + pins["1.20.1"] not in (ROOT / "fabric/build.gradle").read_text(encoding="utf-8"):
        raise RuntimeError("The inspected Fabric 1.20.1 API pin changed")
    for relative in ("mc20/build.gradle", "mc21/build.gradle", "modern/build.gradle", "modern/mapped/build.gradle"):
        text = (ROOT / relative).read_text(encoding="utf-8")
        block = re.search(r"def (?:apiVersions|apis)\s*=\s*\[(.*?)\]", text, re.S)
        if not block:
            raise RuntimeError("Fabric API map is missing from " + relative)
        pins.update(re.findall(r"'([^']+)'\s*:\s*'([^']+)'", block[1]))
    return pins


def api_dependencies(pins: dict[str, str], cache: Path) -> list[dict]:
    if not cache.is_file():
        request = urllib.request.Request(API_URL, headers={"User-Agent": f"RenderScope-publication-preparation/0.3.0 ({REPO_URL})"})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read()
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
    versions = json.loads(cache.read_text(encoding="utf-8"))
    rows = []
    for minecraft, pin in pins.items():
        matches = [version for version in versions if version["version_number"] == pin
                   and version["project_id"] == API_PROJECT and "fabric" in version["loaders"]
                   and minecraft in version["game_versions"]]
        if len(matches) != 1:
            raise RuntimeError(f"Exact official Fabric API version is ambiguous or missing: {pin}")
        version = matches[0]
        file = next(file for file in version["files"] if file["primary"])
        rows.append({"minecraft": minecraft, "version": pin,
            "releaseType": version["version_type"],
            "maven": "net.fabricmc.fabric-api:fabric-api:" + pin,
            "modrinthProjectId": API_PROJECT, "modrinthVersionId": version["id"],
            "modrinthVersionUrl": f"https://modrinth.com/mod/fabric-api/version/{version['id']}",
            "curseforgeProjectId": 306612, "curseforgeSlug": "fabric-api",
            "curseforgeProjectSource": "https://www.curseforge.com/minecraft/mc-mods/fabric-api",
            "file": file["filename"], "fileUrl": file["url"], "sha512": file["hashes"]["sha512"],
            "source": f"https://api.modrinth.com/v2/version/{version['id']}"})
    return rows


def ownership_regressions(target: dict) -> dict:
    core_cases = {"sharedImplementationKeepsIndependentOwnerRowsAndModDetailLookup",
                  "rollingExpiryRemovesOnlyTheExpiredOwnersRendererAndIncludesZeroFrames",
                  "capturePreservesBothOwnersAfterRollingExpiryAndIncludesEmptyFrames"}
    modern = target["project"].startswith(("modern", "loader-mapped", "loader26"))
    exporter = "dev.renderscope.modern.ModernReportJsonTest" if modern else "dev.renderscope.client.report.ReportExporterTest"
    export_case = ("sharedRendererExportPreservesBothRegistryOwnersWithoutChangingClassIds" if modern
                   else "sharedRendererExportPreservesSeparateOwnerRowsAndUnchangedClassIds")
    proofs = []
    for suite_name, expected in (("dev.renderscope.core.RendererOwnershipTest", core_cases), (exporter, {export_case})):
        path = ROOT / target["testResults"] / ("TEST-" + suite_name + ".xml")
        if not path.is_file():
            return {"passed": False, "reason": "Missing current regression suite: " + suite_name, "suites": proofs}
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        names = {case.attrib.get("name", "").removesuffix("()") for case in cases}
        if (suite.attrib.get("name") != suite_name or int(suite.attrib.get("tests", 0)) != len(cases)
                or any(int(suite.attrib.get(key, 0)) != 0 for key in ("failures", "errors", "skipped"))
                or not expected.issubset(names)
                or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped"))):
            return {"passed": False, "reason": "Required owner/rolling/capture/export cases did not all pass: " + suite_name,
                    "suites": proofs}
        proofs.append({"path": path.relative_to(ROOT).as_posix(),
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "tests": len(cases), "requiredPassedCases": sorted(expected)})
    return {"passed": True, "suites": proofs}


def limitations(minecraft: str) -> tuple[str, str]:
    if minecraft == "1.20.1":
        return ("Entity/block-entity renderer CPU intervals. Version-gated optional integrations include supported Flywheel visual CPU tasks and attributed Mekanism deferred callbacks. Observed immediate-buffer geometry is partial. Shared Flywheel GPU timestamps, when available in Full, stay separate from objects and are not exported as capture GPU timings. Optional-mod support requires its own pinned combination evidence.",
                "CPU-интервалы рендереров сущностей и блок-сущностей. Версионные интеграции включают поддерживаемые visual CPU-задачи Flywheel и отложенные вызовы Mekanism. Геометрия стандартных буферов наблюдается частично. Общие GPU-метки Flywheel, если доступны в Full, отделены от объектов и не экспортируются как GPU-тайминги capture. Совместимость модов подтверждается отдельно для точного сочетания версий.")
    if minecraft in ("1.21.9", "1.21.10", "1.21.11") or minecraft.startswith("26."):
        return ("Exclusive CPU render-state extraction and submission, grouped by source. Later deferred feature batches, geometry and GPU timings are unavailable per object. Zero geometry fields do not mean an object emits no geometry. Terrain, shader execution and shared instancing work are not assigned to objects.",
                "Раздельные эксклюзивные CPU-интервалы извлечения render state и submission. Поздние отложенные feature batches, геометрия и GPU-тайминги отдельных объектов недоступны. Нулевые поля геометрии не означают отсутствия геометрии. Terrain, выполнение шейдеров и общий instancing не распределяются между объектами.")
    return ("Version-specific entity/block-entity CPU hooks, including extraction where available. Geometry is limited to observed standard buffers; newer Sodium/Iris native geometry is unavailable. GPU work, terrain, shader execution and shared instancing are not assigned to objects. A vanilla baseline does not establish optional-mod or shader compatibility.",
            "Версионные CPU-хуки сущностей и блок-сущностей, включая extraction там, где доступен. Геометрия ограничена наблюдаемыми стандартными буферами; native-геометрия новых Sodium/Iris недоступна. GPU, terrain, выполнение шейдеров и общий instancing не распределяются между объектами. Ванильный прогон не подтверждает совместимость со всеми модами и шейдерами.")


def gallery(rows: list[dict]) -> list[dict]:
    brand = ROOT / "distribution/brand/renderscope-cover-1600x900.png"
    assets = [{"kind": "brand", "path": brand.relative_to(ROOT).as_posix(),
               "sha256": hashlib.sha256(brand.read_bytes()).hexdigest(), "title": "RenderScope"}]
    slots = [("mods", "Measured mod table / Измеренная таблица модов"),
             ("heatmap", "World heatmap / Теплокарта в мире"),
             ("selected", "A selected real instance / Выбранный экземпляр мира")]
    for keyword, title in slots:
        selected = None
        for row in rows:
            if not row["rendererOwnerIsolationPresent"]:
                continue
            for evidence in row["runtimeEvidence"]:
                record_path = ROOT / evidence["path"]
                record = json.loads(record_path.read_text(encoding="utf-8"))
                details_path = record_path.parent / record["resultFile"] if record.get("resultFile") else record_path
                details = json.loads(details_path.read_text(encoding="utf-8"))
                for name in details.get("screenshots", []):
                    if not isinstance(name, str) or keyword not in name.lower():
                        continue
                    image = (details_path.parent / name).resolve()
                    if not image.is_relative_to(details_path.parent.resolve()) or not image.is_file():
                        continue
                    selected = {"kind": "current-hash gameplay", "path": image.relative_to(ROOT).as_posix(),
                        "sha256": hashlib.sha256(image.read_bytes()).hexdigest(), "title": title,
                        "minecraft": row["minecraft"], "loader": row["loader"], "artifactSha256": row["sha256"],
                        "runtimeScope": evidence["environment"], "runtimeEvidence": evidence["path"],
                        "runtimeEvidenceSha256": evidence["sha256"]}
                    break
                if selected:
                    break
            if selected:
                break
        if selected:
            assets.append(selected)
    return assets


EN = """# RenderScope 0.3.0

![RenderScope](https://raw.githubusercontent.com/AlexFirst404/RenderScope/main/distribution/brand/renderscope-cover-1600x900.png)

**Find the object behind measured render CPU work.**

RenderScope is a client-side profiler for **23 stable Minecraft Java releases from 1.20.1 through 26.3**, with **67 separate Fabric / Forge / NeoForge targets**. Choose one JAR matching your exact Minecraft version and loader. Forge has no upstream release for 1.20.5 or 1.21.2. The server does not need RenderScope.

Open **F8** or **Esc → RenderScope**. The first page names the mod with the most measured object-render CPU work, marks preliminary data, and helps you reach its heaviest measured instance. Inspect mod/type/instance tables, use Highlight/Track, compare nearby objects in the heatmap, and inspect the object under your crosshair. Record 5/10/30 seconds or manually, then export local JSON.

**Minecraft 26.x needs Java 25; earlier RenderScope targets use Java 21. Fabric also requires the matching Fabric API.** Exact build-pinned loader/API versions are listed in the [download and dependency table](https://github.com/AlexFirst404/RenderScope/blob/main/distribution/platforms/0.3.0/DEPENDENCIES.md). Some pinned upstream loader/API builds are beta; their versions and status are retained. Do not install builds for different loaders together.

The 1.20.1–1.21.8 UI uses **Start**, **Find object**, **Show in world** and **Record 10 s**. The 1.21.9–26.3 UI uses **Overview**, **Show the instance**, **Inspect measured mods** and its Capture tab. English and Russian are included.

**Read the scope before blaming a mod.** CPU intervals are not GPU time or a promised FPS gain from removing a mod. Shared work and parallel visual tasks can overlap. Terrain, particles, GUI, shader execution and unobserved deferred/instanced geometry are outside per-object totals. The render-state adapters for 1.21.9–26.3 measure extraction/submission CPU and explicitly mark geometry/GPU timings unavailable. Full mode only adds metrics the selected adapter can actually observe.

Minecraft 1.20.1 retains version-gated Flywheel visual CPU and Mekanism deferred integrations. Shared Flywheel GPU timestamps are separate, asynchronous, supported-context diagnostics; they are not divided among mechanisms. Optional Sodium/Iris/Embeddium/Rubidium/Oculus/Create/Mekanism combinations need their own [exact evidence](https://github.com/AlexFirst404/RenderScope/blob/main/docs/OPTIONAL-RENDERER-COMPATIBILITY.md). No newer-version compatibility is implied by a 1.20.1 screenshot or test.

Reports remain in the profile's `renderscope-reports` folder. Positions and dimensions are off by default. Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded; nothing is automatically uploaded.

[Current build/world evidence](https://github.com/AlexFirst404/RenderScope/blob/main/docs/compatibility/SUPPORT-MATRIX.md) distinguishes **installed production clients** from **development clients**, with exact artifact hashes. A build pass alone is not a world or shader compatibility pass.

Source is available under **RenderScope Source-Available License 1.0**. Personal/private tool use, modification and free inclusion/redistribution are allowed under its terms. Selling the software, including it in paid projects/modpacks/bundles, or charging for access needs prior written permission. [Source and full license](https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE).
"""

RU = """# RenderScope 0.3.0

**Найдите конкретный объект с дорогой измеренной CPU-работой отрисовки.**

Клиентский профилировщик для **23 стабильных версий Minecraft Java от 1.20.1 до 26.3**: подготовлены **67 отдельных целей Fabric / Forge / NeoForge**. Выбирайте один JAR строго под свою версию игры и загрузчик. У Forge нет upstream-релизов для 1.20.5 и 1.21.2. На сервер мод не нужен.

Откройте **F8** или **Esc → RenderScope**. Первая страница показывает лидера измеренной CPU-работы объектов, отмечает предварительные данные и помогает найти самый дорогой экземпляр. Доступны таблицы модов/типов/экземпляров, Highlight/Track, теплокарта и инспектор под прицелом. Capture записывает 5/10/30 секунд либо до ручной остановки, затем сохраняет JSON локально.

**Для Minecraft 26.x нужна Java 25, для предыдущих целей RenderScope — Java 21. Fabric требует подходящий Fabric API.** [Точные загрузчики и Fabric API](https://github.com/AlexFirst404/RenderScope/blob/main/distribution/platforms/0.3.0/DEPENDENCIES.md) указаны отдельно для каждой версии; beta-статус upstream-сборок не скрывается. Не устанавливайте JAR разных загрузчиков вместе.

В интерфейсе 1.20.1–1.21.8 используются **Главная**, **Найти объект**, **Показать в мире**, **Записать 10 с**. В 1.21.9–26.3 — **Обзор**, **Показать объект**, **Изучить моды** и вкладка записи. Есть русский и английский языки.

**CPU-измерение не является GPU-временем или обещанием прироста FPS после удаления мода.** Общая работа и параллельные visual-задачи могут пересекаться. Terrain, particles, GUI, выполнение шейдеров и ненаблюдаемая deferred/instanced-геометрия не входят в сумму отдельных объектов. Адаптеры 1.21.9–26.3 измеряют CPU extraction/submission; геометрия и GPU-тайминги явно недоступны. Full добавляет только реально доступные выбранному адаптеру метрики.

Для 1.20.1 сохранены версионные интеграции visual CPU-задач Flywheel и отложенных вызовов Mekanism. Общие асинхронные GPU-метки Flywheel отделены от механизмов. Совместимость с Sodium/Iris/Embeddium/Rubidium/Oculus/Create/Mekanism подтверждается только для [точных сочетаний](https://github.com/AlexFirst404/RenderScope/blob/main/docs/OPTIONAL-RENDERER-COMPATIBILITY.md), а не автоматически для всех версий игры.

Отчёты остаются в `renderscope-reports` внутри профиля. Координаты и dimension выключены по умолчанию; имена игроков, адреса серверов, имена миров, чат, UUID и абсолютные пути исключены. Автоматической отправки нет.

[Матрица проверок](https://github.com/AlexFirst404/RenderScope/blob/main/docs/compatibility/SUPPORT-MATRIX.md) различает установленный production-клиент и среду разработки и связывает доказательства с точным хешем JAR. Успешная сборка не считается игровым или shader-тестом.

Собственная **RenderScope Source-Available License 1.0** разрешает личное/приватное использование как инструмента, изменения и бесплатное включение/распространение по её условиям. Продажа, включение в платные проекты/модпаки/пакеты и платный доступ требуют предварительного письменного разрешения. [Исходники и полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE).
"""


def main() -> None:
    global SOURCE_REF
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("alpha", "beta", "release"), default="release")
    parser.add_argument("--api-cache", type=Path, default=ROOT / ".tools/fabric-api-official-versions.json")
    parser.add_argument("--source-ref", default="v0.3.0", help="Public release tag/ref used by prepared text links")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.source_ref):
        raise RuntimeError("Unsafe source ref")
    SOURCE_REF = args.source_ref
    builder = module("publication_build", "scripts/build-all.py")
    support = module("publication_support", "scripts/generate-support-matrix.py")
    targets = builder.targets()
    if len(targets) != 67 or len({target["minecraft"] for target in targets}) != 23:
        raise RuntimeError("Expected exactly 67 loader targets across 23 stable Minecraft releases")
    pins = fabric_pins()
    if set(pins) != {target["minecraft"] for target in targets if target["loader"] == "fabric"}:
        raise RuntimeError("Fabric API pins do not cover the exact release set")
    dependencies = api_dependencies(pins, args.api_cache)
    by_api = {row["minecraft"]: row for row in dependencies}
    package_index = json.loads((ROOT / "distribution/0.3.0/artifacts.json").read_text(encoding="utf-8"))
    packages = {(row["minecraft"], row["loader"]): row for row in package_index["artifacts"]}
    rows = []
    for target in targets:
        proof = builder.audit(target)
        source = ROOT / proof["artifact"]
        with zipfile.ZipFile(source) as jar:
            owner_isolation = "dev/renderscope/core/RenderProfiler$RendererKey.class" in jar.namelist()
        package = packages.get((target["minecraft"], target["loader"]), {})
        packaged = ROOT / package.get("packagedArtifact", "missing")
        synchronized = (package.get("sha256") == proof["sha256"] and package.get("bytes") == proof["bytes"]
                        and packaged.is_file() and builder.digest(packaged) == proof["sha256"])
        evidence = support.inspect_runtime(target, proof["sha256"])
        regressions = ownership_regressions(target)
        en, ru = limitations(target["minecraft"])
        dependency = by_api.get(target["minecraft"]) if target["loader"] == "fabric" else None
        changelog = f"changelogs/{target['loader']}-{target['minecraft']}.md"
        status_en = "Current hash-matched world evidence: " + ", ".join(sorted({row["environment"] for row in evidence})) if evidence else "World verification is pending for this exact artifact hash."
        status_ru = "Для текущего хеша есть игровой результат: " + ", ".join(sorted({row["environment"] for row in evidence})) if evidence else "Игровая проверка именно этого хеша ещё не завершена."
        java = 25 if target["minecraft"].startswith("26.") else 21
        write(changelog, f"## RenderScope 0.3.0 — {LOADER_NAMES[target['loader']]} / Minecraft {target['minecraft']}\n\n"
              f"- Client only; {target['loader']} {target['loaderVersion']}, Java {java}. No server installation.\n"
              + (f"- Required Fabric API: **{dependency['version']}**.\n" if dependency else "")
              + "- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.\n"
              "- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.\n"
              "- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.\n\n"
              f"Measurement scope: {en}\n\nValidation: **{proof['unitTests']} unit tests**; {status_en}\n"
              + "\n".join(f"- [Evidence]({REPO_URL}/blob/main/{row['path']})" for row in evidence)
              + f"\n\nSHA-256: `{proof['sha256']}` ({proof['bytes']} bytes).\n\n---\n\n## Русский\n\n"
              f"Клиентская сборка для **Minecraft {target['minecraft']} / {target['loader']} {target['loaderVersion']} / Java {java}**. На сервер мод не нужен.\n\n"
              + (f"Обязателен Fabric API **{dependency['version']}**.\n\n" if dependency else "")
              + "Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. CPU-доли не обещают прирост FPS.\n\n"
              + f"Область измерения: {ru}\n\nПроверено unit-тестов: **{proof['unitTests']}**. {status_ru}\n\n"
              + f"RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия]({REPO_URL}/blob/main/LICENSE).")
        rows.append({**proof, "fileName": source.name, "sha1": hashlib.sha1(source.read_bytes()).hexdigest(),
            "sha512": hashlib.sha512(source.read_bytes()).hexdigest(), "versionNumber": "0.3.0+mc" + target["minecraft"],
            "displayName": f"RenderScope 0.3.0 · {LOADER_NAMES[target['loader']]} {target['minecraft']}",
            "javaRuntime": java, "environment": "client", "clientSide": "required", "serverSide": "unsupported",
            "releaseChannel": args.channel, "packagedArtifact": package.get("packagedArtifact"),
            "packageIndexMatches": synchronized, "rendererOwnerIsolationPresent": owner_isolation,
            "rendererOwnerRegressions": regressions,
            "runtimeEvidence": evidence, "worldVerified": bool(evidence),
            "changelog": "distribution/platforms/0.3.0/" + changelog,
            "measurementScope": {"en": en, "ru": ru}, "fabricApi": dependency,
            "modrinth": {"projectId": "LyIIxCef", "gameVersions": [target["minecraft"]], "loaders": [target["loader"]],
                "versionType": args.channel, "dependencies": [] if not dependency else [{"project_id": API_PROJECT,
                    "version_id": dependency["modrinthVersionId"], "dependency_type": "required"}]},
            "curseforge": {"projectId": 1756581, "gameVersions": [target["minecraft"]],
                "loaders": [target["loader"]], "environmentScope": "file", "environment": ["Client"],
                "javaVersions": [java], "releaseType": args.channel,
                "requiredProjects": [] if not dependency else [{"projectId": 306612, "slug": "fabric-api", "relation": "requiredDependency"}]},
            "remoteState": "Not queried by local preparation; canonical platform status is recorded separately"})
    world_count = sum(row["worldVerified"] for row in rows)
    package_count = sum(row["packageIndexMatches"] for row in rows)
    regression_count = sum(row["rendererOwnerRegressions"]["passed"] for row in rows)
    ready = (world_count == 67 and package_count == 67 and regression_count == 67
             and all(row["rendererOwnerIsolationPresent"] for row in rows))
    summary = {"schemaVersion": 1, "preparedAt": datetime.now(timezone.utc).isoformat(), "renderscopeVersion": "0.3.0",
        "mode": "local preparation only; no platform writes", "publicReleaseAuthorized": True,
        "publicationReady": ready, "publicationPerformed": False, "releaseChannel": args.channel,
        "minecraftReleaseCount": 23, "artifactCount": 67, "worldVerifiedTargets": world_count,
        "packageIndexMatchedTargets": package_count,
        "rendererOwnerRegressionVerifiedTargets": regression_count,
        "remoteStatusAuthority": "distribution/platforms/publication-status.json",
        "runtimeMeaning": "Installed production and development-client evidence remain distinct. Every accepted result matches this exact primary JAR hash. A vanilla result is not optional-mod or shader evidence.",
        "license": "LicenseRef-RenderScope-Source-Available-1.0", "sourceUrl": REPO_URL, "sourceRef": SOURCE_REF,
        "licenseUrl": REPO_URL + "/blob/main/LICENSE", "targets": rows}
    write("release-manifest.json", json.dumps(summary, ensure_ascii=False, indent=2))
    write("fabric-api-dependencies.json", json.dumps({"source": API_URL,
        "sourceCacheSha256": hashlib.sha256(args.api_cache.read_bytes()).hexdigest(),
        "meaning": "Exact build-pinned required Fabric API versions; not latest-version guesses.", "targets": dependencies}, indent=2))
    table = ["# Exact release dependencies / Точные зависимости", "",
        "Install one matching client JAR. Fabric requires the build-pinned API below; game validation is recorded separately. Forge/NeoForge runtime integrations are bundled or supplied by the pinned loader. No RenderScope server component is required.", "",
        "Установите один подходящий клиентский JAR. Для Fabric ниже указан точный проверяемый Fabric API; версия загрузчика и версия игры выбираются отдельно.", "",
        "| Minecraft | Fabric Loader | Fabric API | Forge | NeoForge | Java |", "| --- | --- | --- | --- | --- | --- |"]
    for mc in sorted(pins, key=lambda value: tuple(map(int, value.split(".")))):
        selected = {row["loader"]: row for row in rows if row["minecraft"] == mc}
        api = by_api[mc]
        api_status = "" if api["releaseType"] == "release" else " · " + api["releaseType"]
        table.append(f"| {mc} | {selected['fabric']['loaderVersion']} | [{api['version']}]({api['modrinthVersionUrl']}){api_status} | "
                     + selected.get("forge", {}).get("loaderVersion", "Upstream unavailable") + " | "
                     + selected["neoforge"]["loaderVersion"] + f" | {selected['fabric']['javaRuntime']} |")
    write("DEPENDENCIES.md", "\n".join(table))
    media = gallery(rows)
    write("media-manifest.json", json.dumps({"meaning": "Branding plus actual PNG frames from hash-matched accepted world evidence. No generated gameplay or optional-mod claims for other targets.", "assets": media}, ensure_ascii=False, indent=2))
    illustrations = "\n".join(f"\n![{asset['title']}]({RAW_URL}{asset['path']})\n\n"
        f"{asset['minecraft']} · {LOADER_NAMES[asset['loader']]} · {asset['runtimeScope']}. "
        f"[Exact evidence / Точная проверка]({REPO_URL}/blob/main/{asset['runtimeEvidence']}).\n"
        for asset in media if asset["kind"] != "brand")
    write("DESCRIPTION_EN.md", EN + illustrations)
    write("DESCRIPTION_RU.md", RU + illustrations)
    for platform in ("modrinth", "curseforge"):
        write(platform + "-description-030.ready.md", EN + illustrations + "\n---\n\n" + RU)
    validation = (f"Current snapshot: {world_count}/67 exact artifact hashes have real-world evidence; "
                  f"{package_count}/67 packaged copies match the current audited binaries. "
                  f"{regression_count}/67 targets have passed the actual owner-isolation, rolling, capture and exporter XML regressions. "
                  "Installed production and development-client scopes are listed per artifact. "
                  + ("All exact targets satisfy the local release gate. Platform publication and moderation status are recorded separately."
                     if ready else "DRAFT: this preparation is not a public upload, moderation approval or an assertion that all worlds have passed."))
    changelog = ("# RenderScope 0.3.0\n\n"
        "- 67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3.\n"
        "- Fabric, Forge and NeoForge adapters use their real version-specific rendering APIs.\n"
        "- Clearer diagnostics connect measured CPU work to a mod, type and concrete world instance.\n"
        "- Shared renderer implementations retain independent registry-owner attribution.\n"
        "- Native UI, highlight/track, heatmap, Inspector, timeline, capture and private JSON.\n"
        "- Explicit partial measurement scope: unavailable geometry/GPU work is never invented.\n\n"
        + validation + "\n\n[Exact dependencies](DEPENDENCIES.md) · [Per-artifact evidence](release-manifest.json)\n\n"
        "## Русский\n\n67 отдельных клиентских сборок для 23 стабильных версий Minecraft. "
        "Диагностика ведёт от измеренной CPU-работы к конкретному объекту; общие renderer-классы учитываются отдельно для каждого registry owner. "
        "Сохранены нативный UI, подсветка, tracking, теплокарта, инспектор, timeline, capture и приватный JSON. "
        "Недоступные GPU/geometry-метрики не подменяются оценками.\n\n"
        f"Для текущих хешей есть {world_count}/67 игровых результатов; совпадает {package_count}/67 упакованных JAR. "
        "Установленный клиент и dev-среда различаются в доказательствах. "
        + ("Все цели прошли локальную проверку готовности. Фактическая публикация и модерация платформ учитываются отдельно.\n\n"
           if ready else "ЧЕРНОВИК: это локальная подготовка, а не подтверждение публикации или одобрения платформами.\n\n")
        + f"[Полная лицензия]({REPO_URL}/blob/main/LICENSE): бесплатные проекты и изменения разрешены по её условиям; платные содержащие проекты, продажи и платный доступ требуют письменного разрешения.")
    write("CHANGELOG_0.3.0.md", changelog)
    write("GITHUB_RELEASE_0.3.0.md", changelog + f"\n\n[All exact downloads]({REPO_URL}/blob/main/distribution/0.3.0/README.md)")
    write("README.md", "# RenderScope 0.3.0 publication preparation\n\n" + validation
        + "\n\nRegenerate after package/runtime updates: `python scripts/prepare-publication-030.py` "
        "(default channel Release as requested; `--source-ref v0.3.0` pins final evidence links to the release tag). "
        "This helper never uploads, modifies a platform project, runs Gradle or starts Minecraft. "
        "The existing 0.2.0 saved descriptions, proofs and publication status are historical records and are not overwritten.\n\n"
        "The JSON manifest contains 67 file-level publication records, exact required Fabric API Modrinth version IDs / CurseForge project relations, "
        "Java requirements, legal identity, changelog paths, hashes and scope-specific evidence. "
        "`publicationReady` stays false until all 67 world results and package hashes match and actual XML confirms "
        "the three core ownership/rolling/capture cases plus the matching exporter regression without failures/errors/skips. "
        "Generated remoteState is not a claim about uploaded files; actual platform state remains in the separate canonical status record.\n")
    print(f"Prepared {len(rows)} exact targets / {len(dependencies)} pinned Fabric API versions; "
          f"worlds {world_count}/67, packages {package_count}/67, publicationReady={ready}. No external writes.")


if __name__ == "__main__":
    main()
