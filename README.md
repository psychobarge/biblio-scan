# BiblioScan

A small Android app to catalogue your home library by photographing book covers.
Built for French-language books (including translations), works fully without Google Play Services (tested on GrapheneOS).

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/psychobarge/biblio-scan)

## Features

- **Fast capture** – shoot covers one after another; the preview window is exactly the photo that gets saved.
- **Import from the gallery** – pick photos taken with the regular camera app (system photo picker, no storage permission).
- **Background processing** – on-device OCR (ML Kit, bundled), then lookup on Google Books and Open Library, preferring French editions.
- **« À valider »** – every scanned book waits there until you validate it: one by one (pick one of the proposed books, fix the title, or search again with your own words), or a whole shelf of sure matches at once.
- **Rooms and spots** – validating files the book in a room and a spot (« Salon › Étagère noire 1 »); browse the library one shelf at a time, move books with a long press.
- **Library** – search by title or author, always across the whole house, sort by date, title or author, duplicate hints.
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

API keys are entered in the app (⋮ → « Paramètres » → « Clés API ») and stored only on the phone, never in the APK or the repo:

- **Google Books** (optional, free): without it, Google rate-limits quickly and lookups fall back to Open Library.
- **DeepSeek** (optional, paid): the cover photo is sent to DeepSeek to read the title and author, which works much better than on-device OCR. Without it, only the OCR is used.

Optional file at the project root (git-ignored):

- `keystore.properties` – release signing (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
