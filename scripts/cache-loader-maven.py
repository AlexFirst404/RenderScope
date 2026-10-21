"""Cache exact vendor Maven artifacts with their official SHA-1 files; TLS remains verified.

This is an optional workaround for intermittent Java HTTPS failures. It does not fabricate
POMs or alter coordinates. The resulting ignored repository can precede the same upstream.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.tools/verified-vendor-maven'
BASES = {'mojang':'https://libraries.minecraft.net/', 'forge':'https://maven.minecraftforge.net/',
         'neoforge':'https://maven.neoforged.net/releases/', 'neo_meta':'https://maven.neoforged.net/mojang-meta/'}


def request(url: str) -> bytes:
    if not url.startswith('https://'): raise ValueError('HTTPS is required')
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'RenderScope verified dependency cache/0.3'}), timeout=45) as response:
                return response.read()
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3: raise
            time.sleep(attempt + 1)
    raise AssertionError('unreachable')


def artifact(base: str, relative: str, optional: bool = False) -> dict | None:
    try:
        expected = request(base + relative + '.sha1').decode().strip().split()[0].lower()
    except urllib.error.HTTPError as error:
        if optional and error.code == 404: return None
        raise
    if len(expected) != 40 or any(c not in '0123456789abcdef' for c in expected):
        raise RuntimeError('Invalid official checksum: ' + relative)
    target = DEST / relative
    data = target.read_bytes() if target.is_file() else b''
    if hashlib.sha1(data).hexdigest() != expected:
        data = request(base + relative)
    if hashlib.sha1(data).hexdigest() != expected: raise RuntimeError('Vendor checksum mismatch: ' + relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    target.with_name(target.name + '.sha1').write_text(expected, encoding='ascii')
    return {'path':relative, 'sha1':expected, 'source':base + relative}


def module(entry: tuple[str, str]) -> dict:
    vendor, coordinate = entry
    parts = coordinate.split(':')
    if len(parts) not in (3, 4): raise ValueError('Expected group:artifact:version[:classifier]')
    group, name, version = parts[:3]
    if any('/' in token or '\\' in token or token in ('', '.', '..') for token in parts):
        raise ValueError('Invalid Maven coordinate')
    relative = group.replace('.', '/') + '/' + name + '/' + version + '/' + name + '-' + version
    files = [artifact(BASES[vendor], relative + '.pom'), artifact(BASES[vendor], relative + '.module', optional=True)]
    pom = ET.fromstring((DEST / (relative + '.pom')).read_bytes())
    packaging = pom.findtext('{http://maven.apache.org/POM/4.0.0}packaging', 'jar')
    if packaging != 'pom':
        files.append(artifact(BASES[vendor], relative + '.' + packaging))
    if len(parts) == 4:
        files.append(artifact(BASES[vendor], relative + '-' + parts[3] + '.jar'))
    metadata = DEST / (relative + '.module')
    if metadata.is_file():
        # Runtime variants may select other operating-system classifiers while creating mapped artifacts.
        # Preserve the vendor's metadata and verify every JAR it references rather than removing variants.
        paths = {entry['path'] for entry in files if entry}
        for variant in json.loads(metadata.read_text()).get('variants', []):
            for file in variant.get('files', []):
                name = file['url']
                if Path(name).name != name or name.endswith(('.sha1', '.pom', '.module')):
                    raise RuntimeError('Unexpected vendor variant artifact path: ' + name)
                path = relative.rsplit('/', 1)[0] + '/' + name
                if path not in paths:
                    files.append(artifact(BASES[vendor], path))
                    paths.add(path)
    return {'coordinate':coordinate, 'vendor':vendor, 'files':[value for value in files if value]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for vendor in BASES: parser.add_argument('--' + vendor, nargs='*', default=[])
    parser.add_argument('--minecraft', nargs='*', default=[], help='Verified Mojang/LWJGL libraries, including native classifiers, for these exact releases')
    args = parser.parse_args()
    wanted = [(vendor, coordinate) for vendor in BASES for coordinate in getattr(args, vendor)]
    native_files = []
    catalogue = json.loads((ROOT / 'docs/compatibility/requested-releases.json').read_text())
    for minecraft in args.minecraft:
        target = next(entry for entry in catalogue['targets'] if entry['minecraft'] == minecraft)
        data = request(target['manifestUrl'])
        if hashlib.sha1(data).hexdigest() != target['manifestSha1']: raise RuntimeError('Official Minecraft manifest checksum mismatch')
        manifest = json.loads(data)
        if manifest['id'] != minecraft: raise RuntimeError('Minecraft manifest identity mismatch')
        for library in manifest['libraries']:
            parts = library['name'].split(':')
            if parts[0] != 'com.mojang' and parts[0] != 'org.lwjgl': continue
            wanted.append(('mojang', ':'.join(parts[:3])))
            descriptors = list(library.get('downloads', {}).get('classifiers', {}).values())
            if len(parts) > 3: descriptors.append(library['downloads']['artifact'])
            native_files.extend(descriptors)
    wanted = list(dict.fromkeys(wanted))
    if not wanted: parser.error('Pass one or more exact vendor Maven coordinates')
    with ThreadPoolExecutor(max_workers=6) as pool: results = list(pool.map(module, wanted))
    native_files = list({entry['path']:entry for entry in native_files}.values())
    def native_file(entry: dict) -> None:
        if not entry['url'].startswith(BASES['mojang']): raise RuntimeError('Unexpected Mojang native library host')
        value = artifact(BASES['mojang'], entry['path'])
        if value['sha1'] != entry['sha1']: raise RuntimeError('Native Maven/Minecraft-manifest checksum disagreement')
    with ThreadPoolExecutor(max_workers=8) as pool: list(pool.map(native_file, native_files))
    index = DEST / 'verified-modules.json'
    existing = json.loads(index.read_text()) if index.is_file() else []
    combined = {entry['coordinate']:entry for entry in existing}
    combined.update({entry['coordinate']:entry for entry in results})
    index.write_text(json.dumps(list(combined.values()), indent=2) + '\n')
    print('Verified vendor Maven modules:', ', '.join(entry['coordinate'] for entry in results))


if __name__ == '__main__': main()
