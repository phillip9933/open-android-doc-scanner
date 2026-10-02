# DocAligner offline Android evaluation

Historical research record. Current implementation and model evidence: [DocQuadNet-256](LEARNED-DETECTOR-RC8.md); current release: [RC10](ENHANCEMENT-RC10.md).
Evaluation date: 2026-10-01. **Decision: do not adopt or bundle pretrained weights yet.** Code licensing is evidenced; a grant covering commercial use and redistribution of the exact pretrained checkpoint is unresolved. RC2, processing code and release artifacts remain unchanged. This is a bounded licensing and static-interface evaluation, not an Android performance evaluation.

## Licensing and provenance

The official repository at revision `3275b0f07f8e99d8c01cb0774dea2549be1416b6` provides an [Apache 2.0 code license](https://github.com/DocsaidLab/DocAligner/blob/3275b0f07f8e99d8c01cb0774dea2549be1416b6/LICENSE). This supports use of the licensed code subject to its conditions. The [heatmap checkpoint README](https://github.com/DocsaidLab/DocAligner/blob/3275b0f07f8e99d8c01cb0774dea2549be1416b6/docaligner/heatmap_reg/ckpt/README.md) links a public Google Drive folder but supplies no separate weight license. Inspection of that folder, the repository tree and the candidate's model metadata found no explicit grant identifying these weight files. The repository license might be intended to cover them, but that scope is not sufficiently explicit for this project's adoption gate. Public download access and a third-party mirror's license label do not settle redistribution rights.

The [official dataset description](https://docsaid.org/en/docs/docaligner/dataset/) names SmartDoc, MIDV-500/2019/2020, CORD, online-collected document images and an indoor-background collection. It does not bind the exact candidate to a complete training manifest or document all source-image rights. [SmartDoc's author repository](https://github.com/jchazalon/smartdoc15-ch1-dataset) and [CORD's official repository](https://github.com/clovaai/cord) state CC BY 4.0; attribution obligations apply to those datasets. These dataset licenses do not independently license the exported model. MIDV source-document rights vary: the [MIDV-500 author paper](https://arxiv.org/abs/1807.05786) describes public-domain/public-copyright-license sources, while the [MIDV-2020 university distribution page](https://l3i-share.univ-lr.fr/MIDV2020/midv2020.html) requires its own license acceptance. Full MIDV terms and the online/indoor collection's provenance remain unresolved here; no acceptance form was submitted. Do not claim a uniform unrestricted dataset license.

Required clearance: an official statement applying a redistribution/commercial-use license to an identified checkpoint/hash, plus sufficient training-data provenance and restrictions to review the intended distribution. No checkpoint is declared prohibited; permission is presently unproven.

## Smallest candidate and exact artifact

The [published benchmark](https://docsaid.org/en/docs/docaligner/benchmark/) lists a **heatmap LCNet050** model at approximately 1.7 MB. However, the inspected official folder contains only LCNet100 heatmap, FastViT-T8 heatmap, FastViT-SA24 heatmap and LCNet050 point-regression files. Current heatmap configuration also exposes only LCNet100/T8/SA24. Therefore the smallest *documented* heatmap candidate cannot be pinned or inspected from the current official release; request its exact release and license before treating it as available.

Among the four currently linked binaries, the smallest is **`lcnet100_h_e_bifpn_256_fp32.onnx`**, smaller than the 4,911,217-byte LCNet050 point model. The [official download folder](https://drive.google.com/drive/folders/1wQ8RDsBDqdPT4COt-VSgthu81Bw1uO97) and [individual LCNet100 file](https://drive.google.com/file/d/1IlbLPkCv-TdaBLOPh_4J97P1_KHYYJ7a/view) establish provenance.

Measured from a research-only download:

- File size: **4,767,987 bytes** (about 4.55 MiB).
- SHA-256: `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e`.
- ONNX IR 8, standard opset 16, producer PyTorch 1.14.0; 421 nodes, 150 initializers, no external initializer data or custom operator domains.
- Input `img`: float32 `[1,3,256,256]`. Sole output `heatmap`: float32 with four channels; batch/spatial dimensions are symbolic in the serialized graph. Runtime output dimensions have not been measured.
- Operators include Conv, Resize, ReduceSum, Div and **20 Einsum nodes**. Model metadata contains parameter/computation estimates and an upstream desktop timing estimate, but no license.

The checkpoint and inspection evidence are confined to `work/docaligner-evaluation/`, explicitly research-only/do-not-bundle. A standard-library protobuf reader inspected metadata; neither an ONNX checker nor inference was executed. The upstream benchmark's accuracy figures and embedded desktop timing are **reported upstream, not reproduced measurements**, and establish no Android quality or latency.

## Input, output and integration contract

The pinned [heatmap inference implementation](https://github.com/DocsaidLab/DocAligner/blob/3275b0f07f8e99d8c01cb0774dea2549be1416b6/docaligner/heatmap_reg/infer.py) resizes to 256×256, converts HWC to CHW, casts to float32 and divides by 255; it does not apply mean/std normalization or swap channels. The reference example supplies BGR images. Architecture prose mentions RGB, so a port must establish channel-order parity against this implementation rather than assume RGB. Preserve original aspect mapping after the square resize; default center crop is disabled.

Reference decoding upsamples each heatmap to the image dimensions, zeros values below 0.3, binarizes and selects the centroid of the largest connected polygon. Missing peaks can produce fewer than four points. The inspected heatmap checkpoint exposes **no separate document-presence output or calibrated confidence**. A port needs explicit rejection rules, verified corner ordering, convexity/bounds checks and a confidence calibration; a complete quadrilateral alone must not authorize capture. The point model exposes points/has_obj, but [official guidance](https://docsaid.org/en/docs/docaligner/advance/) describes point-regression results as unsatisfactory and research-oriented.

Both live and imported images must use the same upright normalized `Detection` contract and common EXIF/scene-quality/tracking path described in [AI-DETECTION-GATES.md](AI-DETECTION-GATES.md).

## Offline runtime choice

| Option | Evidence and tradeoff | Evaluation decision |
|---|---|---|
| OpenCV DNN | Existing native build excludes DNN. [OpenCV 4.12's ONNX importer](https://github.com/opencv/opencv/blob/4.12.0/modules/dnn/src/onnx/onnx_importer.cpp) includes Einsum parsing, making this a credible candidate; exact equations, Resize semantics and output parity remain untested. Adding DNN requires a new source build, dependency/notices audit and native alignment verification. | Compare only after weight clearance; do not replace the audited minimal native libraries with the stock AAR. |
| ONNX Runtime CPU | Upstream DocAligner uses ONNX Runtime. Its [MIT license](https://github.com/microsoft/onnxruntime/blob/main/LICENSE), [Android deployment guide](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html) and [compatibility table](https://onnxruntime.ai/docs/reference/compatibility.html) provide a plausible offline path for this standard IR8/opset16 graph. Bundled local assets require no model download. Runtime dependencies still need notice/provenance review. | Preferred first parity experiment after clearance, with a pinned runtime and CPU execution. A reduced custom build is a later size optimization; no version is adopted in this pass. |

Neither option has been built or executed for this model. API 26, arm64-v8a/x86_64 packaging, 16 KB page compatibility, initialization/failure handling, APK size, cold start, latency, memory, sustained throughput and physical-device thermal behavior remain **unmeasured**. No Android inference or accuracy comparison was performed. Run the identical held-out real-camera comparison and shared capture gates in AI-DETECTION-GATES.md before enabling an AI setting or considering a default change. Standard OpenCV detection remains the shipped implementation.
