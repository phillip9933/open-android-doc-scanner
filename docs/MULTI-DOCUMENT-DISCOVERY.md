# Multi-document capture feasibility

Discovery for issue #4, 26 October 2026. No production multi-document mode is enabled by this experiment.

## Benchmark and result

`tools/experiments/multi_document.py` generates 13 deterministic, synthetic 1200 × 900 scenes: separated pages, four cards, rotated pages, mixed sizes, overlap, touching edges, clipping, low contrast, texture crossing paper, shadow, glare, blur and a negative frame. Intended first-use documents are flat rectangular pages and printed cards with all four edges visible. No personal photographs or downloaded models are used.

The offline CPU prototype uses bounded contour proposals, quadrilateral approximation, overlap suppression and provisional row ordering. It is deliberately simpler than the production single-page detector; its measurements do not describe the production detector's accuracy. A match requires one-to-one polygon IoU ≥ 0.80. The JSON reports missed/false detections, split/merge counts, duplicate suppression, clipping, order and per-frame time. Ordering is measured among matched documents; false positives are reported separately.

Run with Python, NumPy and OpenCV installed:

```sh
python3 tools/experiments/multi_document.py --output evidence/multi-document-discovery.json
```

[Measured results](../evidence/multi-document-discovery.json): the prototype found all intended pages/cards in separated, cards, rotated, mixed, shadow, glare and blur cases. Touching and overlapping sheets merged into one false proposal. Low-contrast sheets were missed. Cross-page texture produced seven false proposals and split both pages. The clipped document was rejected while the complete one was retained. No duplicate pair remained after suppression. The negative frame produced no detections.

Host CPU time was approximately 1–9 ms per scene in this run. Peak process RSS was about 164 MiB, including Python, OpenCV, generated images and the benchmark itself; it is **not** Android peak working memory or an incremental detector allocation. One synthetic instance per condition is a feasibility probe, not a statistical accuracy estimate. Real-camera performance and device memory still need a separate implementation acceptance study.

## Decision and implementation proposal

Feasible for well-separated, high-contrast documents; not safe to ship as automatic multi-page capture on the evidence above. Keep this out of 0.8.0 and do not promise a delivery date. The current production API returns one `Detection`; the learned detector also targets a single document. Neither can be treated as a multi-instance detector simply by changing a setting.

Proposed follow-up implementation:

1. Add a separate bounded multi-candidate API rather than changing the single-page contract. Reuse production side-evidence validation, cancellation and resource budgets; cap candidate count and minimum area. Preserve the original once and render confirmed crops sequentially.
2. Reject ambiguous overlap/clipped contours instead of inventing hidden corners. Add containment-aware suppression to remove text/table rectangles while preserving distinct pages. Compare classical proposals with a multi-instance model only if its license, offline size and provenance are acceptable.
3. Show numbered, editable quadrilaterals and let users add/remove/reorder them before committing pages. Row ordering is only an initial suggestion. A failed render must not silently omit a document or overwrite the original.
4. Before scheduling release, test a held-out consented/synthetic photographed corpus across paper/card sizes, perspective, backgrounds and lighting. Measure per-instance recall, false splits/merges, corner error, ordering, duplicate rate, latency and peak Android PSS. Include cancellation, partial failure, restart and output-ownership tests.

The exploration is complete; production implementation and physical acceptance remain future work. This document records the decision and benchmark, while GitHub remains the live work list.
