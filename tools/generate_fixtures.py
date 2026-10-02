"""Deterministic, original synthetic scanner benchmark. Apache-2.0; no private images."""
from pathlib import Path
import json, math, hashlib
from PIL import Image, ImageDraw, ImageFilter

root = Path(__file__).resolve().parents[1]
assets = root / 'scanner-processing-opencv/src/androidTest/assets/benchmark'
assets.mkdir(parents=True, exist_ok=True)
cases = []
categories = ['paper-dark', 'paper-light', 'receipt', 'card', 'colored-form', 'photo',
              'rotation', 'perspective', 'shadow', 'blur', 'partial-boundary', 'negative']
for split in ('tuning', 'held-out'):
    for index, category in enumerate(categories):
        offset = 0 if split == 'tuning' else 11
        w, h = 800, 600
        background = (35+offset, 42+offset, 48+offset)
        if category == 'paper-light': background = (225, 221, 214)
        image = Image.new('RGB', (w,h), background)
        draw = ImageDraw.Draw(image)
        q = [(140+offset,85), (660,105+offset), (635-offset,520), (160,505-offset)]
        if category == 'receipt': q = [(280,40),(500,65),(460,560),(250,535)]
        if category == 'card': q = [(130,175),(665,160),(680,465),(145,480)]
        if category == 'rotation': q = [(300,55),(700,270),(490,560),(95,350)]
        if category == 'perspective': q = [(260,60),(670,150),(740,510),(110,550)]
        if category == 'partial-boundary': q = [(-40,75),(630,75),(640,520),(-20,510)]
        paper = (244,241,234)
        if category == 'colored-form': paper = (193,221,235)
        if category == 'photo': paper = (160,110,73)
        truth = q
        if category == 'negative':
            truth = None
            for i in range(45):
                x=(i*97+offset*31)%w; y=(i*139+offset)%h
                draw.ellipse((x,y,x+28,y+25), fill=(70+i%30,80+i%30,100+i%30))
        else:
            draw.polygon(q,fill=paper)
            # Content is clipped to the true region; synthetic faint text, ink, stamps, highlight.
            content=Image.new('RGB',(w,h),paper); pen=ImageDraw.Draw(content)
            if category == 'photo':
                for y in range(h):
                    pen.line((0,y,w,y), fill=(40+int(120*y/h),90+int(80*y/h),170-int(80*y/h)))
                pen.ellipse((260,130,480,350), fill=(230,175,60))
                for x in range(80,750,7): pen.line((x,420,x+100,200),fill=(33,100,60),width=2)
            else:
                pen.text((310,160),'SYNTHETIC SCANNER BENCHMARK',fill=(40,40,40))
                for row in range(10):
                    y=220+row*20
                    shade=140 if row%3==0 else 45
                    pen.text((280,y),'item %02d  total 123.45  reference ABC' % row, fill=(shade,shade,shade))
                pen.rectangle((290,280,490,293),fill=(240,215,80))
                pen.line([(290,405),(320,390),(335,417),(365,397),(420,415)],fill=(25,60,125),width=3)
                pen.ellipse((425,415,510,460),outline=(170,50,55),width=3)
                pen.text((450,432),'STAMP',fill=(170,50,55))
            mask=Image.new('L',(w,h),0); ImageDraw.Draw(mask).polygon(q,fill=255)
            image.paste(content,(0,0),mask)
            if category == 'shadow':
                shade=Image.new('RGB',(w,h)); px=shade.load()
                for y in range(h):
                    for x in range(w):
                        f=.52+.48*x/w; r,g,b=image.getpixel((x,y)); px[x,y]=(int(r*f),int(g*f),int(b*f))
                image=shade
            if category == 'blur': image=image.filter(ImageFilter.GaussianBlur(4 if split=='tuning' else 7))
        case_id=split+'-'+category
        file=assets/(case_id+'.png'); image.save(file)
        normalized=None if truth is None else [[x/(w-1),y/(h-1)] for x,y in truth]
        outside=normalized if category=='partial-boundary' else None
        if outside is not None: normalized=None
        cases.append({'id':case_id,'split':split,'category':category,'file':file.name,
                      'corners':normalized,'unboundedCorners':outside,'expectedDetection': category not in ('negative','partial-boundary'),
                      'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
manifest={'license':'Apache-2.0','generator':'tools/generate_fixtures.py','width':800,'height':600,'cases':cases}
(assets/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
benchmark=root/'benchmarks'; benchmark.mkdir(exist_ok=True)
(benchmark/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Generated',len(cases),'original synthetic fixtures; 12 tuning and 12 held-out.')
