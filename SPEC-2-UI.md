# BiblioScan — Spec 2: UI/UX pass

Base: [SPEC.md](SPEC.md). This pass changed **neither processing nor data**, only the interface, navigation and finish.
Later changes to the screens from [SPEC-3-IMPORT.md](SPEC-3-IMPORT.md) and [SPEC-4-POLISH.md](SPEC-4-POLISH.md) are folded in below and marked.

UI labels are in French in the app and are quoted as they appear, with a translation where useful.

## Status (merged in `main`, `e3670ef`, refined since)

Implemented in one pass (instead of the 4 PRs first planned). Verified on Pixel 6 GrapheneOS with **3-button navigation**:
- library, camera and book screens: nothing under the system bars;
- light status-bar icons over the camera preview.

Not verified yet:
- gesture navigation;
- dark mode;
- 200 % system font size;
- review mode end to end;
- the permission-denied screen.

## 1. Goals

1. The app opens on the **library**, after a system splash.
2. No control under the status bar or the navigation bar. The V1 camera screen had its « Bibliothèque » button over the status icons.
3. Actions where Android users expect them: a FAB to scan, a bottom control bar on the camera, a standard app bar.
4. Fast review of books « À vérifier » ("to review"), without going back to the list each time.
5. Accessibility and theme: dynamic colors, dark mode, 48 dp touch targets, statuses that don't rely on color alone.

## 2. Out of scope
Tablet and landscape (the app is locked to portrait), translation (no multi-language `strings.xml`), elaborate animations, crop.

## 3. Foundations

### 3.1 Edge-to-edge and insets
- `enableEdgeToEdge()` in `MainActivity`. Android 15 already forces edge-to-edge with targetSdk 35, which caused the V1 overlap.
- Screens with a `Scaffold` use its `paddingValues`, which already handles the bar insets.
- Camera screen: every control sits inside `WindowInsets.safeDrawing`. While the camera screen is shown, the status-bar icons are switched to light, then restored when leaving it.

### 3.2 Theme
- `BiblioTheme` (Material 3):
  - dynamic colors (Material You) on Android 12+, a fixed palette otherwise (brand color `#1F5F5B`);
  - light/dark following the system.
- On the test phone the system palette is monochrome, so the app looks grey. That is expected: it follows the system.

### 3.3 Icon and splash
- **Adaptive icon**: foreground (book + viewfinder corners, white), plain `#1F5F5B` background, **monochrome layer** for Android 13+ themed icons.
- **Splash**: `androidx.core:core-splashscreen`, `installSplashScreen()` before `setContent`.
  - App icon on the brand color.
  - **No artificial delay**: it is kept on screen (`setKeepOnScreenCondition`) only until the book list has been read once, so the empty state never flashes.

### 3.4 Navigation
- **Navigation Compose** with type-safe routes: `LibraryRoute` (start), `CaptureRoute`, `BookRoute(id, reviewPos, reviewTotal)`, `SettingsRoute` (SPEC-4).
- In review mode, moving to the next book replaces the current book screen, so Back returns to the library.
- Predictive back: `android:enableOnBackInvokedCallback="true"` in the manifest.
- One `SnackbarHostState` shared by all screens, so a message survives navigation (e.g. « Revue terminée » shown on the library).

## 4. Screens

### 4.1 Library (start screen)
- **App bar** (`TopAppBar`, collapses on scroll):
  - title « Bibliothèque »;
  - 🔍 search;
  - ⇅ sort;
  - ⋮ menu: « Importer des photos » (SPEC-3), « Exporter (ZIP) », « Importer (ZIP) », « Exporter (CSV) », « Paramètres » (SPEC-4).
- **Sort**: date added (newest first, **default**), title A→Z, author A→Z.
  - Accent- and case-insensitive; author sort uses the first author.
  - Books without a title or author go last.
  - The choice is remembered (app preference).
- **Search**: the 🔍 icon opens a « Titre ou auteur » field under the app bar. It is a local, accent-insensitive filter on title and authors, combined with the status filters; ✕ closes it.
- **Filters** (chips, horizontal scroll), each with its count:
  - « Tous » (all);
  - « À vérifier » (`NEEDS_REVIEW`, `NOT_FOUND`);
  - « En cours » (`CAPTURED`, `OCR_DONE`);
  - « Erreurs » (`ERROR`).

  An empty filter stays visible but disabled.
- **Processing banner**: « N livres en cours de traitement » with an indeterminate progress bar, while N > 0.
- **Review banner**: « N livres à vérifier — Commencer » while N > 0. It opens the oldest one in review mode (§4.3).
- **List**:
  - 2:3 thumbnail = **always the user's own photo** (SPEC-4 §1; this replaces the first decision of showing the API cover);
  - title on 2 lines max, authors on 1 line;
  - **status chip with icon + text**: « Lecture en attente », « Recherche en attente », « Trouvé », « À vérifier », « Introuvable », « Validé », « Erreur »;
  - « doublon possible » badge;
  - books still being read show « Lecture en cours… » in italics.
- When a new book arrives (capture or import) and the sort is by date, the list scrolls back to the top so the new book is visible.
- **Extended FAB** « Scanner » (camera icon), bottom right above the navigation bar. It shrinks to a round FAB once the list is scrolled.
- **Empty state** (no book): icon, « Votre bibliothèque est vide », a short text with the shooting tip (the book should fill the photo, with no other book visible behind), « Scanner un livre » and « Importer des photos » buttons.
- Export/import results: **Snackbar** (no Toast).

### 4.2 Camera
```
┌────────────────────────────┐  ← status bar (nothing under it)
│ ←                          │  ← back to the library, inside the safe area
│  ┌──────────────────────┐  │
│  │                      │  │
│  │   2:3 preview window  │  │  ← what you see is exactly the saved photo
│  │   (white border,      │  │     border turns green while a photo is written
│  │    green while saving) │  │
│  └──────────────────────┘  │
│   « Cadrez la couverture » │  ← hint, hidden after the first photo
│ [thumb]    ( ◉ )  [Terminé (3)] │  ← control bar, translucent black
└────────────────────────────┘  ← navigation bar (nothing under it)
```
- **What you see is the photo**: the preview is a 2:3 window. A CameraX 2:3 `ViewPort` crops both the preview and the saved JPEG to that same region (≈ 1280×1920, verified on the Pixel). V1 showed a full-screen preview cropped at the sides, while the photo kept the whole 4:3 field.
- **Bottom bar**:
  - **left**: thumbnail of the last photo, animated when it appears; a tap opens its book screen;
  - **center**: shutter (76 dp);
  - **right**: « Terminé » with the number of photos taken in this session; back to the library.
- **Feedback on each shot**: haptic tick plus a short white flash (≈ 150 ms) over the preview.
- **Permission denied**:
  - an explanation screen with « Autoriser la caméra »;
  - « Ouvrir les réglages » when the permission is permanently denied;
  - the permission is re-checked when coming back from the system settings.
- Capture error: Snackbar above the control bar.
- Volume keys keep their normal behaviour; they don't trigger the shutter.

### 4.3 Book screen
- **App bar**: back, book title (or « Livre sans titre »), with « Revue n / total » under it in review mode. ⋮ menu: « Relancer la recherche » (search again) and « Supprimer » (delete).
- **Header**:
  - « Votre photo » (own photo, 2:3; tap = full screen with pinch zoom);
  - next to it « Couverture trouvée » (API cover) **until the book is validated** (SPEC-4 §3);
  - then the status chip and the last error, if any.
- **Form**: title, authors (separated by « ; »), publisher, year, ISBN.
- **« Rechercher avec ce titre et cet auteur »** button under the form: saves the typed fields first, then searches again with title + author (SPEC 1 §5).
- **« Livres proposés »** (proposed books) as selectable cards (radio button):
  - the selected one is highlighted, and tapping a card fills the form;
  - the score is shown in words (« Correspondance forte / moyenne / faible »);
  - the source and, for a foreign edition, a language badge (« EN »…) are shown too (SPEC-4).
- **Main button pinned at the bottom**, above the navigation bar and the keyboard:
  - « Valider » outside review mode: saves as `CONFIRMED`, goes back to the library, Snackbar « Livre validé »;
  - « Valider et suivant » + « Passer » (skip) in **review mode**.
- **Review mode**:
  - opened from the review banner;
  - books are walked from oldest to newest, and « Passer » moves on without changing anything;
  - the counter total is fixed at the count when review started;
  - at the end: Snackbar « Revue terminée » and back to the library.
- **« Relancer la recherche »**: bottom sheet with the query pre-filled and a « Relancer » button (free-text search).
- **OCR text**: collapsible « Texte lu sur la couverture » section, closed by default.
- **Delete**: confirmation dialog (« La fiche et la photo seront effacées définitivement »).

## 5. Accessibility
- Touch targets ≥ 48 dp. Every icon button has a French `contentDescription`.
- Statuses: icon + text, AA contrast in both themes.
- A 200 % system font size must not break the screens: text wraps, and actions are never truncated.

## 6. Acceptance criteria

| Check | How |
|---|---|
| No control under the status or navigation bar, on every screen | Pixel 6, 3-button ✅ then gesture navigation ⬜ |
| Cold start → library shown | splash without artificial delay, no empty-state flash ✅ |
| Library → camera in 1 tap (FAB); camera → library in 1 tap (« Terminé » or back) | ✅ |
| Photo = preview window | ✅ (1280×1920, same framing) |
| Review 10 books « à vérifier » without going back to the list | ⬜ |
| Dark mode and dynamic colors | ⬜ |
| 200 % system font: screens usable | ⬜ |
| No processing regression | unit tests green, real captures processed ✅ |

## 7. Delivery
Implemented in one pass on `feat/ui-ux` (merged as `e3670ef`), then refined:
- WYSIWYG camera window;
- light status-bar icons on the camera;
- « Rechercher avec ce titre et cet auteur »;
- scroll to top on new books.

## 8. Decisions
- ✅ Sort: date added by default, plus title and author (§4.1).
- ✅ Volume key as shutter: no.
- ↺ List thumbnail: first decided as « API cover once found or validated », **replaced by SPEC-4 §1**: always the user's own photo.
