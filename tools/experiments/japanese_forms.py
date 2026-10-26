"""Synthetic Japanese boxed forms: compare layout, language order and field crops.
No personal document inputs. Requires Pillow and pinned models from card_ocr.py.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont
from card_ocr import EXPECTED


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    for language,digest in EXPECTED.items():
        assert hashlib.sha256((args.models/f'{language}.traineddata').read_bytes()).hexdigest()==digest
    args.output.mkdir(parents=True,exist_ok=True)
    expected=['会員番号','0012345678','氏名','山田太郎','有効期限','2027年12月31日']
    results=[]
    for size in (32,48):
        font=ImageFont.truetype('/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc',size,index=0)
        for boxes in (False,True):
            image=Image.new('RGB',(1200,600),'white');draw=ImageDraw.Draw(image)
            for row in range(3):
                y=50+row*170
                if boxes:
                    draw.rectangle((30,y-10,1170,y+130),outline='black',width=3)
                    draw.line((400,y-10,400,y+130),fill='black',width=3)
                draw.text((60,y+20),expected[row*2],font=font,fill='black')
                draw.text((440,y+20),expected[row*2+1],font=font,fill='black')
            path=args.output/f'form-{size}-boxes{boxes}.png';image.save(path)
            fields=[]
            for row in range(3):
                y=50+row*170
                for col,(left,right) in enumerate(((45,390),(420,1160))):
                    field=args.output/f'field-{size}-{boxes}-{row}-{col}.png'
                    image.crop((left,y, right,y+115)).save(field)
                    fields.append(field)
            for language in ('eng+jpn','jpn+eng','jpn'):
                for mode in ('document','sparse','block','fields'):
                    psm={'document':3,'sparse':11,'block':6,'fields':7}[mode]
                    output=[]
                    for source in fields if mode=='fields' else [path]:
                        r=subprocess.run(['tesseract',str(source),'stdout','--tessdata-dir',str(args.models),'-l',language,'--psm',str(psm)],
                            text=True,capture_output=True,check=True,timeout=30,env={**os.environ,'OMP_THREAD_LIMIT':'1'})
                        output.append(r.stdout.strip())
                    text='\n'.join(output); compact=''.join(text.split())
                    positions=[compact.find(value) for value in expected]
                    results.append(dict(size=size,boxes=boxes,language=language,mode=mode,text=text,
                        fields_found=sum(p>=0 for p in positions),all_in_order=all(p>=0 for p in positions) and positions==sorted(positions)))
    summary={mode:dict(cases=sum(r['mode']==mode for r in results),
        all_fields=sum(r['mode']==mode and r['fields_found']==6 for r in results),
        ordered=sum(r['mode']==mode and r['all_in_order'] for r in results)) for mode in ('document','sparse','block','fields')}
    report=dict(engine=subprocess.check_output(['tesseract','--version'],text=True).splitlines()[0],
        limitation='Synthetic host diagnostic. Field boxes/order are known here; no automatic field detection is implied.',
        model_sha256=EXPECTED,summary=summary,results=results)
    (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(summary,indent=2))

if __name__=='__main__': main()
