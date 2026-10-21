"""Verify isolated native QA JARs, including Java-module package separation."""
import argparse
import hashlib
import json
import struct
import tomllib
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent

def require(value,message):
    if not value:raise SystemExit(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loader',choices=('forge','neoforge'))
    parser.add_argument('--minecraft',choices=('1.21.9','1.21.10','1.21.11'))
    args=parser.parse_args()
    targets=json.loads((ROOT/'targets.json').read_text(encoding='utf-8'))['targets']
    rows=[]
    for target in targets:
        if args.loader and target['loader']!=args.loader:continue
        if args.minecraft and target['minecraft']!=args.minecraft:continue
        primary=REPO/target['artifact']
        artifact=primary.with_name(primary.stem+'-native-smoke-harness.jar')
        require(artifact.is_file(),'Missing selected QA JAR: '+str(artifact))
        with zipfile.ZipFile(primary) as main,zipfile.ZipFile(artifact) as qa:
            names=set(qa.namelist())
            packages=lambda entries:{name.rpartition('/')[0] for name in entries if name.endswith('.class')}
            overlap=packages(main.namelist())&packages(names)
            require(not overlap,'Main/QA Java module packages overlap: '+str(overlap))
            prefix='dev/renderscope/modern/smoke/'
            for name in ('NativeModernSmoke.class','NativeSmokeContext.class','NativeSmokeOrigin.class','mixin/MouseInvoker.class'):
                require(prefix+name in names,'Missing real QA implementation: '+name)
            for name in names:
                if name.endswith('.class'):
                    data=qa.read(name)
                    require(name.startswith(prefix),'QA class escaped its dedicated package: '+name)
                    require(struct.unpack('>H',data[6:8])[0]==65,'Native QA class must target Java21: '+name)
                    require(b'net/fabricmc/' not in data,'Fabric API leaked into native QA: '+name)
            mixins=json.loads(qa.read('renderscope.native-smoke.mixins.json'))
            require(mixins['package']=='dev.renderscope.modern.smoke.mixin' and mixins['client']==['MouseInvoker'],
                'QA Mixin config must contain only the actual mouse callback Invoker')
            manifest=qa.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','')
            require('MixinConfigs: renderscope.native-smoke.mixins.json' in manifest and
                'MixinConfigs: renderscope.modern.mixins.json' not in manifest,'QA must register its own config only')
            metadata_path='META-INF/mods.toml' if target['loader']=='forge' else 'META-INF/neoforge.mods.toml'
            metadata=tomllib.loads(qa.read(metadata_path).decode())
            require(metadata['mods'][0]['modId']=='renderscope_native_smoke','Incorrect separate QA mod identity')
        rows.append({'minecraft':target['minecraft'],'loader':target['loader'],
            'artifact':str(artifact.relative_to(REPO)).replace('\\','/'),'bytes':artifact.stat().st_size,
            'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'packagesDisjointFromMain':True,'runtimeVerified':False})
    print(json.dumps({'scope':'Separate native QA JAR compilation, no Fabric dependency, disjoint Java module packages; not a runtime pass.',
        'artifacts':rows},indent=2))

if __name__=='__main__':main()
