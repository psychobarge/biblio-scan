# BiblioScan — Spec 1 (V1, telle que construite)

App Android perso pour cataloguer sa bibliothèque en photographiant les couvertures.
Priorités : **rapidité de capture** et **zéro perte d'enregistrement**.

Cette spec décrit **le code actuel** (commit `f6e190e`, 2026-11-08). Les numéros de section sont cités dans les commentaires du code (« SPEC §5 ») : ne pas les renuméroter.
Suite : [SPEC-2-UI.md](SPEC-2-UI.md) (passe UI/UX).

## État d'avancement

| Élément | État |
|---|---|
| Capture CameraX + cadre-guide | ✅ fait, testé sur Pixel 6 (GrapheneOS) |
| Photo enregistrée avant sa ligne en base + réconciliation au démarrage | ✅ fait |
| OCR ML Kit embarqué (sans Play Services) | ✅ fait, **vérifié sur GrapheneOS** |
| Workers asynchrones OCR / recherche, 5 tentatives | ✅ fait |
| Recherche Google Books → Open Library, bascule si une source échoue | ✅ fait |
| Scoring + regroupement des éditions d'une même œuvre | ✅ fait, testé en unitaire |
| Bibliothèque (filtres, doublons) / Fiche (candidats, édition, relance, suppression) | ✅ fait ; relance avec titre corrigé vérifiée sur un vrai livre |
| Export/import ZIP, export CSV | ✅ fait ; ⚠️ **pas encore testé sur le téléphone** |
| Signature release (`keystore.properties`) | 🟡 code prêt, keystore pas créé |
| Clé Google Books (`local.properties`) | 🟡 code prêt, clé pas créée : l'app tourne sur Open Library seul |
| Test de robustesse (mode avion, app tuée pendant le traitement) | ⬜ pas fait |
| Mesure sur 30 livres (§12) | ⬜ pas faite |
| Icône d'app, README, CI GitHub Actions, réduction de taille de l'APK (56 Mo en debug) | ⬜ pas fait |
| Insets système (boutons sous la barre d'état), splash, navigation standard | ⬜ → **Spec 2** |

## 1. Périmètre

### Dans la V1
- Photo de couverture cadrée par l'utilisateur (pas de recadrage).
- Enchaînement rapide des photos ; le traitement ne bloque jamais la capture.
- Traitement asynchrone : OCR sur le téléphone, puis recherche sur des API gratuites.
- Bibliothèque locale, fiche livre, validation et correction.
- Export/import ZIP, export CSV. Distribution par GitHub, pas de Play Store.

### Hors V1
Recadrage auto, scan ISBN, modèle de vision (VLM), sync cloud, étagères/tags/prêts, écran de réglages, traduction de l'interface.

## 2. Stack

| Besoin | Choix | Version |
|---|---|---|
| Langage / UI | Kotlin + Jetpack Compose, Material 3 (thème par défaut) | Kotlin 2.1.0, BOM 2024.12.01 |
| Build | AGP 8.7.3, Gradle 8.11.1 (wrapper), **JDK 17 requis** | |
| SDK | minSdk 28 (relevé par SPEC-3 pour `ImageDecoder`), target/compile 35 | |
| Caméra | CameraX `ImageCapture` | 1.4.1 |
| OCR | ML Kit Text Recognition v2 **bundled** (latin) | 16.0.1 |
| Base | Room (schéma exporté dans `app/schemas/`) | 2.6.1 |
| Asynchrone | WorkManager | 2.10.0 |
| HTTP / JSON | OkHttp + kotlinx.serialization | 4.12.0 / 1.7.3 |
| Images | Coil | 2.7.0 |

Pas de ViewModel ni de bibliothèque de navigation : les écrans lisent directement les `Flow` Room. La navigation est un simple état (`"capture"`, `"library"` ou l'id d'un livre) dans `MainActivity`.

## 3. Parcours utilisateur (actuel)

1. **Capture** (écran d'accueil, `CaptureScreen.kt`)
   - Aperçu plein écran, cadre-guide blanc au ratio 2:3 (75 % de la hauteur), qui passe au vert tant qu'une photo s'écrit.
   - Déclencheur rond en bas : vibration à l'appui, écran prêt pour la photo suivante sans attendre. Pas de son.
   - Bouton « Bibliothèque · N en cours » **en haut à droite, superposé à l'aperçu** (il chevauche la barre d'état → Spec 2).
   - Permission caméra demandée au premier lancement ; si elle est refusée, un bouton « Autoriser la caméra ».
   - Échec de capture → toast « Photo ratée ».
2. **Bibliothèque** (`LibraryScreen.kt`)
   - Barre du haut : retour vers la capture, titre « Bibliothèque (N) », menu ⋮ (Exporter ZIP, Importer ZIP, Exporter CSV).
   - Filtres : Tous / À vérifier (`NEEDS_REVIEW` + `NOT_FOUND`) / Erreurs, avec leur compteur.
   - Ligne : miniature de la photo, titre (ou « (sans titre) »), auteurs, statut en couleur, badge « doublon possible ».
3. **Fiche** (`BookScreen.kt`)
   - Photo, message d'erreur éventuel.
   - Champs éditables : titre, auteur(s) séparés par « ; », éditeur, année, ISBN. Bouton « Valider » → `CONFIRMED` (actif si le titre n'est pas vide).
   - Candidats (top 5) : couverture, titre, auteurs · éditeur · année, score, source. Un toucher remplit le formulaire, sans valider.
   - « Relancer la recherche » avec le texte de requête modifiable.
   - Texte OCR brut ; « Supprimer ce livre » avec confirmation.

## 4. Fiabilité des enregistrements

**Une photo prise n'est jamais perdue, quel que soit l'échec qui suit.**

- La photo est toujours `filesDir/covers/{id}.jpg` ; **aucun chemin n'est stocké en base**, il est dérivé de l'id.
- Capture : CameraX écrit le JPEG → la ligne `CAPTURED` est insérée dans le scope applicatif (qui survit à l'écran) → OCR mis en file.
- Une erreur dans ce scope est journalisée, pas de crash ; la photo est rattrapée par la réconciliation.
- **Réconciliation** à chaque démarrage (`BiblioApp.onCreate`) :
  - tout `.jpg` non vide sans ligne en base → ligne `CAPTURED` ;
  - puis relance du traitement, qui reprend tout statut non final.
- Suppression : photo d'abord, puis la ligne (sinon la photo restante ressusciterait le livre).
- Room version 1, schéma exporté ; **jamais** de `fallbackToDestructiveMigration`.

## 5. Traitement asynchrone

### Machine à états
```
CAPTURED ──OCR──▶ OCR_DONE ──recherche──▶ MATCHED       (auto)
                                     ├─▶ NEEDS_REVIEW
                                     └─▶ NOT_FOUND
MATCHED / NEEDS_REVIEW / NOT_FOUND ──« Valider »──▶ CONFIRMED
étape en échec 5 fois ──▶ ERROR (photo conservée)
« Relancer » ──▶ CAPTURED (si pas encore d'OCR) ou OCR_DONE (avec la requête saisie)
```

### Workers (`work/Processing.kt`)
- La base est la source de vérité. Chaque worker traite **toutes** les lignes de son statut d'entrée ; il est idempotent.
- **OcrWorker** : statut `CAPTURED`, sans contrainte (marche hors ligne). Il enregistre :
  - le texte brut ;
  - les lignes avec la hauteur de leur boîte (JSON) ;
  - la requête construite (§6).

  À la fin, il met la recherche en file.
- **LookupWorker** : statut `OCR_DONE`, contrainte réseau `CONNECTED`.
- Deux chaînes uniques séparées (`"ocr"`, `"lookup"`, `APPEND_OR_REPLACE`) : l'OCR n'attend jamais derrière une recherche qui attend le réseau.
- Échec d'un livre : `attempts + 1` et `lastError` enregistrés. À 5 → `ERROR`, sinon `Result.retry()` (backoff exponentiel par défaut de WorkManager).

## 6. De l'OCR à la requête (`match/Matching.kt`)

1. Normalisation : minuscules, accents et ponctuation retirés.
2. Ligne supprimée entièrement si elle contient : `prix, traduit, traduction, preface, postface`.
3. Expressions retirées : `livre de poche`, `j ai lu`.
4. Mots retirés : `editions, edition, roman, prix, collection, poche, folio, points, pocket, inedit, nouvelle`.
   Mots **contenant** `flammarion, garnier, integral, gallimard` retirés aussi : l'OCR colle souvent les noms de collection imprimés sur les bords (« GARNIERFLAMMARIONH »).
5. Lignes triées par **hauteur des lettres** décroissante.
   - La hauteur est mesurée dans le sens de la ligne, à partir des 4 coins fournis par ML Kit, et non par le cadre aligné sur l'image.
   - Sinon, une ligne verticale ou penchée (nom de collection le long d'un bord) paraît énorme et passe devant le titre.
   - Corrigé le 2026-11-08 après un import réel. Requête = les 4 plus grosses, jointes, coupées à 100 caractères maximum sur une frontière de mot.
6. Requête de secours : la plus grosse ligne seule.

Limite connue : les polices décoratives sont mal lues. Le contournement actuel est « Relancer » avec le titre corrigé (vérifié efficace).

## 7. API de recherche (`match/BookSearch.kt`)

Pour chaque requête (principale, puis secours), on interroge **Google Books puis Open Library**, et on s'arrête au premier `MATCHED`.
- **Si une source échoue** (HTTP ≠ 2xx, réseau), on passe à la suivante.
- On ne lève une erreur, donc une nouvelle tentative, que si **tous** les appels ont échoué.
- Timeout 20 s par appel.

**Google Books** : `volumes?q=…&langRestrict=fr&printType=books&maxResults=10[&key=…]`
- Titre sans sous-titre (rarement sur la couverture), ISBN-13 de préférence, vignette forcée en https.
- **Sans clé : 429 constaté le 2026-11-08** (quota partagé épuisé). La clé est donc nécessaire en pratique.

**Open Library** : `search.json?q=…&language=fre&limit=10&fields=key,title,author_name,publisher,first_publish_year,isbn,cover_i`
- `User-Agent: BiblioScan/0.1 (personal Android app)`. Couverture : `covers.openlibrary.org/b/id/{cover_i}-M.jpg`.

## 8. Scoring et décision

- Mots = mots normalisés de 2 caractères ou plus.
- `t` = part des mots du titre retrouvés dans l'OCR.
- `a` = meilleure part, parmi les auteurs, des mots de son nom retrouvés dans l'OCR.
- `score = 0.6·t + 0.4·a`

Décision :
- `MATCHED` si `best ≥ 0.8` **et** `best − second ≥ 0.15`.
  - `second` = meilleur score d'une **œuvre différente** (titre + premier auteur normalisés différents). Plusieurs éditions d'un même livre ne créent donc pas d'ambiguïté.
- `NEEDS_REVIEW` si `best ≥ 0.4`.
- `NOT_FOUND` sinon.

Un candidat sans auteur plafonne à 0.6, donc il ne peut jamais être en `MATCHED`.
Les 5 meilleurs candidats sont conservés pour l'écran de revue. Les seuils restent à calibrer (§12).

Doublons : même `sourceId` (ou ISBN) sur plusieurs livres → badge, jamais bloquant.

## 9. Modèle de données (`data/Book.kt`)

```kotlin
@Serializable @Entity
data class Book(
    @PrimaryKey val id: String,          // UUID ; photo = covers/{id}.jpg
    val createdAt: Long,
    val status: Status,                  // CAPTURED, OCR_DONE, MATCHED, NEEDS_REVIEW, NOT_FOUND, CONFIRMED, ERROR
    val attempts: Int = 0,
    val lastError: String? = null,
    val ocrText: String? = null,
    val ocrLinesJson: String? = null,    // [{text, height}]
    val query: String? = null,
    val title: String? = null,
    val authors: String? = null,         // "A; B"
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val sourceId: String? = null,        // "google:{id}" ou "openlibrary:{key}"
    val coverUrl: String? = null,
    val score: Double? = null,
    val candidatesJson: String? = null,  // top 5
)
```

## 10. Sauvegarde, export, import (`data/Backup.kt`)

Tous les fichiers passent par le sélecteur de fichiers Android, donc ils sont hors de l'app et survivent à une désinstallation.

- **Export ZIP** : `library.json` (tous les livres) + `covers/{id}.jpg`.
- **Import ZIP** : ajoute seulement les livres et photos absents, n'écrase jamais rien, puis relance le traitement. Contrôles à la frontière de confiance :
  - seules les entrées `covers/<uuid>.jpg` sont acceptées ;
  - les livres dont l'id n'est pas un UUID sont ignorés ;
  - chaque photo est écrite en `.tmp` puis renommée.
- **Export CSV** : `titre,auteurs,editeur,annee,isbn,statut`, UTF-8 avec BOM (pour Excel).

## 11. Distribution (GitHub, hors Play Store)

- Repo : `github.com/psychobarge/biblio-scan`, branche `main`. Releases : APK, à installer à la main (ou suivre avec Obtainium).
- Signature release lue depuis `keystore.properties` (ignoré par git). Une seule clé, sauvegardée hors du PC. Ne jamais publier d'APK debug.
- `BOOKS_API_KEY` lue depuis `local.properties` (ignoré par git) → `BuildConfig`. À restreindre à la Books API dans Google Cloud.
- Aucune dépendance aux Google Play Services : **vérifié sur GrapheneOS**.
- Build local : `JAVA_HOME` doit pointer vers un JDK 17 (installé par scoop : `temurin17-jdk`), puis `./gradlew installDebug`.

## 12. Critères d'acceptation / vérification

| Mesure | Cible | État |
|---|---|---|
| Build + 8 tests unitaires (requête, scoring, éditions, validation ZIP) | verts | ✅ |
| Install + lancement sans crash sur Pixel 6 GrapheneOS | OK | ✅ |
| Délai entre deux photos | < 1 s | 🟡 ressenti OK, non mesuré |
| Photos perdues (app tuée pendant le traitement, mode avion) | 0 | ⬜ à tester |
| Corpus de 30 livres : `MATCHED` corrects | ≥ 60 % | ⬜ |
| Corpus : **faux `MATCHED`** | **≤ 2 %** | ⬜ |
| Corpus : résolus après revue | ≥ 90 % | ⬜ |
| Export puis import ZIP sur le téléphone, sans perte | OK | ⬜ |

## 13. Décisions prises / questions ouvertes

- ✅ Pas de recadrage en V1 ; résolution de capture 4:3, environ 1920×1440.
- ✅ Clé Google Books nécessaire (le mode sans clé renvoie 429) ; Open Library sert de secours.
- ✅ Chemin de la photo dérivé de l'id (pas de `photoPath` en base).
- ✅ Couverture de l'API conservée (`coverUrl`) en plus de la photo perso ; l'affichage utilise la photo.
- ❓ Licence et visibilité du repo (un `LICENSE` a été créé par GitHub : vérifier qu'il convient).
- ❓ Réduire l'APK de release à `arm64-v8a` seulement (l'émulateur x86 ne pourrait plus l'installer) ?
