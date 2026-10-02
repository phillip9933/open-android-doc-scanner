"""Regression gates established after initial API35 baseline; no detector tuning on held-out."""
import json, sys
from pathlib import Path
path=Path(sys.argv[1] if len(sys.argv)>1 else 'evidence/benchmark-api35.json')
report=json.loads(path.read_text(encoding='utf-8-sig'))
held=report['bySplit']['held-out']
checks={
 'held-out detection >= 80%': held['detectionRateOnPositives']>=.8,
 'held-out false positives = 0': held['falsePositiveCount']==0,
 'held-out IoU including misses >= .79': held['meanPolygonIoUIncludingMisses']>=.79,
 'matched corner error <= .005': held['meanNormalizedCornerErrorOnMatches']<=.005,
 'emulator warmed analysis p95 <= 100ms': report['aggregate']['p95ElapsedMillis']<=100,
}
for name,passed in checks.items(): print(('PASS ' if passed else 'FAIL ')+name)
if not all(checks.values()): sys.exit(1)
print('Synthetic regression gates passed. Physical image-quality acceptance remains separate.')
