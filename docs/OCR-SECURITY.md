# OCR security hardening — 2026-10-24

The optional 0.8.0 OCR module now uses locally rebuilt native code, not the Tesseract4Android 4.9.0 prebuilt AAR. Existing scanner modules remain separate from OCR.

## Findings addressed

- **GHSA-x3vq-7rr7-5x3h (DAWG model reader):** updated Tesseract from 5.5.1 to 5.5.3, which includes the upstream bounds fix.
- **GHSA-f6h7-cqr4-6fx4 (empty LSTM stack):** added an explicit `size == 0` rejection to 5.5.3's `Plumbing::DeSerialize`.
- **GHSA-5j2p-r5vc-q7f3 (legacy ReadNormProtos):** bounded token extraction to the destination array size. The adapter still selects LSTM-only recognition, but the bundled legacy code is also patched.
- **libpng findings:** replaced 1.6.48 with 1.6.59, covering GHSA-qvg3-h654-xq3j and GHSA-vgjq-8cw5-ggw8. The adapter continues to use Android bitmap decoding rather than forwarding encoded files to the native PNG reader.
- **Model trust:** English/Japanese model copies must match approved SHA-256 values before native code sees them. Other models are rejected. No configurable bypass is exposed by the adapter.
- **Resource handling:** bounded streamed file copies, dimensions and pixel checks, batch limits, worker-thread enforcement and one active OCR job across adapter instances. Models/images are copied into a private job directory before use and cleaned in `finally`.
- **Build integrity:** Gradle dependency verification records SHA-256 checksums for resolved artifacts; native libraries and vendored Java sources have separate recorded hashes checked in CI. Verification metadata was bootstrapped from resolved HTTPS artifacts; it is not independent proof that every upstream artifact is trustworthy. Changes to accepted hashes require review.

## Rebuild

Install Android NDK 28.2.13676358 and CMake 3.22.1 in `ANDROID_HOME`, then run:

```sh
python3 tools/build_ocr_native.py
python3 tools/check_ocr_native.py
```

The explicit rebuild downloads three SHA-256-pinned source archives into ignored build output. It replaces Tesseract and libpng sources in the upstream Android wrapper, applies the two narrowly scoped parser patches, uses C++20, and builds arm64-v8a/x86_64 for API 26 with static libc++. Native outputs are stripped and committed as normal SDK inputs, as with the existing OpenCV module. The script records hashes in [the build manifest](../evidence/ocr-native-build.json); it also copies attributed Java bindings and updates packaged license provenance. It does not run upstream shell scripts or publish anything.

`tools/check_ocr_native.py` verifies the exact binary/source set and hashes, ELF 16 KB load alignment, non-executable stack, GNU_RELRO and immediate binding (full RELRO). Build reproduction may require reviewing changed binary hashes after compiler/toolchain changes; do not accept new hashes automatically.

Normal Gradle builds use committed inputs and strict dependency verification. Do not run `--write-verification-metadata` as a routine CI workaround. For intentional dependency updates, inspect the source/version change and review new checksum entries.

## Validation and remaining limits

See [hardening evidence](../evidence/ocr-security-validation.json) for final test counts and artifact hashes. Tests include both actual English/Japanese recognition and rejecting an untrusted model before native initialization, concurrent-job rejection, bounded-copy growth, cancellation cleanup and batch rejection. Source/binary checks are not native sanitizer testing.

Native recognition still cannot be interrupted mid-call. The global job gate limits concurrent memory pressure but does not impose a native wall-clock timeout. A consuming app that needs a hard deadline or isolation from a native crash should host OCR in a separate service process. No claim of universal model/image parser safety, physical arm64 runtime acceptance or exhaustive fuzzing is made. Use app-private model/storage directories and treat recognized text as untrusted data in host parsing/UI.

Sources: [DAWG advisory](https://github.com/tesseract-ocr/tesseract/security/advisories/GHSA-x3vq-7rr7-5x3h), [empty-stack advisory](https://github.com/tesseract-ocr/tesseract/security/advisories/GHSA-f6h7-cqr4-6fx4), [legacy parser advisory](https://github.com/tesseract-ocr/tesseract/security/advisories/GHSA-5j2p-r5vc-q7f3), [PNG reader advisory](https://github.com/pnggroup/libpng/security/advisories/GHSA-qvg3-h654-xq3j), [PNG writer advisory](https://github.com/pnggroup/libpng/security/advisories/GHSA-vgjq-8cw5-ggw8).
