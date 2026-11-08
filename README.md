# BiblioScan

A small Android app to catalogue your home library by photographing book covers.
Built for French-language books (including translations), works fully without Google Play Services (tested on GrapheneOS).

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/psychobarge/biblio-scan)

## Features

- **Fast capture** – shoot covers one after another; the preview window is exactly the photo that gets saved.
- **Import from the gallery** – pick photos taken with the regular camera app (system photo picker, no storage permission).
- **Background processing** – on-device OCR (ML Kit, bundled), then lookup on Google Books and Open Library, preferring French editions.
- **Review queue** – uncertain matches land in "À vérifier"; pick one of the proposed books, fix the title, or search again with your own words.
- **Library** – search by title or author, sort by date, title or author, filters by status, duplicate hints.
- **Settings** – edit the list of words ignored on covers (publisher, collection, genre labels…).
- **Your data stays yours** – photos and the database live on the phone; ZIP export/import (photos + data) and CSV export.

## Install

- **Obtainium**: tap the badge above on your phone, or add `https://github.com/psychobarge/biblio-scan` in Obtainium.
- **Manually**: download the APK from the [latest release](https://github.com/psychobarge/biblio-scan/releases/latest).

Requires Android 9 or later. Network is only used to look books up.

## Build

JDK 17 and the Android SDK (platform 35) are required.

```bash
./gradlew assembleDebug
```

Optional files at the project root (both git-ignored):

- `local.properties` – `BOOKS_API_KEY=…`: a free Google Books API key (restrict it to the Books API). Without one, Google rate-limits quickly and lookups fall back to Open Library.
- `keystore.properties` – release signing (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

Design notes and decisions are in the `SPEC*.md` files (in French).
