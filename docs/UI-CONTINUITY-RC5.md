# Stable camera and page previews - 0.1.0-rc5

Capture always reserves its page strip and Next control. Before adding a page the strip is empty and Next is disabled. Adding the first page fills the reserved space without changing the camera bounds, shutter position or control sizes.

Review thumbnails keep their individual renderers mounted when selection changes. Previously the selected thumbnail borrowed the main preview while the others mounted their own renderers, causing a loading flash each time selection changed. Initial thumbnail preparation now displays a small loading indicator instead of incorrectly reporting an unavailable preview.

Capture, review, save and appearance reuse a session-scoped cache of processed previews, keyed by page ID, immutable original and edits. The cache holds at most 16 MiB of bitmap allocations and evicts the least recently used entries. Page removal/editing removes obsolete cache entries; session disposal clears the cache. Eviction releases references without recycling bitmaps that Compose may still be drawing. Recently prepared previews can therefore display immediately while moving between pages and screens. A page outside the bounded cache may still require rendering again.

The complete Android workflow verifies exactly equal camera bounds before and after first import, Next disabled on an empty scan, both loaded thumbnails remaining present immediately after left/right swipes following a reorder, retake/delete confirmations, replacement order, review/save navigation and named two-page PDF export. Checks cover normal and 200% text sizes. The remaining UI tests exercise empty cancellation, malformed import ownership and consecutive manual camera captures. Lint, local SDK publication, independent host integration and the final APK audit remain release gates.

Detection, stability rules, native runtime and model licensing are unchanged from RC4. Physical scanning acceptance remains separate.
