# Learned detector evaluation for RC8

DocQuadNet-256 loads and runs offline on the Android API 35 x86_64 emulator with
official ONNX Runtime Android 1.24.1 CPU execution. **It has not demonstrated
better detection than Standard in the frozen synthetic comparisons.** Runtime
compatibility and an inference-weight license are necessary, but do not establish
product quality. Keep Standard the default and do not advertise this model as a
detection improvement based on these results.

## Exact implementation and provenance

- Upstream: [MakeACopy](https://github.com/egdels/makeacopy/tree/01bebd394b9dd6f3a692f28aea7c0638085eb4da).
- Asset: `app/src/main/assets/docquad/docquadnet256_trained_opset17.ort`,
  13,404,960 bytes, unmodified, SHA-256
  `f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a`.
- Input: float32 `input` `[1,3,256,256]`, RGB divided by 255, aspect-preserving
  centered black letterbox. The filtered Canvas resize matches the pinned Android
  reference implementation. The training script uses rounded PIL resize/paste;
  this is an existing upstream training/runtime distinction.
- Outputs: float32 `corner_heatmaps` `[1,4,64,64]` in TL/TR/BR/BL order and
  `mask_logits` `[1,1,64,64]`. Both are logits; no extra input normalization.
- Local 3x3 softmax centroid refines each peak; grid cell centers map to 256-space
  at `(index + 0.5) * 4`, then inverse letterbox and source normalization.
- Finite, clockwise convex geometry must occupy 0.015 to 0.95 of the image.
  Minimum corner peak probability is 0.55, foreground mask area is 0.015 to 0.95,
  and mask/quad intersection over union is at least 0.55. No bounding-box fallback
  creates corners when evidence is absent. Confidence is minimum peak probability
  times mask/quad agreement, capped at 0.94; it is not calibrated as a probability
  of geometrically correct detection.
- Both modes return the shared core `Detection`, including the same raw scene
  signature and rectified document ROI sharpness/brightness. EXIF files use the
  same upright visual coordinate convention. No Standard contour fallback is
  hidden in the learned detector.
- CPU runtime uses at most two intra-op threads and one inter-op thread. Session
  construction is lazy on worker inference, access is serialized, and close waits
  for an active inference. The process-wide OrtEnvironment stays shared. No
  NNAPI, Play services, downloads, accounts, or network calls are required.

### Runtime choice

The source-built OpenCV artifact currently excludes its DNN module. The pinned
redistributable model is an ORT-format graph, which OpenCV DNN cannot consume
directly. No matching upstream ONNX export is present at this pin. Official ONNX
Runtime Android CPU therefore provides a suitable offline path for evaluating
the exact licensed artifact without rebuilding OpenCV or changing the model.
The API 35 instrumentation run establishes that this artifact's operators work
with ORT 1.24.1. No DNN performance comparison has been run, and the latency table
must not be interpreted as ORT outperforming OpenCV DNN. A later DNN comparison
would require a licensed matching ONNX graph and a separately audited DNN build.

The bundled `docquad/model-manifest.json` pins the artifact hash, byte length,
upstream revision, input/output conventions, runtime coordinate and license/
notice paths. The release audit must verify those paths and bytes in the exact
APK; the manifest does not itself prove build provenance or model quality.

## Licenses

The pinned root [LICENSE](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/LICENSE)
is Apache-2.0. The final paragraph of the pinned
[NOTICE](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/NOTICE)
grants Apache-2.0 to its independently created exported ONNX inference model.
Applying this grant to DocQuadNet is a scope interpretation: the grant is singular
and unqualified, the DocQuadNet training README identifies its exported inference
model as the shipped artifact, and the preceding Paddle section separately
licenses ten OCR artifacts. No maintainer clarification or legal review was
obtained. The complete pinned NOTICE and Apache license are bundled beside a
README stating this interpretation.

Training-image provenance remains a limitation. In particular, upstream reports
DTD background augmentation; its original dataset lacks an explicit included
license and its website describes research use. The model grant is Apache-2.0,
but this does not clear individual training-image rights. No DTD, SmartDoc,
CORD, or other training dataset images or annotations ship with this SDK.

Official ONNX Runtime Android 1.24.1 is MIT licensed. The official versioned
LICENSE and full ThirdPartyNotices are bundled. The exact APK's native inventory
and 16KB alignment must be audited separately; inference on this emulator alone
does not establish arm64 performance or 16KB device support.

## Measured Android comparison

Evidence: [raw API 35 results](../evidence/learned-comparison-api35-rc8.json) and
[aggregate/stability calculations](../evidence/learned-comparison-summary-api35-rc8.json).
There are 78 identical held-out images per detector, measured sequentially in one
test: 12 original cases, 10 challenge cases and 56 frames of the frozen shadow
sequences. All fixtures are original deterministic synthetic scenes; these are
not real handheld camera footage and were not used to train DocQuadNet. Detector
thresholds were fixed before this held-out run. Held-out outcomes must not be
used to tune them.

| Held-out cohort | Mode | Detected positives | False positives | Mean polygon IoU including misses | Mean corner error on matches | Warm p95 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Original | Standard | 10/10 | 0/2 | 0.996275 | 0.000840 | 24.316 |
| Original | AI | 3/10 | 0/2 | 0.238625 | 0.060122 | 29.986 |
| Challenge | Standard | 5/6 | 0/4 | 0.830860 | 0.000701 | 39.765 |
| Challenge | AI | 1/6 | 0/4 | 0.041178 | 0.244888 | 23.843 |
| Shadow sequences | Standard | 29/32 | 0/24 | 0.903545 | 0.000838 | 18.516 |
| Shadow sequences | AI | 0/32 | 0/24 | 0.000000 | Not measurable | 26.343 |

Timing includes preprocessing, inference, postprocessing, ROI quality and scene
signature; image decoding is excluded. Nearest-rank p95 is used. The recorded
cold-start scope includes first learned inference and Standard warmup together:
236.990 ms. It is not a model-only startup measurement. These are emulator
measurements under one run, not a phone latency or battery/thermal claim.

Standard has 23 adjacent detected positive pairs in the temporal sequences, two
observed-to-missing transitions, mean raw corner motion 0.005470 and mean residual
jitter after subtracting ground-truth motion 0.000316 in normalized coordinates.
AI has no matched adjacent positive pairs. Its zero observed-to-missing
transitions mean it never locked on; they must not be described as stable
tracking. Overlay smoothing is excluded from this raw detector comparison.

## Confidence and capture implications

At the existing automatic capture minimum confidence of 0.72, the accepted
positive AI candidates number only 1/10 original, 1/6 challenge and 0/32 temporal
frames. Other quality, motion and persistence gates can reduce actual automatic
captures further. The accepted challenge candidate has confidence about 0.762
but polygon IoU only about 0.247 and normalized corner error about 0.245.
Consequently mask/heatmap agreement is insufficient evidence of correct document
location. Lowering the automatic capture confidence threshold would not address
this calibration problem and risks accepting incorrect crops.

## Verified integration checks and next gate

### Sustained CPU exercise

The opt-in API 35 exercise performed 1,200 actual complete detector calls on one
original printed bitmap, explicitly paced at a 100 ms interval over 120.001
seconds. Every call includes preprocessing, ORT inference, postprocessing, ROI
quality and the scene signature. There were zero exceptions and zero corner/
confidence changes outside the 0.000001 tolerance. Evidence:
[sustained report](../evidence/learned-sustained-api35-rc8.json).

| Warm call window | p50 ms | p95 ms |
| --- | ---: | ---: |
| First 100 | 21.608 | 25.542 |
| Last 100 | 17.665 | 19.929 |
| All 1,200 | 17.587 | 23.541 |

The slowest call was 34.265 ms. Last-window p95 was about 0.780 of first-window
p95; this run showed no latency increase over the two minutes. The explicit
100 ms pacing means this is a 10 Hz pipeline exercise, not continuous maximum
CPU load, camera footage, a phone thermal test, or a battery measurement.

After requested GC with the detector/session and bitmap still alive, managed
heap endpoints were 15,680,640 to 15,668,352 bytes (difference -12,288), and native
allocator endpoints were 46,182,768 to 46,190,096 bytes (difference +7,328).
These endpoint samples show no large retained growth in this bounded run; they
are not peak RSS measurements or proof that all possible leaks are absent.

Separate initialization measurements were OrtEnvironment initialization 37.748
ms, reading the model asset 59.758 ms and creating a new CPU session from those
bytes 39.171 ms. A direct first inference plus session close took 32.678 ms;
close is included, so this is not a pure model inference figure. Creating a new
`LearnedDocumentDetector` and running its first full pipeline call took 263.855
ms, including its own model read/integrity check/session construction and first
quality work. These timings are scoped separately and should not be summed into
an invented startup figure.

The full 25 processing tests also passed on the 16KB page-size emulator, and the
settings/review/save workflows passed API 35 and large-font 16KB checks. This
confirms the tested Android integration, not arm64 phone performance. The exact
release native alignment audit remains a separate artifact check.

### Small licensed real-camera diagnostic

A separate opt-in test loaded three unmodified real camera frames from SmartDoc
2015 Challenge 1, official dataset release v2.0.0. Only 122,880 compressed bytes
were read before stopping the archive download. The author's dataset README
licenses the actual dataset under CC BY 4.0. The JPEGs were pushed into the test
app's external directory; they are not bundled into main/test APK assets, Maven
artifacts, or the source release. The results retain source paths, hashes,
license evidence and attribution without including the copyrighted image pixels.

Evidence: [camera results](../evidence/learned-camera-api35-rc8.json),
[fixture metadata](../evidence/learned-camera-fixture-manifest-rc8.json), and
[full attribution](../evidence/learned-camera-attribution-rc8.md). Dataset credit:
Jean-Christophe Burie, Joseph Chazalon, Mickaël Coustaty, Sébastien Eskenazi,
Muhammad Muzzamil Luqman, Maroua Mehri, Nibal Nayef, Jean-Marc OGIER, Sophea Prum
and Marçal Rusinol, *ICDAR2015 Competition on Smartphone Document Capture and OCR
(SmartDoc)*, ICDAR 2015.

| Camera frame | Standard detected | AI detected | AI confidence | AI IoU | AI document sharpness |
| --- | --- | --- | ---: | ---: | ---: |
| background01/datasheet001/frame_0001 | No | Yes | 0.9288 | 0.98315 | 2.643 |
| background01/datasheet001/frame_0002 | No | Yes | 0.9338 | Not annotated | 2.823 |
| background01/datasheet001/frame_0003 | No | Yes | 0.9400 | Not annotated | 109.637 |

Frame 1 uses the exact author-published corner example, reordered to TL/TR/BR/BL
and normalized against the verified 1920x1080 image size. Its normalized corner
error is 0.003545. Frames 2 and 3 have no local ground truth; detection alone
does not establish their crop accuracy. The first AI inference includes cold
initialization (232.393 ms); the next two run in about 34.601 and 26.943 ms. This
three-frame smoke test is not a latency benchmark.

The first two frames illustrate why detection and capture quality are separate:
the learned outline is present, but their document sharpness is below the
existing 60 threshold and automatic capture should wait. The third frame has
greater measured detail. A stable outline alone must not accept a blurry page.

These are adjacent frames of one camera sequence, not diverse independent test
cases. DocQuadNet's training includes SmartDoc; exact sequence overlap is
unverified. Thus the promising camera result supports an experimental testing
option, but cannot justify a general accuracy claim or a default switch.

### Printed-text exploratory diagnostic

After the frozen comparison, five original Canvas-generated pages were used as
an explicitly exploratory integration diagnostic. They contain readable original
test text, a dark/light background, a cross-page shadow, one removed corner, or
receipt text. The page quadrilateral is identical across these cases; these are
not five independent real-camera scenes. No private images, external document
text, or training dataset photos are used. Thresholds were not changed for them.
Evidence: [printed diagnostic](../evidence/learned-printed-api35-rc8.json).

| Printed-text case | Standard detected | Standard IoU | AI detected | AI IoU | AI confidence |
| --- | --- | ---: | --- | ---: | ---: |
| Dark background | Yes | 0.996 | Yes | 0.984 | 0.880 |
| Light background | Yes | 0.996 | Yes | 0.973 | 0.814 |
| Shadow across page | Yes | 0.996 | Yes | 0.987 | 0.737 |
| Removed corner | No | 0.000 | Yes | 0.964 | 0.866 |
| Receipt text | Yes | 0.996 | Yes | 0.984 | 0.822 |

This is useful evidence that inference and coordinate decoding work on printed
pages. The removed-corner case is promising for the user's reported issue:
learned content/mask evidence can support a document whose outline does not
supply four complete physical corners. AI still returns one quadrilateral, not
an arbitrary contour or a simultaneous multi-document result. The evidence
supports making an experimental option available for testing, while retaining
Standard as the default. It does not support a general accuracy superiority
claim or a default change.

### Tuning rejection diagnosis

The separate [tuning diagnostic](../evidence/learned-tuning-api35-rc8.json)
records raw peak evidence, mask fraction/agreement and rejection reasons. AI
accepts 2/10 tuning positives in the original cohort, 0/6 in the challenge and
0/32 temporal frames, with zero false positives on 30 tuning negatives. Original
rejections are eight low-corner-evidence cases, one mask-disagreement case and
one invalid-geometry case. Challenge has six low-corner-evidence cases and four
invalid-geometry cases; temporal has 33 low-corner-evidence and 23 invalid-geometry
cases. These counts include negatives. Peak probabilities in the temporal tuning
cohort range only from about 0.010 to 0.326. Lowering the capture threshold cannot
repair missing geometry or establish correct document presence.

The contrast with printed pages suggests a semantic/domain mismatch with sparse
synthetic shapes, rather than a RGB/BGR, output-name, or coordinate-order mistake.
This is an inference from the diagnostic, not proven causation. The preprocessing
matches the upstream Android implementation, and the separately validated
rotated/mirrored file path uses identical upright bitmap outputs.

The API 35 instrumentation run passed learned offline model loading, uniform-scene
rejection, cancellation, close/reuse protection, caller bitmap ownership and
rotated/mirrored EXIF file equivalence. It also completed the matched comparison
with finite shared outputs. These checks establish implementation compatibility,
not improved document detection.

Investigate separately on tuning inputs and original printed-text scenes for
domain mismatch or decoder errors; add a small explicitly licensed real-camera
set with corner ground truth where available. Preserve the negative cases and
compare identical inputs. Do not switch the default or present AI as a quality
upgrade until independent camera examples support it. Simultaneous multiple
document detection remains a separate future feature.
