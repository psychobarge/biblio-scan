# BiblioScan — Spec 4: polish

Base: [SPEC.md](SPEC.md), [SPEC-2-UI.md](SPEC-2-UI.md), [SPEC-3-IMPORT.md](SPEC-3-IMPORT.md).

## Status (merged in `main`, `4732c8c`)

Implemented. Unit tests green, including the real cases:
- *La victoire des aigles*: the French edition is matched automatically, English editions never are;
- *La maison des autres*: the volume ranks above its cycle.

Verified on Pixel 6:
- thumbnails are the user's own photos, validated books included;
- ⋮ → « Paramètres » → list of ignored words;
- « Appliquer aux livres non validés » → « N livre(s) relancé(s) ». With the new scoring, the false « Trouvé » « Zola » (by Henri Mitterand, the preface author of *La Bête humaine*) went back to « À vérifier » with its fields cleared, and validated books were untouched.

Findings on real books:
- **False « Trouvé » from the old scoring** (*La Bête humaine* above): led to extending the apply button to « Trouvé » books (§4.4).
- **Stray words that block Google, not handled by this spec**: for *La Curée* the query « 20la la curee texte integra gf » finds nothing.
  - « ZOLA » was read « 20LA », and « integra » is truncated.
  - Google needs almost every word, so one stray word makes the lookup fail.
  - Adding « gf » and « integra » to the ignored words helps. A real fix would be to retry the lookup automatically, leaving out one line at a time.

Not verified yet: the « EN » badge on a real book screen, ZIP export then import of a customised list, « Rétablir la liste par défaut ».

## 1. Library thumbnails = always the user's photo

- In the list, the thumbnail is **always the photo taken by the user**, whatever the book's status.
- This replaces SPEC-2 §8 (« API cover once the book is found »).
- The book screen keeps both images side by side (« Votre photo » / « Couverture trouvée ») until validation (§3): the API cover helps check visually that it is the right book.
- « Livres proposés » cards keep the API cover, since they are there to compare.

## 2. Prefer French editions

### 2.1 Finding (real case: *La victoire des aigles*, Naomi Novik, volume 5 of Téméraire)
- Google Books **does** have the French edition: « La victoire des aigles », language `fr`.
- The app still offered « Victory of Eagles (The Temeraire Series, Book 5) », in English, for three combined reasons:
  1. **A noisy query**: « temeraire naomi novik **hntasy** la victoire des aigles **5** ». « hntasy » is « FANTASY » misread, « 5 » the volume number. With those extra words, Google no longer returned the French edition.
  2. **Google's language filter is unreliable**: with `langRestrict=fr`, Google still returns English, Swedish or Romanian editions, and they were ranked first.
  3. **Short titles scored too high**: the candidate « Temeraire » (the series name) got 1.0 because its only word is on the cover. The same flaw tied *La grande patience* (the cycle) with *La maison des autres* (the volume).
- Checked by hand: « naomi novik la victoire des aigles » returns the French edition first.

### 2.2 Changes
1. **Candidate language**: new `language` field on `Candidate`.
   - Google: `volumeInfo.language` (`fr`, `en`, …).
   - Open Library: unknown (`null`), since the returned title is the work's title, often in the original language.
2. **Language penalty**: a candidate whose language is known and **does not start with `fr`** has its score **halved**. It can never be picked automatically, but stays in the list.
3. **Language badge** on « Livres proposés » cards when it is not `fr` (« EN », « SV »…), so the user sees they are picking a foreign edition.
4. **Cleaner query**; also removed:
   - **lone numbers** (volume number, price);
   - **genre labels** (default ignored words): `fantasy, science fiction, sf, thriller, policier, polar, bd, manga, jeunesse`.
5. **Title scored both ways**, combining:
   - `p` = share of the **candidate's title** words found on the cover (OCR text + query);
   - `r` = share of the **query** words explained by the candidate's title + authors;
   - title = `2·p·r / (p + r)` (harmonic mean). The final score stays `0.6·title + 0.4·author`, then the language penalty.

   Effect on the real case, with the cleaned query « temeraire naomi novik la victoire des aigles hntasy » (computed by hand, checked by the unit test):

   | Candidate | Before | After |
   |---|---|---|
   | La victoire des aigles (fr) | not in the list | **≈ 0.91 → matched automatically** |
   | Temeraire (fr) | 1.0 | ≈ 0.73 |
   | Victory of Eagles (The Temeraire Series, Book 5) (en) | 0.49 | ≈ 0.26 |

   For *La maison des autres*, « La grande patience 1 : La maison des autres » now ranks above « La grande patience » alone.
6. **`r` is computed on the query** (cleaned, or typed by the user), not on the whole OCR text, so stray cover words (« folio », « Prix Nobel »…) don't penalise the right candidate.

### 2.3 Unit tests (`PolishTest`)
- *La victoire des aigles*: OCR text from the real book; candidates as returned by Google for that query; letter heights approximated. The `fr` edition is matched automatically, and it ranks first.
- A foreign edition is never matched automatically (score halved).
- *La maison des autres* (OCR and query from the real book): the volume ranks first, and the cycle below the volume title alone.
- Genre labels and lone numbers removed from the query.
- *L'étranger* (existing tests, now with the query): still matched automatically. A candidate without an author can no longer be matched, but stays listed.

### 2.4 Books already validated
Nothing changes automatically: a validated book (`CONFIRMED`) stays as it is. To fix *La victoire des aigles*, open the book screen, type the French title, then tap « Rechercher avec ce titre et cet auteur ».

## 3. After validation: no more API cover

Once a book is **validated** (`CONFIRMED`), the cover found online is no longer needed.

- On validation, `coverUrl` is **cleared**.
- The book screen of a validated book only shows « Votre photo ». The « Couverture trouvée » column is gone.
- The « Livres proposés » list is kept (it is text, useful to change one's mind). Its cards still show their online covers.
- If a new lookup on a validated book ends in an automatic match, `coverUrl` is filled again until the next validation.
- The user's photo is **kept as is**, in full resolution. Shrinking it after validation was considered and **rejected** on 2026-11-08.

## 4. Settings screen: ignored words

### 4.1 Need
The words removed from the query (publisher and collection names, « Prix Goncourt »…) used to be hard-coded (SPEC §6). Real covers keep revealing new ones (« GARNIERFLAMMARIONH », « FANTASY »…). The user must be able to **extend or correct the list themselves**, without rebuilding the app.

### 4.2 Access
- Library → ⋮ → **« Paramètres »**, last menu entry.
- Standard screen: app bar with back and the title « Paramètres ». A single section for now: **« Mots ignorés sur les couvertures »** ("words ignored on covers"), with a short explanation.

### 4.3 The list
- At the top: an **« Ajouter un mot ou une expression »** field and an « Ajouter » button (or the keyboard's Done key).
  - Input is normalised like OCR text (lowercase, no accents or punctuation), so « Prix » and « prix » are the same entry.
  - A duplicate is refused with « Déjà dans la liste ».
- Then the **« Appliquer aux livres non validés »** button (§4.4).
- One row per entry, in alphabetical order: the word or phrase, a **« Toute la ligne »** ("whole line") switch, and a 🗑 delete button.
- The **two behaviours**:

  | Switch | Effect on a line read on the cover | Default examples |
  |---|---|---|
  | off (default) | the word or phrase is **removed** from the line, the rest is kept | editions, collection, folio, livre de poche, j ai lu, fantasy |
  | « Toute la ligne » | the **whole line** is dropped if it contains the word | prix, traduit, traduction, preface, postface |

- **Words glued by the OCR**: a **single-word entry of 7+ letters** is also matched **inside** a word (« garnierflammarionh » contains « garnier » and « flammarion »). It is automatic, with nothing to set. Short words (« prix », « folio ») only match as whole words, so real titles are not damaged.
- At the bottom: **« Rétablir la liste par défaut »** (reset to defaults), with confirmation.
- The default list (`Matching.DEFAULT_NOISE`) holds the previously hard-coded words plus the genre labels of §2.2.

### 4.4 Applying changes to existing books
- Changes apply to **the next books processed**.
- **« Appliquer aux livres non validés »** rebuilds, from the stored OCR lines (`ocrLinesJson`), the query of every book the user has not validated: « À vérifier », « Introuvable » **and « Trouvé »** (matched automatically). The lookup is then run again.
  - For a « Trouvé » book, the automatically filled fields (title, authors, publisher, year, ISBN, source, cover, score) are cleared first, so an old wrong match doesn't linger.
  - No new OCR: it is fast and never re-reads the photos.
  - Validated books are **never** touched.
  - Snackbar: « N livre(s) relancé(s) ».

### 4.5 Storage and technical
- The list is stored as JSON in the app preferences (`SharedPreferences` "settings", key `noise`), e.g. `[{"text": "prix", "wholeLine": true}, …]`.
  - Writes use `commit()`, so the list is on disk before a worker reads it.
  - Missing or unreadable preferences → the default list.
- It is **included in the ZIP export** (`settings.json`, only when customised). At import it is restored only if the phone still uses the default list, so a customised list is never overwritten.
- `Matching.clean` / `buildQuery` / `biggestLine` take the list as a parameter (default: `DEFAULT_NOISE`). It stays pure Kotlin, hence testable.
- Workers read the list once per pass.

### 4.6 Tests
- Unit: remove a word, drop a whole line, 7+ letter glued word, short word not matched inside a word, input normalisation, duplicate refused.
- The default list gives **exactly** the queries of the earlier tests (no regression).

## 5. Out of scope
- Querying the BnF (French national library catalogue): an option if Google and Open Library stay insufficient, but it is a third API.
- A configurable preferred language.
- Entering the Google Books key in Settings (it stays in `local.properties` at build time).
- Automatically re-scoring already processed books (the apply button is manual).
- Automatic retries that drop one query line at a time (see Status).

## 6. Acceptance criteria

| Check | How | State |
|---|---|---|
| List: every thumbnail is the user's photo, found and validated books included | Pixel 6 | ✅ |
| *La victoire des aigles* re-imported → French edition matched automatically | Pixel 6 | ⬜ (unit test ✅) |
| An English candidate is never matched automatically, and shows « EN » | unit test + book screen | ✅ test, ⬜ screen |
| Validated book → `coverUrl` cleared, no « Couverture trouvée » | Pixel 6 | ⬜ |
| Settings: add, delete, toggle « Toute la ligne », reset to defaults | Pixel 6 | 🟡 screen shown, actions not all tried |
| Apply relaunches to-review, not-found and auto-matched books, never validated ones | Pixel 6 + database | ✅ |
| Custom list survives a restart and goes into the ZIP export | Pixel 6 | ⬜ |
| Existing tests green + new real-case tests | `./gradlew testDebugUnitTest` | ✅ |
