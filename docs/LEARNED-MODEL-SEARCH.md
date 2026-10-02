# Learned document detector search

Historical research record. Current implementation and model evidence: [DocQuadNet-256](LEARNED-DETECTOR-RC8.md); current release: [RC10](ENHANCEMENT-RC10.md).
Research date: 2026-10-01. Recommendation: evaluate **MakeACopy DocQuadNet-256** with a pinned, official ONNX Runtime CPU build on Android. It has an explicit Apache 2.0 grant for the exported inference model, an existing offline Android deployment, and both document-mask and corner outputs. These establish a practical adoption candidate; they do not establish superior detection quality on this scanner's footage. Keep Standard as the default until comparative testing supports a change.

## Exact candidate and permission

Source repository: [egdels/makeacopy](https://github.com/egdels/makeacopy), revision `01bebd394b9dd6f3a692f28aea7c0638085eb4da`.

The pinned [NOTICE](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/NOTICE) expressly states: “The exported ONNX inference model is an independently created work and is licensed under the Apache License 2.0.” This is stronger model-specific permission than a repository license badge or a third-party mirror's label. The [LICENSE](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/LICENSE) supplies the Apache 2.0 terms. Preserve that license, applicable notices, Christian Kierdorf attribution, and modification notices for adapted source. Model conversion to ORT is a deployment format change; the shipped ORT artifact is the repository's inference model, not an unrelated training checkpoint.

| Artifact | Pinned value |
|---|---|
| Repository path | `app/src/main/assets/docquad/docquadnet256_trained_opset17.ort` |
| Download | [Exact inference artifact](https://raw.githubusercontent.com/egdels/makeacopy/01bebd394b9dd6f3a692f28aea7c0638085eb4da/app/src/main/assets/docquad/docquadnet256_trained_opset17.ort) |
| Bytes | 13,404,960 |
| SHA-256 | `f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a` |
| Research download | `work/model-license-search/egdels-makeacopy/app__src__main__assets__docquad__docquadnet256_trained_opset17.ort` |

The final model-grant sentence is placed after the separate PaddleOCR section and does not name DocQuadNet explicitly. Its application to DocQuadNet is an inference from the repository-wide Apache license, the included DocQuadNet artifact, and the DocQuadNet-specific training README describing that exported inference model as the shipped model. PaddleOCR's section independently enumerates ten artifacts with their own Apache grant. Preserve the complete NOTICE so this surrounding context remains reviewable; no maintainer clarification of the sentence's scope has been obtained.

No ONNX inference binary was present in the pinned tree. The ORT artifact therefore provides the reproducible deployment input; do not reconstruct or substitute weights from another project. The model's required-operator file lists Conv, Resize, Mul, HardSigmoid, GlobalAveragePool and Microsoft's FusedConv. Use an official full CPU runtime first, rather than copying MakeACopy's custom native libraries without a separate runtime audit. ONNX Runtime's MIT license and its third-party notices also belong in the SDK's dependency inventory.

## Training provenance caveat

Model permission and training-image permission are separate questions. MakeACopy's training documentation and NOTICE identify UVDoc, SmartDoc, DTD texture backgrounds, and receipt-related CORD training/evaluation. Exact per-checkpoint membership is not fully manifested. UVDoc has an [MIT license](https://github.com/tanguymagne/UVDoc-Dataset/blob/main/LICENSE); SmartDoc and CORD provide CC BY 4.0 materials. Do not redistribute their images in this SDK.

The [official DTD page](https://www.robots.ox.ac.uk/~vgg/data/dtd/) says the dataset is supplied for research purposes and explains that images were gathered from Google Images and Flickr. MakeACopy acknowledges missing explicit DTD license text and says those images and derived images are not shipped. This leaves a residual training-provenance concern; the explicit Apache inference-model grant is nevertheless present. No NC or research-only clause was found in the actual model's grant. Do not describe the model as having fully verified rights to every training image, nor infer automatically that dataset restrictions relicense the exported weights. This report is technical license evidence, not a legal warranty. No license-acceptance form was submitted and no maintainer was contacted.

## Android input and output contract

The pinned [Android adapter](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/app/src/main/java/de/schliweb/makeacopy/ml/corners/DocQuadDetector.java), [runner](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadOrtRunner.java), and [postprocessor](https://github.com/egdels/makeacopy/blob/01bebd394b9dd6f3a692f28aea7c0638085eb4da/app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadPostprocessor.java) establish:

- Input name `input`, float32 `[1,3,256,256]`, RGB divided by 255; no channel mean/std normalization.
- Bilinear aspect-preserving centered letterbox to 256 square, with **black padding**. Use upright source pixels and invert the same scale/offset when mapping detections back.
- Output `corner_heatmaps`: `[1,4,64,64]` raw logits, channel order TL, TR, BR, BL. Decode grid-cell centers as `(index + 0.5) * 4`; upstream provides subpixel refinement.
- Output `mask_logits`: `[1,1,64,64]` raw logits. A sigmoid greater than 0.5 identifies document-mask cells.
- Upstream uses two CPU threads and avoids NNAPI. Its reported device timing is not a measurement on our SDK or our test devices.

The mask is useful for checking whether four corners enclose the predicted document and for exploring outlines with imperfect visible corners. The final crop still uses a quadrilateral; this candidate does not implement unrestricted polygon cropping or simultaneous multiple-document capture.

Do not copy the upstream product's acceptance policy blindly: its adapter currently comments out the suspicious-result rejection. Validate finite coordinates, ordering, convexity, bounds, area, corner peak quality, and mask agreement before adapting to this SDK's shared `Detection` result. The same tracking, smoothing, scene-quality checks, automatic capture and page workflow must consume both detectors. A plausible quad alone is insufficient evidence to trigger capture.

Android initialization, exact output parity, latency, sustained memory, APK size, both ABIs, 16 KB native alignment, offline first launch, negative scenes and identical-footage comparisons remain release gates. Keep those measured results in release evidence rather than treating upstream benchmark claims as local verification.

## Other candidates examined

| Candidate | Permission and practical finding | Decision |
|---|---|---|
| Scanic DocCornerNet LEAN | Pinned `marquaye/scanic` revision `3633d5f9292948400c4a6809964e7f75c43661ec`. Its [scanic-ml package](https://github.com/marquaye/scanic/blob/3633d5f9292948400c4a6809964e7f75c43661ec/scanic-ml/package.json) explicitly lists the shipped model and MIT package license. Approximately 1.82 MB ONNX, RGB ImageNet-normalized NHWC 224 square, normalized corners plus a presence logit. Training uses DocCornerDataset, whose [card](https://huggingface.co/datasets/mapo80/DocCornerDataset/blob/main/README.md) labels some sources research-only and many Roboflow licenses unspecified. Some card labels also need correction against original datasets. | Smaller useful fallback candidate, but weaker provenance and no mask head; prioritize DocQuadNet. |
| DocAligner LCNet100 | Code Apache 2.0; exact 4.77 MB checkpoint already inspected. The official linked checkpoint permission remains insufficiently explicit. | Preserve earlier evaluation; do not substitute a mirror's Apache label for an upstream checkpoint grant. |
| FairScan segmentation | Available model repository uses GPL 3.0. | Do not bundle into this Apache 2.0 SDK under the present permissive dependency requirement. |
| Pagescan YOLO/HQ-SAM cascade | [Model card](https://huggingface.co/7rplus/pagescan-weights/blob/main/README.md) explicitly licenses its own YOLO export Apache 2.0, but says it was fine-tuned from Ultralytics YOLO11 weights. That upstream licensing needs separate resolution. HQ-SAM is far larger (362 MB). Its fallback DeepLab model has expressly unresolved origin. | Poor Android size/runtime fit; an Apache label alone does not resolve upstream YOLO rights. |
| Jwalit KYC detector | [Model card](https://huggingface.co/Jwalit/kyc-document-corner-detector/blob/main/README.md) delegates permission to its dataset and supplies no clear artifact license. Labels originate from OpenCV-generated masks. | Insufficient permission and uncertain advantage over current detector. |

Research snapshots and exact downloaded source references are retained in `work/model-license-search/`; only deliberately adopted inference assets and relevant notices should enter release outputs. No private reference screenshots, training datasets, or research-only checkpoints are release assets.
