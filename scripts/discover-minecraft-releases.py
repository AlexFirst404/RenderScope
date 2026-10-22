"""Resolve requested stable Java releases from Mojang, with loader metadata provenance.

This is a requested-target catalogue, never a declaration of tested mod support.
Only verified release artifacts/runtime evidence may be marked supported elsewhere.
"""
import concurrent.futures
import datetime
import hashlib
import json
import re
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'minecraft': 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',
    'fabricLoader': 'https://meta.fabricmc.net/v2/versions/loader',
    'fabricApi': 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml',
    'forge': 'https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json',
    'neoforge': 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml',
}

def read(url):
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'RenderScope-build/0.3'}), timeout=40) as response:
                return response.read()
        except OSError:
            if attempt == 4: raise
            time.sleep(1 + attempt)

def numeric(version):
    return tuple(int(part) for part in re.findall(r'\d+', version))

def main():
    manifest = json.loads(read(SOURCES['minecraft']))
    releases = [item for item in manifest['versions'] if item['type'] == 'release'
                and numeric('1.20.1') <= numeric(item['id']) <= numeric('26.3')]
    releases.sort(key=lambda item: numeric(item['id']))
    loaders = json.loads(read(SOURCES['fabricLoader']))
    fabric_loader = next(item['version'] for item in loaders if item.get('stable'))
    fabric_api = [node.text for node in ET.fromstring(read(SOURCES['fabricApi'])).findall('./versioning/versions/version')]
    forge = json.loads(read(SOURCES['forge']))['promos']
    neo = [node.text for node in ET.fromstring(read(SOURCES['neoforge'])).findall('./versioning/versions/version')]

    def resolve(item):
        raw = read(item['url'])
        assert hashlib.sha1(raw).hexdigest() == item['sha1'], f"Manifest SHA-1 mismatch: {item['id']}"
        game = json.loads(raw)
        version = item['id']
        api_versions = [value for value in fabric_api if value.endswith('+' + version)]
        if version == '1.20.1':
            neo_versions = ['47.1.106']
            neo_coordinate = 'net.neoforged:forge'
        else:
            parts = version.split('.')
            prefix = '.'.join(parts[1:] if parts[0] == '1' else parts[:2])
            if len(prefix.split('.')) == 1: prefix += '.0'
            neo_versions = [value for value in neo if value.startswith(prefix + '.')]
            neo_coordinate = 'net.neoforged:neoforge'
        return {
            'minecraft': version,
            'javaMajor': game['javaVersion']['majorVersion'],
            'manifestUrl': item['url'], 'manifestSha1': item['sha1'],
            'releasedAt': item['releaseTime'],
            'requested': True,
            'loaders': {
                'fabric': {'loaderVersion': fabric_loader, 'apiVersion': api_versions[-1] if api_versions else None},
                'forge': {'loaderVersion': forge.get(version + '-recommended') or forge.get(version + '-latest')},
                'neoforge': {'coordinate': neo_coordinate, 'candidateVersion': neo_versions[-1] if neo_versions else None,
                    'note': 'A candidate coordinate is not support evidence; its embedded game constraints must be checked.'},
            },
        }
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        targets = list(executor.map(resolve, releases))
    output = ROOT / 'docs/compatibility/requested-releases.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'checkedOn': datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
        'range': ['1.20.1', '26.3'], 'stableOnly': True,
        'meaning': 'Requested targets and upstream availability only, not a tested support matrix.',
        'sources': SOURCES, 'targets': targets}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{len(targets)} official stable releases; {output.relative_to(ROOT)}')

if __name__ == '__main__': main()
