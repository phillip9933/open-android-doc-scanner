# SmartDoc real-camera diagnostic subset

Original dataset: SmartDoc 2015 Challenge 1, repackaged by Jean-Christophe Chazalon as version 2.0.0.

Source release: https://github.com/jchazalon/smartdoc15-ch1-dataset/releases/tag/v2.0.0

Dataset license statement: https://github.com/jchazalon/smartdoc15-ch1-dataset#about-smartdoc-2015---challenge-1

License: Creative Commons Attribution 4.0 International, https://creativecommons.org/licenses/by/4.0/

Attribution requested by the dataset creator:

Jean-Christophe Burie, Joseph Chazalon, Mickaël Coustaty, Sébastien Eskenazi, Muhammad Muzzamil Luqman, Maroua Mehri, Nibal Nayef, Jean-Marc OGIER, Sophea Prum and Marçal Rusinol. “ICDAR2015 Competition on Smartphone Document Capture and OCR (SmartDoc).” 13th International Conference on Document Analysis and Recognition (ICDAR), 2015.

Three original unmodified JPEG frames were extracted from the beginning of the official `frames.tar.gz`. No dataset image modifications were made; only local filenames replace path separators with double underscores. Download stopped after three frames (122,880 compressed bytes read); the complete 1 GB archive was not downloaded.

The author README explicitly licenses the associated dataset and this repackaged version under CC BY 4.0, rather than merely placing a software license on unrelated repository code. It describes tablet camera capture of printed document models and realistic handheld focus/motion distortions.

`manifest.json` records original archive paths, file hashes and the exact author-published corner annotation for `background01/datasheet001/frame_0001.jpeg`. That annotation is transcribed from the dataset README metadata example, with order changed from TL, BL, BR, TR to the scanner's TL, TR, BR, BL. Frames 2 and 3 have no supplied local annotation and should not receive quantitative IoU scores without an explicitly documented annotation step.

These adjacent frames represent one camera sequence. They are diagnostic smoke tests, not independent held-out examples: DocQuadNet was trained on SmartDoc material, and its exact sequence membership is unverified. Do not use this sample to claim general real-camera detection accuracy or robustness across receipts, shadows or clutter. Keep this research subset outside APK/source release assets unless intentionally redistributed with attribution.
