"""Collect resolved runtime coordinates, cached POM license declarations and embedded notices."""
from pathlib import Path
import hashlib, json, zipfile, xml.etree.ElementTree as ET, io
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
out=root/'third-party/runtime-notices'; out.mkdir(parents=True,exist_ok=True)
records=[]
ns={'m':'http://maven.apache.org/POM/4.0.0'}
for line in (root/'evidence/runtime-artifacts.tsv').read_text(encoding='utf-8-sig').splitlines():
    group,name,version,filename=line.split('\t'); artifact=Path(filename)
    if not artifact.is_file():
        matches=list((cache/group/name/version).glob('*/'+Path(filename).name))
        if len(matches)!=1: raise RuntimeError(f'Expected one resolved artifact for {group}:{name}:{version}: {filename}')
        artifact=matches[0]
    poms=list((cache/group/name/version).glob('*/*.pom'))
    licenses=[]
    for pom in poms:
        document=ET.parse(pom)
        licenses.extend({'name':x.findtext('m:name',default='',namespaces=ns),'url':x.findtext('m:url',default='',namespaces=ns)} for x in document.findall('.//m:licenses/m:license',ns))
    notices=[]
    folder=out/(group+'_'+name+'_'+version)
    def collect(data,prefix=''):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for entry in archive.infolist():
                if entry.is_dir():continue
                label=Path(entry.filename).name.lower()
                if any(term in label for term in ['license','notice','copying','copyright']):
                    target=folder/(prefix+entry.filename.replace('/','_'))
                    target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(archive.read(entry)); notices.append(target.relative_to(root).as_posix())
                elif entry.filename=='classes.jar':collect(archive.read(entry),'classes_')
    if artifact.suffix in ('.jar','.aar'):collect(artifact.read_bytes())
    records.append({'coordinate':f'{group}:{name}:{version}','sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'pomLicenses':licenses,'embeddedNoticeFiles':notices,
                    'noticeStatus':'preserved' if notices else 'POM declaration only; see full upstream license texts'})
(root/'third-party/runtime-inventory.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
print(f'Inventoried {len(records)} runtime artifacts; preserved {sum(len(r["embeddedNoticeFiles"]) for r in records)} embedded notices.')
