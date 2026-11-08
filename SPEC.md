# BiblioScan — Spec 1: core app (as built)

Personal Android app to catalogue a home library by photographing book covers.
Priorities: **fast capture** and **no lost records**.

This spec describes **the current code** (release `v0.0.1`, 2026-11-08). It covers the core: storage, processing, lookup and distribution. Later specs refine it, and this file points to them where they changed things:
- [SPEC-2-UI.md](SPEC-2-UI.md) — UI/UX pass (screens, navigation, theme).
- [SPEC-3-IMPORT.md](SPEC-3-IMPORT.md) — importing photos from the gallery.
- [SPEC-4-POLISH.md](SPEC-4-POLISH.md) — thumbnails, French editions, scoring, settings.

Section numbers are referenced from code comments ("SPEC §5"): do not renumber.

UI labels are in French in the app; they are quoted here as they appear, e.g. « À vérifier » ("To review").

## Status

| Item | State |
|---|---|
| In-app capture (CameraX, 2:3 window, photo = what you see) | ✅ done, tested on Pixel 6 (GrapheneOS) |
| Photo written before its DB row + startup reconciliation | ✅ done |
| On-device OCR (ML Kit bundled, no Play Services) | ✅ done, **verified on GrapheneOS** |
| Background OCR / lookup workers, 5 attempts | ✅ done |
| Lookup Google Books → Open Library, falls through when a source fails | ✅ done |
| Scoring (both ways, foreign editions penalised, editions grouped) | ✅ done, unit tests on real cases |
| Library / book / review / settings screens | ✅ done (SPEC-2, SPEC-4) |
| Gallery import | ✅ done (SPEC-3) |
| ZIP export/import, CSV export | ✅ done; ⚠️ **not yet tested on the phone** |
| Release signing, Google Books key, app icon, README | ✅ done |
| GitHub release `v0.0.1` (signed APK, Obtainium link) | ✅ done |
| Robustness test (airplane mode, app killed during processing) | ⬜ not done |
| 30-book measurement (§12) | ⬜ not done |
| CI (GitHub Actions), smaller APK (70 MB release, 4 ABIs) | ⬜ not done |

## 1. Scope

### In
- Cover photo, framed by the user in the app's 2:3 window, or imported from the gallery (SPEC-3).
- Shooting never waits for processing.
- Background processing: on-device OCR, then lookup on free APIs.
- Local library, book screen with validation and correction, review mode.
- Settings: editable list of words ignored on covers (SPEC-4 §4).
- ZIP export/import, CSV export. Distribution through GitHub, no Play Store.

### Out
Automatic crop (tried and dropped, SPEC-3 §8), ISBN barcode scanning, vision model (VLM), cloud sync, shelves/tags/loans, UI translation.

## 2. Stack

| Need | Choice | Version |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose, Material 3 (Material You colors) | Kotlin 2.1.0, BOM 2024.12.01 |
| Navigation / splash | Navigation Compose (type-safe routes), `core-splashscreen` | 2.8.5 / 1.0.1 |
| Build | AGP 8.7.3, Gradle 8.11.1 (wrapper), **JDK 17 required** | |
| SDK | minSdk 28 (raised by SPEC-3 for `ImageDecoder`), target/compile 35 | |
| Camera | CameraX `ImageCapture` + `ViewPort` | 1.4.1 |
| OCR | ML Kit Text Recognition v2 **bundled** (Latin) | 16.0.1 |
| Database | Room (schema exported to `app/schemas/`) | 2.6.1 |
| Background work | WorkManager | 2.10.0 |
| HTTP / JSON | OkHttp + kotlinx.serialization | 4.12.0 / 1.7.3 |
| Images | Coil | 2.7.0 |

No ViewModels: screens read Room `Flow`s directly.

## 3. User journey (summary)

The details are in SPEC-2; this is the short version.

1. **Library** (start screen, after the system splash): list of books with the user's own photo as thumbnail, status chip, search, sort, filters, banners for books in progress and books to review. « Scanner » FAB → camera.
2. **Camera**: 2:3 preview window; the saved photo is exactly that window. The next shot is possible immediately. « Terminé (N) » goes back to the library.
3. **Gallery import** (⋮ → « Importer des photos »): system photo picker, every photo is processed (SPEC-3).
4. **Book screen**: own photo (plus the found cover until validation), editable fields, proposed books, « Rechercher avec ce titre et cet auteur » (search again with the typed title and author), validation, review mode.
5. **Settings** (⋮ → « Paramètres »): list of words ignored on covers (SPEC-4 §4).

## 4. Reliability of records

**A photo that was taken is never lost, whatever fails afterwards.**

- The photo is always `filesDir/covers/{id}.jpg`. **No path is stored in the database**: it is derived from the id.
- Capture: CameraX writes the JPEG → a `CAPTURED` row is inserted in the application scope (which outlives the screen) → OCR is queued.
- Gallery import (SPEC-3 §4) follows the same order, writing to `.tmp` then renaming.
- An error in the application scope is logged, not a crash; the photo is picked up again by reconciliation.
- **Reconciliation** at every app start (`BiblioApp.onCreate`):
  - leftover `*.tmp` files (interrupted writes) are deleted;
  - every non-empty `.jpg` without a row → `CAPTURED` row;
  - then processing is queued, which resumes every non-final status.
- Deletion: photo first, then the row (otherwise the leftover photo would bring the book back).
- Room version 1, schema exported; **never** `fallbackToDestructiveMigration`.

## 5. Background processing

### State machine
```
CAPTURED ──OCR──▶ OCR_DONE ──lookup──▶ MATCHED       (« Trouvé », automatic)
                                   ├─▶ NEEDS_REVIEW  (« À vérifier »)
                                   └─▶ NOT_FOUND     (« Introuvable »)
MATCHED / NEEDS_REVIEW / NOT_FOUND ──« Valider »──▶ CONFIRMED (« Validé »)
step failing 5 times ──▶ ERROR (photo kept)
search again (book screen) ──▶ CAPTURED (no OCR yet) or OCR_DONE (with the typed query)
« Appliquer aux livres non validés » (settings) ──▶ OCR_DONE with a rebuilt query (SPEC-4 §4.4)
```

### Workers (`work/Processing.kt`)
- The database is the source of truth. Each worker processes **every** row in its input status; workers are idempotent.
- **OcrWorker**: status `CAPTURED`, no constraint (works offline). It stores:
  - the raw text;
  - the lines with their letter height (JSON, §6);
  - the query built from them (§6), unless the user already typed one.

  It then queues the lookup.
- **LookupWorker**: status `OCR_DONE`, network constraint `CONNECTED`.
  - It searches the stored query, then the biggest line alone as a fallback (§7).
  - Candidates are scored against the OCR text **and** the query, so a title typed by hand counts even when the OCR misread it.
- Two separate unique chains (`"ocr"`, `"lookup"`, `APPEND_OR_REPLACE`): OCR never waits behind a lookup that waits for the network.
- Both workers read the user's ignored-words list (SPEC-4 §4) once per pass.
- A failing book gets `attempts + 1` and `lastError`. At 5 → `ERROR`, otherwise `Result.retry()` (WorkManager's default exponential backoff).

## 6. From OCR to the query (`match/Matching.kt`)

1. Normalisation: lowercase, accents and punctuation removed.
2. **Letter height** of each line is measured along the line's own orientation (from ML Kit's 4 corner points), not from the axis-aligned box. Otherwise a vertical or tilted line (collection name along an edge) looks like huge text and outranks the title.
3. Ignored words, from the user-editable list (SPEC-4 §4, defaults below):
   - « whole line » entries (`prix, traduit, traduction, preface, postface`) drop the whole line that contains them;
   - other entries are removed from the line (`editions, collection, folio, livre de poche, j ai lu, flammarion, garnier, fantasy, polar, bd`…);
   - single-word entries of **7+ letters** are also removed from inside words the OCR glued together (« GARNIERFLAMMARIONH »).
4. Lone numbers (volume number, price) are removed.
5. Lines sorted by letter height, biggest first. Query = the 4 biggest lines joined, cut at 100 characters at most on a word boundary.
6. Fallback query: the biggest line alone.

Known limit: decorative fonts are misread by the OCR (e.g. « BOVARY »). The workaround is to type the title and use « Rechercher avec ce titre et cet auteur ».

## 7. Lookup APIs (`match/BookSearch.kt`)

For each query (main, then fallback), **Google Books then Open Library** are queried, stopping at the first `MATCHED`.
- **When a source fails** (HTTP other than 2xx, network), the next one is tried.
- An error, and therefore a retry, is raised only if **every** call failed.
- 20 s timeout per call.
- All candidates are scored against the main query (§8).

**Google Books**: `volumes?q=…&langRestrict=fr&printType=books&maxResults=10[&key=…]`
- Title without subtitle (rarely on the cover), ISBN-13 preferred, thumbnail forced to https, `language` kept.
- `langRestrict=fr` is **not reliable**: English, Swedish or Romanian editions still come back (SPEC-4 §2.1).
- **Without a key: HTTP 429 seen on 2026-11-08** (shared quota exhausted). A key is needed in practice (§11).

**Open Library**: `search.json?q=…&language=fre&limit=10&fields=key,title,author_name,publisher,first_publish_year,isbn,cover_i`
- `User-Agent: BiblioScan/0.1 (personal Android app)`. Cover: `covers.openlibrary.org/b/id/{cover_i}-M.jpg`.
- Language is left unknown: the returned title is the work's title, often in the original language.

## 8. Scoring and decision

Words = normalised words of 2+ characters.

- `p` = share of the candidate's **title** words found on the cover (OCR text + query).
- `r` = share of the **query** words explained by the candidate's title + authors.
- `title = 2·p·r / (p + r)`. The harmonic mean stops a short title (series or cycle name) from scoring 1.0 just because its one word is on the cover.
- `a` = best share, over the candidate's authors, of the author's name words found on the cover.
- `score = 0.6·title + 0.4·a`, **halved** when the candidate's language is known and is not French.

Decision:
- `MATCHED` if `best ≥ 0.8` **and** `best − second ≥ 0.15`.
  - `second` = best score of a **different work** (normalised title + first author differ), so several editions of the same book are not ambiguous.
- `NEEDS_REVIEW` if `best ≥ 0.4`.
- `NOT_FOUND` otherwise.

Consequences:
- A foreign edition can never be picked automatically.
- A candidate without an author cannot either.
- The top 5 candidates are kept for the book screen, whatever the verdict.

Duplicates: the same `sourceId` (or ISBN) on several books → « doublon possible » badge, never blocking.

Thresholds are still to be calibrated (§12). Unit tests cover a textbook case (*L'étranger*) and two real ones seen on the phone (*La victoire des aigles*, *La maison des autres*).

## 9. Data model (`data/Book.kt`)

```kotlin
@Serializable @Entity
data class Book(
    @PrimaryKey val id: String,          // UUID; photo = covers/{id}.jpg
    val createdAt: Long,
    val status: Status,                  // CAPTURED, OCR_DONE, MATCHED, NEEDS_REVIEW, NOT_FOUND, CONFIRMED, ERROR
    val attempts: Int = 0,
    val lastError: String? = null,
    val ocrText: String? = null,
    val ocrLinesJson: String? = null,    // [{text, height}], height = letter height
    val query: String? = null,
    val title: String? = null,
    val authors: String? = null,         // "A; B"
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val sourceId: String? = null,        // "google:{id}" or "openlibrary:{key}"
    val coverUrl: String? = null,        // cleared on validation (SPEC-4 §3)
    val score: Double? = null,
    val candidatesJson: String? = null,  // top 5, each with its language
)
```

## 10. Backup, export, import (`data/Backup.kt`)

Every file goes through the Android file picker, so it lives outside the app and survives an uninstall.

- **ZIP export**:
  - `library.json` (all books) + `covers/{id}.jpg`;
  - `settings.json` (ignored-words list), only when it was customised.
- **ZIP import**: adds only missing books and photos, never overwrites, then queues processing. Trust-boundary checks:
  - only `covers/<uuid>.jpg` entries are accepted;
  - books whose id is not a UUID are ignored;
  - each photo is written to `.tmp`, then renamed;
  - `settings.json` is applied only if the phone still uses the default list.
- **CSV export**: `titre,auteurs,editeur,annee,isbn,statut`, UTF-8 with BOM (for Excel).

## 11. Distribution (GitHub, no Play Store)

- Repository: `github.com/psychobarge/biblio-scan`, branch `main`. Releases carry the signed APK. Users install it by hand or follow the repo with **Obtainium**; the README has an Obtainium badge using the official redirect link.
- **Version**: `versionName` = release tag without the `v` (`0.0.1` ↔ `v0.0.1`), since Obtainium compares them.
- **Release signing**: `biblioscan-release.jks` + `keystore.properties` at the project root, both git-ignored.
  - One key only, to be backed up outside the PC.
  - Losing it = updates can't install over the existing app.
- **Debug builds** use `app.biblioscan.debug` (label « BiblioScan dev »), so they never collide with the release installed from GitHub.
- `BOOKS_API_KEY` is read from `local.properties` (git-ignored) into `BuildConfig`.
  - ⚠️ It ends up inside the public APK and can be extracted: restrict it to the Books API in Google Cloud. The worst case is someone exhausting the free quota.
- No dependency on Google Play Services: **verified on GrapheneOS**.
- Local build: `JAVA_HOME` must point to JDK 17 (installed with scoop: `temurin17-jdk`).
  - `./gradlew installDebug` for development.
  - `./gradlew assembleRelease` for the signed APK.
- Release: `gh release create vX.Y.Z <apk>` (GitHub CLI, logged in with the owner's account).
- ⚠️ Moving from a build signed with another key (e.g. an old debug install of `app.biblioscan`) requires: ZIP export → uninstall → install the release → ZIP import.

## 12. Acceptance criteria / verification

| Check | Target | State |
|---|---|---|
| Build + 25 unit tests (query, scoring, real cases, ZIP checks, sort/search, import size) | green | ✅ |
| Install + launch without crash on Pixel 6 GrapheneOS | OK | ✅ |
| Time between two shots | < 1 s | 🟡 feels fine, not measured |
| Lost photos (app killed during processing, airplane mode) | 0 | ⬜ to test |
| 30-book corpus: correct `MATCHED` | ≥ 60 % | ⬜ |
| Corpus: **wrong `MATCHED`** | **≤ 2 %** | ⬜ (one seen with the old scoring: « Zola » by Henri Mitterand, SPEC-4) |
| Corpus: solved after review | ≥ 90 % | ⬜ |
| ZIP export then import on the phone, nothing lost | OK | ⬜ |

## 13. Decisions / open questions

- ✅ In-app capture: 4:3 resolution around 1920×1440, cropped by a 2:3 `ViewPort` to the visible window (≈ 1280×1920, SPEC-2).
- ✅ No automatic crop for gallery photos: tried with the ML Kit object detector and dropped (SPEC-3 §8).
- ✅ Google Books key needed (keyless calls return 429); Open Library is the fallback.
- ✅ Photo path derived from the id (no `photoPath` column).
- ✅ The user's photo is the thumbnail everywhere in the list; the API cover is only shown on the book screen until validation (SPEC-4).
- ✅ Full-resolution photos are kept after validation (shrinking them was considered and rejected).
- ❓ Repository license: GitHub created a `LICENSE` file — check it fits.
- ❓ Smaller release APK: `arm64-v8a` only (the x86 emulator could no longer install it), and/or R8 minification.
