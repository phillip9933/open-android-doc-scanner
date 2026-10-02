"""Preserve source notice blocks from the included OpenCV modules, plus pinned NDK notices."""
from pathlib import Path
import re, json, hashlib, shutil, argparse
parser=argparse.ArgumentParser(); parser.add_argument('--source',type=Path,required=True); parser.add_argument('--ndk',type=Path,required=True)
args=parser.parse_args(); root=Path(__file__).resolve().parents[1]
out=root/'third-party/native-source-notices'; out.mkdir(parents=True,exist_ok=True)
records=[]; blocks=[]
for module in ['core','imgproc','java']:
    for file in sorted((args.source/'modules'/module).rglob('*')):
        if file.suffix.lower() not in ['.h','.hpp','.c','.cpp','.java','.in']:continue
        text=file.read_text(encoding='utf-8',errors='replace')
        notices=[]
        # Retain complete copyright-bearing block comments, including permissive exceptions.
        notices.extend(m.group() for m in re.finditer(r'/\*.*?\*/',text,re.S) if re.search(r'copyright|redistribution|permission is|license',m.group(),re.I))
        # Preserve adjacent line-comment notices as complete groups.
        notices.extend(m.group() for m in re.finditer(r'(?:^[ \t]*//[^\n]*\n)+',text,re.M) if re.search(r'copyright|license|redistribut|permission',m.group(),re.I))
        if notices:
            relative=file.relative_to(args.source).as_posix(); blocks.append('\nFILE: '+relative+'\n'+'\n'.join(notices))
            records.append({'sourcePath':relative,'sourceSha256':hashlib.sha256(file.read_bytes()).hexdigest(),'noticeBlockCount':len(notices)})
(out/'OpenCV-core-imgproc-java-SOURCE-NOTICES.txt').write_text('\n'.join(blocks),encoding='utf-8')
shutil.copy2(args.source/'LICENSE',out/'OpenCV-LICENSE.txt')
for name in ['NOTICE','NOTICE.toolchain']:shutil.copy2(args.ndk/name,out/('Android-NDK-'+name+'.txt'))
(out/'inventory.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
assets=root/'scanner-processing-opencv/src/main/assets/offline-scanner-notices'; assets.mkdir(parents=True,exist_ok=True)
for file in out.glob('*.txt'):shutil.copy2(file,assets/file.name)
shutil.copy2(root/'THIRD-PARTY-NOTICES.md',assets/'THIRD-PARTY-NOTICES.md')
print(f'Preserved complete notices from {len(records)} OpenCV source files and matching NDK distribution notices; copied into SDK assets.')
