"""Synthetic card OCR diagnostic; host Tesseract results are not Android acceptance.

Usage: python3 tools/experiments/card_ocr.py --models DIR --output DIR
Requires Pillow and host tesseract. No user images or network access.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from PIL import Image, ImageDraw, ImageFont, ImageOps, ImageFilter

EXPECTED = {'eng': '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2',
            'jpn': '1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d'}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--models', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--font', default='/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc')
    args = p.parse_args()
    for lang, digest in EXPECTED.items():
        if hashlib.sha256((args.models / f'{lang}.traineddata').read_bytes()).hexdigest() != digest:
            raise ValueError(f'Unapproved {lang} model')
    args.output.mkdir(parents=True, exist_ok=True)
    card = Image.new('RGB', (1400, 850), 'white')
    d = ImageDraw.Draw(card)
    font = ImageFont.truetype(args.font, 54, index=0)
    small = ImageFont.truetype(args.font, 32, index=0)
    d.rectangle((20, 20, 1380, 830), outline='#008e81', width=12)
    d.text((80, 60), '会員番号', font=font, fill='black')
    d.text((80, 150), '0012345678', font=font, fill='black')
    d.text((80, 280), 'Name: SAMPLE TEST', font=font, fill='black')
    d.text((80, 390), '山田 太郎', font=font, fill='black')
    d.rectangle((40, 540, 1360, 810), fill='#008e81')
    d.text((80, 555), 'ご本人以外はご利用できません', font=small, fill='white')
    d.text((80, 650), 'www.example.com', font=font, fill='white')
    page = Image.new('RGB', (1800, 2400), 'white')
    page.paste(card.resize((840, 510)), (480, 180))
    variants = {'tight_card': card, 'card_on_page': page,
                'number_region': ImageOps.expand(card.crop((60, 140, 650, 230)), 20, 'white'),
                'light_on_color': card.crop((50, 545, 1350, 635)),
                'light_on_color_inverted': ImageOps.invert(ImageOps.grayscale(card.crop((50, 545, 1350, 635))))}
    # Paired variants keep the source pixels identical when testing enlargement.
    for width in (700, 350):
        reduced = card.resize((width, round(850*width/1400)), Image.Resampling.LANCZOS)
        for radius in (0, 0.6):
            degraded = reduced.filter(ImageFilter.GaussianBlur(radius))
            name = f"card_{width}px_blur{radius}"
            variants[name] = degraded
            variants[name + '_upscaled'] = degraded.resize((1400, 850), Image.Resampling.LANCZOS)
    results = []
    for name, im in variants.items():
        image = args.output / f'{name}.png'
        im.save(image)
        for lang in ('eng+jpn', 'jpn', 'eng'):
            for psm in (3, 11, 7):
                start = time.perf_counter()
                r = subprocess.run(['tesseract', str(image), 'stdout', '--tessdata-dir', str(args.models),
                                    '-l', lang, '--psm', str(psm)], capture_output=True, text=True,
                                   timeout=30, check=True, env={**os.environ, 'OMP_THREAD_LIMIT': '1'})
                text = r.stdout.strip()
                results.append(dict(image=name, languages=lang, psm=psm, text=text,
                                    exact_number='0012345678' in text,
                                    exact_heading='会員番号' in text.replace(' ', ''),
                                    exact_japanese='ご本人以外はご利用できません' in text.replace(' ', ''),
                                    milliseconds=round((time.perf_counter()-start)*1000)))
    report = dict(engine=subprocess.check_output(['tesseract', '--version'], text=True).splitlines()[0],
                  limitation='Synthetic host diagnostic only; not photographed-card or Android acceptance.',
                  model_sha256=EXPECTED, results=results)
    (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    for name in variants:
        rows = [r for r in results if r['image'] == name]
        print(name, 'exact number', sum(r['exact_number'] for r in rows), '/9;',
              'exact Japanese', sum(r['exact_japanese'] for r in rows), '/9')

if __name__ == '__main__':
    main()
