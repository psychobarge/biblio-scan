# BiblioScan — Spec 3: importing photos from the phone

Base: [SPEC.md](SPEC.md) (processing, reliability) and [SPEC-2-UI.md](SPEC-2-UI.md) (screens).

## Status (merged in `main`, `108f86f`)

Implemented. Verified on Pixel 6 (GrapheneOS):
- ⋮ → « Importer des photos » opens the system photo picker; **no storage permission** is granted to the app;
- gallery photos are copied at 1446×1920 (300–450 KB instead of ≈ 4 MB), the right way up, then processed with no further action.

Covered by unit tests: target size, collection names filtered from the query.

Not verified yet:
- importing 20 to 50 photos at once;
- the app killed during an import;
- a corrupt image;
- a photo taken in landscape.

Added during the real-world test:
- the list scrolls back to the top when a new book arrives (imported books used to stay out of view);
- letter height measured along the line, and collection names filtered (SPEC §6).

Display order: with the « date added » sort (newest first), the last photo of the batch comes first.

## 1. Need

Take a burst of photos with the phone's regular camera app, without opening BiblioScan, then later:
**Library → import photos → select → every photo is processed**, with no further action.

## 2. Decisions

- ✅ **minSdk 26 → 28** (Android 9), to use `ImageDecoder`.
- ✅ **No crop**: imported photos go straight to OCR (§8 explains the test behind this choice).
- ❌ "Share to BiblioScan" from the gallery: not needed.
- ⏸ Duplicate detection at import (SHA-256 fingerprint): not for now. The existing « doublon possible » badge is enough.

## 3. Journey

1. The **« Importer des photos »** entry is in two places in the library:
   - the ⋮ menu;
   - the empty state, next to « Scanner un livre ».
2. The **Android photo picker** opens: **images only**, multiple selection (up to the system limit, usually 100).
   - No storage permission is requested: only the chosen photos are readable by the app.
   - It is built into the system since Android 13, so it is available on GrapheneOS.
3. Back to the library straight away, with a « Import de N photos… » Snackbar.
4. Each photo becomes a `CAPTURED` book as soon as it is copied. The processing banner and the list fill in as it goes.
5. At the end: Snackbar **« N photos importées »**, or « N photos importées, K illisibles » ("K unreadable") if some failed.
6. Then the usual processing: OCR → lookup → « Trouvé » / « À vérifier ».

The shooting tip is shown in the library's empty state: *the book should fill the photo, with no other book visible behind it*.

## 4. Copying an imported photo

For each selected URI, **in selection order**, one after the other, in the application scope (the copy goes on if the screen is left):

1. **Decode** with `ImageDecoder`, which applies the EXIF orientation, **downscaling** to 1920 px max on the long side (`Books.targetSize`: ratio kept, never upscaled). A gallery photo is ≈ 4000×3000 and 4 MB; an in-app photo is 1280×1920.
2. **Re-encode** as JPEG (quality 90) to `covers/{uuid}.jpg.tmp`, then **rename** to `.jpg`. A half-written file is never mistaken for a photo, and leftover `.tmp` files are deleted at the next start.
3. **Insert** the `CAPTURED` row, with `createdAt` = import time + index to keep the selection order.
4. Queue OCR every 10 photos and at the end, so processing starts without waiting for the whole import.

An unreadable photo (corrupt file, unsupported format) is skipped and counted in « K illisibles ». It doesn't block the others.

## 5. Reliability

- Same invariants as capture (SPEC §4): the photo is on disk **before** its row, and startup reconciliation picks up a photo without a row.
- **If the app is killed during an import**, the photos already copied are safe. The rest is not imported, since picker URIs only live for the session; the user imports the missing photos again.
- Importing the same photo again creates a second book, later flagged « doublon possible ».
- Gallery photos are **never modified or deleted**.
- No database schema change.

## 6. Technical

| Point | Choice |
|---|---|
| Selection | `ActivityResultContracts.PickMultipleVisualMedia()` + `PickVisualMedia.ImageOnly` |
| Decoding | `ImageDecoder` + `setTargetSize` (EXIF applied), software allocator; minSdk 28 |
| Import | `Books.importPhotos(ctx, uris): ImportResult(ok, failed)`, next to `onPhotoSaved` |
| Unit test | target size: downscaling, ratio kept, small images never upscaled |

## 7. Out of scope

- **Any crop** of imported photos, manual or automatic.
- "Share to BiblioScan" from the gallery.
- Duplicate detection at import.
- PDF or video import; deleting photos from the gallery.

## 8. Automatic crop test (2026-11-08): dropped

- **Method**: the bundled ML Kit object detector (no Google services), run on the Pixel 6 over **10 real photos** taken with the phone's camera app (book held in hand), plus 6 in-app photos.
- **Result**: on the 10 real photos the detector **never** found the book. It boxed a detail of the cover illustration (a bottle, a wheel, a medal, a character). The decision rule would have applied 3 of those boxes automatically, **cutting off the title and author**.
- **Finding**: on these photos the book already fills ≈ 85 % of the frame. The rest is a hand and a blurred background with no stray text, so cropping brings almost nothing to the OCR.
- **Revisit only if** books in the background cause wrong matches in practice. Options then:
  - edge detection (OpenCV, heavy);
  - manual crop before OCR: a screen with a 4-handle adjustable frame and a « Pivoter 90° » button, imported photos waiting in a `TO_CROP` status until validated.

## 9. Acceptance criteria

| Check | How | State |
|---|---|---|
| 20 photos selected → 20 books in the list, then processed with no action | Pixel 6 | ⬜ (2 photos ✅) |
| No storage permission requested | App settings → Permissions | ✅ |
| Imported photo ≤ 1920 px, right orientation (landscape included) | thumbnail + file size | ✅ portrait, ⬜ landscape |
| App killed during import → no copied photo lost, no `.tmp` seen as a book | adb during a 50-photo import | ⬜ |
| One corrupt image in the selection → the others are imported, « 1 illisible » | manual | ⬜ |
| Unit tests green, including target size | `./gradlew testDebugUnitTest` | ✅ |
