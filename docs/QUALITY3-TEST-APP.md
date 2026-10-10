# Quality3: background cleanup and clearer review

Historical tuning record. The published successor is [RC12](RELEASE-RC12.md); candidate-only statements below describe that earlier stage.

Local candidate `0.1.0-rc12-quality3`, sample version code 14. Builds on [quality2](QUALITY2-TEST-APP.md); export folders, original sharing and native output budgets remain available.

Auto now flattens estimated paper illumination toward white with a bounded additive correction. A smooth shoulder reduces near-paper neutral marks such as weak reverse-side print. Stronger pale strokes bypass that shoulder; colored marks retain channel differences. This is continuous-tone processing, not binary thresholding. It cannot reliably distinguish all faint front-side writing from reverse-side print. Colored stains and strong bleed-through can remain; inspect faint originals when assessing cleanup.

Selected review/save pages and the appearance comparison render at up to 3072 pixels along the long edge, respecting the configured output limit. Thumbnails remain at 1024. Preview cache references are bounded to 48 MiB (displayed bitmaps and processing allocations are additional). Preview PNGs decode without the former RGB565 conversion. Pinch/double-tap zoom draws the detailed bitmap at the requested scale instead of enlarging a screen-sized graphics layer. The preview is still an approximation of a larger export, not a full-resolution guarantee.

Synthetic regression coverage checks broad shadows, weak bleed-through, pale ink, colored annotations, original preservation and preview/export agreement. Physical Pixel acceptance is still required, especially for colored stains and difficult faint writing. No private document samples are included in tests.

Validation on API 35: processing instrumentation passed 34 tests; two optional learned-detector benchmarks were skipped. The new background fixture verifies shaded paper within 15 intensity levels of lit paper, reduced 12-level show-through contrast, at least 40 levels of pale-ink contrast, and preserved pale blue annotation color. These are synthetic checks, not measured quality claims about the user's documents.

Final sample instrumentation: all 14 tests passed on API 35, including review zoom, page switching, appearance editing, export and diagnostic viewing. UI lint passed. The APK and all five SDK modules were built and packaged locally. The diagnostic test now grants camera permission before launching the activity so a permission dialog cannot interrupt recreation.
