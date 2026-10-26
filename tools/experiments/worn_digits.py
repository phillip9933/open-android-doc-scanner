"""Compare numeric OCR preprocessing on synthetic worn digits; no private inputs.
Requires Pillow, numpy, OpenCV and host Tesseract. Models must match card_ocr.py.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from concurrent.futures import ThreadPoolExecutor
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from card_ocr import EXPECTED


def deskew_line(a):
    # Estimate from pixels alone; do not use the synthetic source angle or expected text.
    height, width = a.shape
    ink = (a < 160).astype(np.uint8)
    best_angle, best_score = 0, -1
    for angle in range(-12, 13):
        matrix = cv2.getRotationMatrix2D((width/2, height/2), angle, 1)
        rotated = cv2.warpAffine(ink, matrix, (width, height), flags=cv2.INTER_NEAREST)
        score = float(np.square(rotated.sum(axis=1).astype(float)).sum())
        if score > best_score:
            best_angle, best_score = angle, score
    matrix = cv2.getRotationMatrix2D((width/2, height/2), best_angle, 1)
    return cv2.warpAffine(a, matrix, (width, height), flags=cv2.INTER_CUBIC, borderValue=255)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if hashlib.sha256((args.models/'eng.traineddata').read_bytes()).hexdigest() != EXPECTED['eng']:
        raise ValueError('Unapproved English model')
    args.output.mkdir(parents=True, exist_ok=True)
    fonts = ['/usr/share/fonts/noto/NotoSerif-Regular.ttf', '/usr/share/fonts/noto/NotoSans-Regular.ttf']
    jobs = []
    for font_path in fonts:
        for expected in ('08263859', '38180624'):
            for wear in (0, 1, 2):
                for angle in (-12, -7, -3, 0, 3, 7, 12):
                    im = Image.new('L', (500, 120), 255)
                    font = ImageFont.truetype(font_path, 48)
                    draw = ImageDraw.Draw(im)
                    draw.text((25, 20), expected, font=font, fill=30)
                    # Fixed-seed horizontal gaps model missing ink, not any owner's card.
                    if wear:
                        rng = np.random.default_rng(170+wear)
                        for _ in range(wear*10):
                            x, y = int(rng.integers(25, 255)), int(rng.integers(35, 75))
                            draw.rectangle((x, y, x+int(rng.integers(2, 9)), y+wear-1), fill=245)
                    im = im.rotate(angle, resample=Image.Resampling.BICUBIC, fillcolor=255)
                    a = np.asarray(im)
                    _, binary = cv2.threshold(a, 0, 255, cv2.THRESH_BINARY+cv2.THRESH_OTSU)
                    # Black strokes expand under erosion; compare rather than assume it helps.
                    variants = {'original':a, 'deskew':deskew_line(a), 'enlarge2x':cv2.resize(a,None,fx=2,fy=2,interpolation=cv2.INTER_CUBIC),
                                'otsu':binary, 'thicken':cv2.erode(binary,np.ones((2,2),np.uint8))}
                    case = f'{Path(font_path).stem}-{expected}-wear{wear}-angle{angle}'
                    for method, pixels in variants.items():
                        path=args.output/f'{case}-{method}.png'
                        Image.fromarray(pixels).save(path)
                        jobs.append((case, expected, method, path, wear))
    def run(job):
        case, expected, method, path, wear = job
        proc=subprocess.run(['tesseract',str(path),'stdout','--tessdata-dir',str(args.models),'-l','eng',
                             '--psm','7','-c','tessedit_char_whitelist=0123456789'],capture_output=True,text=True,
                            check=True,timeout=30,env={**os.environ,'OMP_THREAD_LIMIT':'1'})
        actual=proc.stdout.strip()
        return dict(case=case,expected=expected,method=method,actual=actual,exact=actual==expected,wear=wear)
    with ThreadPoolExecutor(max_workers=4) as executor:
        rows=list(executor.map(run,jobs))
    baseline={r['case']:r for r in rows if r['method']=='original'}
    summary={}
    for method in ('original','deskew','enlarge2x','otsu','thicken'):
        selected=[r for r in rows if r['method']==method]
        summary[method]=dict(cases=len(selected),exact=sum(r['exact'] for r in selected),
            recovered=sum(r['exact'] and not baseline[r['case']]['exact'] for r in selected),
            regressed=sum(not r['exact'] and baseline[r['case']]['exact'] for r in selected))
    result=dict(engine=subprocess.check_output(['tesseract','--version'],text=True).splitlines()[0],
                model_sha256=EXPECTED['eng'],limitation='Synthetic host diagnostic; not physical-device accuracy.',
                summary=summary,results=rows)
    (args.output/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(summary,indent=2))

if __name__=='__main__': main()
