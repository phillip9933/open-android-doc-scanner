# Scanner SDK 1.1.0

The viewfinder checks for page edges as soon as the learned detector misses a frame. On cards with a dark printed panel, it looks for the full card border before suggesting a crop. When you take a picture, the scanner refines the corners against the page edges and carries the selected photo’s crop into review.

Auto keeps the stock color on two-tone cards. On paper documents it reduces show-through, preserves pencil strokes and highlighter, and makes dim printed grids easier to read. Very faint writing gets a gentler contrast adjustment.

## What we tested

On 32 synthetic scenes with pages and cards photographed at different angles and sizes, the first frame had the correct outline in 32 cases, up from 11 in 1.0.0. Both versions rejected all five empty scenes. On a shadowed form, measured show-through fell by 90% while pencil stroke contrast increased. These results come from Android emulator tests. Handheld timing varies with the phone, lighting and background. Manual corner adjustment remains available for difficult pages.

## Upgrade from 1.0.0

Update every `dev.offlinescan` module your app uses to `1.1.0`. Apps using the ready-made scanner get the new behavior automatically. Custom camera integrations can use `ScannerCamera.captureWithDetection` to receive the saved JPEG and its detection together. For custom document import or capture, `DocumentOutlineFallback.refineStill` selects between the learned and measured outlines.

The download contains the six-module Maven repository. Verify the ZIP with `sha256sum --check SHA256SUMS`, extract it and point Gradle at the extracted `maven` directory.
