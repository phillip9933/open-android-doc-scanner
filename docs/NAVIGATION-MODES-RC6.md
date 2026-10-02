# Navigation and capture modes - 0.1.0-rc6

The review screen's top-left Back control now returns to capture without deleting or replacing the selected page. The dedicated Retake control still asks before discarding that page. Save Back continues to return to review, and Close continues to confirm discarding the unsaved session.

Previous/next arrow controls have been removed from review and save. Their previews retain horizontal swipe navigation, page position and thumbnails. The stable capture layout and session preview cache from RC5 remain.

The camera guidance pill is a button that opens a small menu for Document, Receipt, Photograph and Card. It displays the selected mode alongside the current guidance. Opening the menu pauses automatic capture evidence; closing it resumes the applicable mode without restarting the session or clearing pages.

Modes apply to subsequent captures/imports. Photograph uses the full frame and the Photo preset, with manual shutter and no automatic document outline/crop. Document, Receipt and Card use the shared Standard document detector and Color document preset; automatic scanning respects the host's autoCapture setting and the user's pause state. They do not introduce new detectors or learned models. Existing page edits remain intact. Session page and resource limits remain those set by the host; selecting Card does not silently reduce the limit or delete pages to create a front/back pair.

The camera retains the host's original automatic-capture permission independently of the currently selected mode, allowing a session that starts in Photograph to switch to automatic Document scanning. A host that disables automatic capture continues to prohibit it in every mode.

End-to-end checks cover non-destructive Back, absence of page arrows, swipe navigation after reordering, current mode selection, preserved Photo preset on an earlier page after switching to Document, Color document preset on the next page, Photograph/manual versus Document/automatic controls, menu capture suspension, normal and 200% text, and two-page named PDF export. Physical scanning acceptance remains separate.
