"""Verify native loader packaging and actual selected-version tests; no runtime pass is inferred."""
import argparse, hashlib, json, struct, tomllib, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent

def require(value,message):
    if not value:raise SystemExit(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loader',choices=('forge','neoforge'),required=True)
    parser.add_argument('--minecraft',choices=('1.21.9','1.21.10','1.21.11'),required=True)
    parser.add_argument('--require-runtime',action='store_true')
    parser.add_argument('--runtime-evidence',type=Path,help='Explicit result JSON, normally from the installed launcher')
    args=parser.parse_args()
    target=next(item for item in json.loads((ROOT/'targets.json').read_text())['targets'] if item['loader']==args.loader and item['minecraft']==args.minecraft)
    artifact=REPO/target['artifact']
    require(artifact.is_file(),'Selected artifact is missing')
    with zipfile.ZipFile(artifact) as jar:
        names=set(jar.namelist())
        resource_format=75 if args.minecraft=='1.21.11' else 69
        pack=json.loads(jar.read('pack.mcmeta'))['pack']
        require(pack.get('min_format')==[resource_format,0] and pack.get('max_format')==[resource_format,0],
            'Resource pack metadata must match the selected vanilla client resource format')
        require('fabric.mod.json' not in names,'Fabric metadata leaked into native loader artifact')
        require(not any('/smoke/' in name or 'gametest' in name.lower() or 'NativeSmoke' in name or 'NativeModernSmoke' in name for name in names),'Test harness leaked into artifact')
        require(jar.read('META-INF/LICENSE')==(REPO/'LICENSE').read_bytes(),'Embedded license bytes differ')
        require(jar.read('META-INF/THIRD_PARTY_NOTICES.md')==(REPO/'THIRD_PARTY_NOTICES.md').read_bytes(),'Third-party notices differ')
        metadata_path='META-INF/mods.toml' if args.loader=='forge' else 'META-INF/neoforge.mods.toml'
        metadata=tomllib.loads(jar.read(metadata_path).decode())
        require(metadata['license']=='LicenseRef-RenderScope-Source-Available-1.0','Incorrect license metadata')
        require(metadata['mods'][0]['modId']=='renderscope','Incorrect mod id')
        dependencies=metadata['dependencies']['renderscope']
        require(any(item['modId']=='minecraft' and item['versionRange']==f'[{args.minecraft}]' for item in dependencies),'Minecraft target must be exact')
        require(any(item['modId']==args.loader and item['versionRange']==f"[{target['loaderVersion']},)" for item in dependencies),'Loader version metadata differs')
        require(all(item['side']=='CLIENT' for item in dependencies),'Artifact dependencies are not client-side')
        manifest=jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','')
        for text in (f"RenderScope-Loader: {args.loader}",f"RenderScope-Loader-Version: {target['loaderVersion']}",f"RenderScope-Minecraft: {args.minecraft}"):
            require(text in manifest,'Manifest identity mismatch: '+text)
        mixins=json.loads(jar.read('renderscope.modern.mixins.json'))
        require(mixins.get('required') is True and 'refmap' not in mixins,'Native named runtime must not receive a Fabric/SRG refmap')
        require('MappedLevelRendererMixin' in mixins['client'],'World measurement/overlay mixin missing')
        require(jar.read('assets/renderscope/renderscope-logo.png')==(REPO/'src/main/resources/renderscope-logo.png').read_bytes(),'Pixel-art logo bytes changed')
        for name in names:
            if name.startswith('dev/renderscope/') and name.endswith('.class'):
                data=jar.read(name)
                require(struct.unpack('>H',data[6:8])[0]==65,'Production class requires another Java version: '+name)
                require(b'net/fabricmc/' not in data,'Fabric API reference leaked into native class: '+name)
        require('dev/renderscope/modern/RenderScopeScreen.class' in names,'Native UI missing')
        entry='RenderScopeForge' if args.loader=='forge' else 'RenderScopeNeoForge'
        require(f'dev/renderscope/modern/{entry}.class' in names,'Native loader bootstrap missing')
        if args.minecraft!='1.21.11':require('META-INF/accesstransformer.cfg' in names,'Native pipeline access transformer missing')
        nested_mixinextras=any('mixinextras-forge' in name for name in names)
        if args.loader=='forge' and args.minecraft=='1.21.9':
            require(nested_mixinextras,'Forge59 requires the bundled MixinExtras runtime')
            bundled=json.loads(jar.read('META-INF/jarjar/metadata.json'))['jars']
            runtime=next(item for item in bundled if item['identifier']['artifact']=='mixinextras-forge')
            require(runtime['version']=={'range':'[0.4.1,)','artifactVersion':'0.4.1'},'Pinned MixinExtras must permit newer compatible loader/mod runtimes')
        if args.loader=='forge' and args.minecraft in ('1.21.10','1.21.11'):require(not nested_mixinextras,'Forge60/61 already supply MixinExtras; conflicting runtime must not be nested')
    suites=list((REPO/target['testResults']).glob('TEST-*.xml'))
    require(suites,'No selected-version test results')
    counts={key:0 for key in ('tests','failures','errors','skipped')}
    for path in suites:
        attributes=ET.parse(path).getroot().attrib
        for key in counts:counts[key]+=int(attributes.get(key,0))
    require(counts['tests']>=56 and not any(counts[key] for key in ('failures','errors','skipped')),f'Tests did not pass: {counts}')
    require(any(path.name=='TEST-dev.renderscope.modern.NativeMappedTargetsTest.xml' for path in suites),'Patched Minecraft interception targets were not tested')
    result={'minecraft':args.minecraft,'loader':args.loader,'loaderVersion':target['loaderVersion'],
        'artifact':target['artifact'],'bytes':artifact.stat().st_size,'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),
        'tests':counts['tests'],'runtimeVerified':False}
    result['resourcePackFormat']=[resource_format,0]
    if args.require_runtime:
        path=args.runtime_evidence or REPO/f'distribution/{args.loader}/{args.minecraft}/development/smoke-result.json'
        require(path.is_file(),'Selected runtime result is missing')
        runtime=json.loads(path.read_text(encoding='utf-8'))
        require(runtime.get('success') is True,'Real-world runtime assertions did not pass')
        require(runtime.get('minecraftVersion')==args.minecraft and runtime.get('loader','').lower()==args.loader,
            'Runtime belongs to another Minecraft or loader')
        require(runtime.get('loaderVersion')==target['loaderVersion'] and runtime.get('runtimeLoaderVerified') is True,
            'Exact runtime loader was not verified')
        require(runtime.get('artifactSha256')==result['sha256'] and runtime.get('artifactBytes')==result['bytes'],
            'Runtime belongs to another main artifact')
        environment=runtime.get('environment')
        require(environment in ('installed-production',args.loader+'-development-client'),'Unknown runtime scope')
        if environment=='installed-production':
            require(runtime.get('primaryJarSha256')==result['sha256'] and runtime.get('primaryJarBytes')==result['bytes'],
                'Actual installed main origin did not match the release artifact')
        for field in ('entityAndBlockEntityExtractionAndSubmission','f8NativeScreen','pauseNativeButton',
            'selectedGizmoBoxAndLabels','realCrosshairInspector','reportPrivacy','disconnectClearsInstances',
            'selectedInstanceOwnerDetails','rendererClassCopy','highlightNativeButton'):
            require(runtime.get(field) is True,'Missing runtime assertion: '+field)
        require(runtime.get('captureSeconds',0)>=5 and runtime.get('captureFrames',0)>20,'Real runtime capture is too short')
        require(runtime.get('heatmapGizmoBoxes',0)>=3,'Measured-object heatmap was not exercised')
        screenshots=runtime.get('screenshots',[])
        require(len(screenshots)>=13,'Runtime screenshot set is incomplete')
        for name in screenshots:
            require(Path(name).name==name and (path.parent/name).is_file(),'Missing runtime screenshot: '+name)
        result['runtimeVerified']=True
        result['runtimeScope']=environment
        result['runtimeEvidence']=str(path)
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
