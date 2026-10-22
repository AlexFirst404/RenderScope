"""Cache exact Fabric API/intermediary artifacts, verifying the vendor's SHA-1 files.

Optional workaround for intermittent Java HTTPS failures. TLS validation stays enabled.
The ignored local Maven repository is usable without changing upstream coordinates.
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
DEST = ROOT / '.tools/fabric-maven'
BASE = 'https://maven.fabricmc.net/'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def request(relative: str) -> bytes:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(BASE + relative,
                    headers={'User-Agent':'RenderScope reproducible dependency cache/0.3'}), timeout=35) as response:
                return response.read()
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3: raise
            time.sleep(attempt + 1)
    raise AssertionError('unreachable')

def artifact(relative: str, optional: bool = False) -> bytes | None:
    target = DEST / relative
    try:
        checksum = request(relative + '.sha1').decode().split()[0].lower()
    except urllib.error.HTTPError as error:
        if optional and error.code == 404: return None
        raise
    data = target.read_bytes() if target.is_file() else b''
    if hashlib.sha1(data).hexdigest() != checksum:
        data = request(relative)
    if hashlib.sha1(data).hexdigest() != checksum:
        raise RuntimeError('Vendor SHA-1 mismatch: ' + relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    target.with_name(target.name + '.sha1').write_text(checksum, encoding='ascii')
    return data

def pom(group: str, name: str, version: str) -> tuple[str, ET.Element]:
    base = group.replace('.', '/') + '/' + name + '/' + version + '/' + name + '-' + version
    return base, ET.fromstring(artifact(base + '.pom'))

def dependencies(xml: ET.Element) -> set[tuple[str, str, str]]:
    result = set()
    for dep in xml.findall('m:dependencies/m:dependency', NS):
        group, name, version = (dep.findtext('m:' + key, namespaces=NS) for key in ('groupId','artifactId','version'))
        if group == 'net.fabricmc.fabric-api': result.add((group, name, version))
    return result

def module(coordinate: tuple[str, str, str]) -> set[tuple[str, str, str]]:
    base, xml = pom(*coordinate)
    if xml.findtext('m:packaging', namespaces=NS) != 'pom':
        artifact(base + '.jar')
        artifact(base + '-sources.jar', optional=True)
    return dependencies(xml)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--minecraft', required=True, nargs='+')
    args = parser.parse_args()
    catalogue = json.loads((ROOT / 'docs/compatibility/requested-releases.json').read_text())
    for mc in args.minecraft:
        target = next(t for t in catalogue['targets'] if t['minecraft'] == mc)
        api = target['loaders']['fabric']['apiVersion']
        base, xml = pom('net.fabricmc.fabric-api', 'fabric-api', api)
        artifact(base + '.jar')
        artifact(base + '-sources.jar', optional=True)
        pending = dependencies(xml)
        visited = set()
        with ThreadPoolExecutor(max_workers=8) as pool:
            while pending:
                current = pending - visited
                visited.update(current)
                pending = set().union(*pool.map(module, sorted(current))) - visited if current else set()
        if not mc.startswith('26.'):
            path, _ = pom('net.fabricmc', 'intermediary', mc)
            artifact(path + '.jar')
            artifact(path + '-v2.jar')
        print(f'{mc}: verified Fabric API {api}, {len(visited)} exact API modules', flush=True)

if __name__ == '__main__': main()
