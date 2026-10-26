"""Verify the committed OCR native/Java inputs and ELF hardening; no network."""
from pathlib import Path
import hashlib, json, struct
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root/'evidence/ocr-native-build.json').read_text())
expected = {f'scanner-ocr-tesseract/src/main/jniLibs/{abi}/lib{name}.so'
            for abi in ('arm64-v8a','x86_64') for name in ('jpeg','pngx','leptonica','tesseract')}
assert {x['file'] for x in manifest['libraries']} == expected
assert {str(p.relative_to(root)) for p in (root/'scanner-ocr-tesseract/src/main/jniLibs').rglob('*.so')} == expected
assert {x['file'] for x in manifest['java_sources']} == {str(p.relative_to(root)) for p in (root/'scanner-ocr-tesseract/src/main/java').rglob('*.java')}
for item in manifest['libraries'] + manifest['java_sources']:
    path = root/item['file']
    assert path.resolve().is_relative_to(root.resolve())
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == item['sha256'], f'Hash mismatch: {path.name}'
    if path.suffix != '.so': continue
    assert data[:6] == b'\x7fELF\x02\x01'
    offset = struct.unpack_from('<Q',data,32)[0]
    size,count = struct.unpack_from('<HH',data,54)
    headers = [struct.unpack_from('<IIQQQQQQ',data,offset+i*size) for i in range(count)]
    assert all(h[7] >= 16384 for h in headers if h[0] == 1), path.name
    assert any(h[0] == 0x6474e552 for h in headers), path.name
    assert any(h[0] == 0x6474e551 and not h[1]&1 for h in headers), path.name
    dynamic = next(h for h in headers if h[0] == 2)
    tags = [struct.unpack_from('<QQ',data,o) for o in range(dynamic[2], dynamic[2]+dynamic[5],16)]
    assert any(t == 24 or (t == 30 and v&8) or (t == 0x6ffffffb and v&1) for t,v in tags), path.name
print('OCR source/binary checksums, 16 KB alignment, non-executable stack and full RELRO passed (8 libraries).')
