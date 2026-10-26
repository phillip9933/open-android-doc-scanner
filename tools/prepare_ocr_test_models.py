"""Fetch pinned public models into ignored instrumentation and sample-debug assets; never SDK release assets."""
from pathlib import Path
import hashlib
import urllib.request

REVISION = "87416418657359cb625c412a48b6e1d6d41c29bd"
MODELS = {
    "eng": "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    "jpn": "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d",
}
root = Path(__file__).resolve().parents[1] / "scanner-ocr-tesseract/build/ocr-test-assets"
root.mkdir(parents=True, exist_ok=True)
for language, expected in MODELS.items():
    target = root / f"{language}.traineddata"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        continue
    url = f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{REVISION}/{language}.traineddata"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Unexpected checksum for {language}")
    target.write_bytes(data)
print("English and Japanese test models verified. License: Apache-2.0 (tesseract-ocr/tessdata_fast).")
