"""Make a labeled same-source comparison from instrumentation evidence (Pillow required)."""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument('evidence', type=Path, help='Pulled processing test external-files directory')
args = parser.parse_args()
names = [
    ('scanner-bw-source-synthetic-shadow.jpg', 'Identical synthetic source'),
    ('scanner-bw-before-fixed-gaussian-31-c12.png', 'Previous fixed Gaussian (31, C=12)'),
    ('scanner-bw-auto-cleaned-grayscale.png', 'New Auto (continuous grayscale)'),
    ('scanner-bw-new-adaptive-gaussian.png', 'New manual Black and white'),
]
board = Image.new('RGB', (1200, 560), '#dddddd')
draw = ImageDraw.Draw(board)
for index, (name, label) in enumerate(names):
    with Image.open(args.evidence / name) as source:
        crop = source.convert('RGB').crop((80, 320, 840, 450))
        crop.thumbnail((620, 110))
        board.paste(crop, (30, 30 + index * 140))
        pale = source.convert('RGB').crop((80, 550, 840, 680))
        pale.thumbnail((490, 110))
        board.paste(pale, (700, 30 + index * 140))
    draw.text((30, 10 + index * 140), label, fill='black')
board.save(args.evidence / 'comparison.png')
