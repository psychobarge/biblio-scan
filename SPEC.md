# Biblio Scan — Spec v0.1 (brouillon)

App Android perso pour cataloguer sa bibliothèque en photographiant les couvertures.
Priorités : **rapidité de capture** et **zéro perte d'enregistrement**.

## 1. Périmètre

### V1 (dans le périmètre)
- Photo de couverture, cadrée par l'utilisateur à l'aide d'un cadre-guide à l'écran.
- Enchaînement rapide des photos : le traitement ne bloque jamais la capture.
- Traitement asynchrone : OCR sur le téléphone, puis recherche du livre sur une API gratuite.
- Bibliothèque locale : liste, fiche livre, validation et correction des résultats douteux.
- Export/import ZIP (photos + données) et export CSV.
- Distribution par GitHub Releases (APK), pas de Play Store.

### Hors périmètre V1 (plus tard, peut-être)
- Recadrage auto / correction de perspective.
- Scan du code-barres ISBN.
- Modèle de vision (VLM), local ou cloud.
- Sync cloud, multi-appareils, étagères/tags, prêts.

## 2. Stack

| Besoin | Choix | Pourquoi |
|---|---|---|
| UI | Kotlin + Jetpack Compose | standard actuel |
| Caméra | CameraX (`ImageCapture`) | capture rapide, pas d'UI système |
| OCR | ML Kit Text Recognition v2, **version bundled** (Latin) | 100 % sur le téléphone, modèle dans l'APK, pas de Play Services requis |
| Stockage | Room (SQLite) + fichiers dans `filesDir/covers/` | source de vérité locale |
| Asynchrone | WorkManager | survit à la fermeture de l'app, au redémarrage, à l'absence de réseau |
| HTTP | OkHttp + kotlinx.serialization | léger |
| Images | Coil | affichage des miniatures |

minSdk proposé : 26.

## 3. Parcours utilisateur

1. **Écran Capture** (écran d'accueil) : aperçu caméra + cadre-guide au ratio d'un livre (~2:3).
   - Tap sur le déclencheur → son/vibration → l'écran est prêt pour la photo suivante en **< 1 s**.
   - Compteur « N en attente » affiché en surimpression.
2. **Écran Bibliothèque** : liste avec miniature, titre, auteur et badge de statut.
   Filtres : Tous / À vérifier / Erreurs.
3. **Écran Fiche** : photo d'origine, champs titre/auteur/éditeur/année, candidats proposés.
   Actions : choisir un candidat, éditer à la main, relancer la recherche avec un texte corrigé, supprimer.

## 4. Fiabilité des enregistrements (règle n°1)

**Une photo prise n'est jamais perdue, quel que soit l'échec qui suit.**

Séquence de capture :
1. `ImageCapture` écrit directement dans `filesDir/covers/{uuid}.jpg`.
2. Insertion de la ligne Room `{id=uuid, photoPath, status=CAPTURED}`.
3. Mise en file du traitement (`enqueueUniqueWork("process", KEEP)`).

- Si l'étape 2 échoue : réessayer ; ne **pas** supprimer la photo.
- Au démarrage de l'app : réconciliation.
  - Tout fichier dans `covers/` sans ligne en base → créer la ligne en `CAPTURED`.
  - Toute ligne qui n'est pas dans un état final → relancer le traitement.
- La **base est la source de vérité**, les workers sont **idempotents** : ils lisent les livres selon leur statut et peuvent être relancés sans risque.
- Les photos restent dans la mémoire de l'app ; on ne les supprime qu'à la suppression du livre.

## 5. Traitement asynchrone

### Machine à états

```
CAPTURED ──OCR──▶ OCR_DONE ──lookup──▶ MATCHED       (auto, score élevé)
                                   ├─▶ NEEDS_REVIEW  (candidats douteux)
                                   └─▶ NOT_FOUND     (aucun candidat)
MATCHED / NEEDS_REVIEW / NOT_FOUND ──utilisateur──▶ CONFIRMED
toute étape ──échec répété──▶ ERROR (photo conservée, bouton « Réessayer »)
```

### Workers
- **OcrWorker** (sans contrainte, tourne hors ligne) : traite les livres en `CAPTURED`.
  Il enregistre le texte brut **et** les lignes avec leur hauteur de boîte (JSON), puis passe en `OCR_DONE`.
- **LookupWorker** (contrainte `CONNECTED`) : traite les livres en `OCR_DONE`.
  Hors ligne, le travail attend le réseau tout seul.
- Retry : backoff exponentiel de WorkManager. Après 5 échecs → `ERROR` + `lastError`.
- Appels API séquentiels (un livre à la fois) pour rester sous les quotas.

## 6. De l'OCR à la requête

L'OCR d'une couverture est bruité : éditeur, collection, « Prix Goncourt », citations de presse…

1. Récupérer les lignes ML Kit avec la hauteur de leur boîte englobante.
2. Normaliser : minuscules, sans accents, sans ponctuation.
3. Retirer les mots parasites connus : `editions, roman, prix, collection, poche, folio, livre de poche, j'ai lu, points, traduit de, preface…`. Liste en dur, à enrichir.
4. Trier les lignes par hauteur décroissante : le titre et l'auteur sont généralement les plus gros.
5. Requête = les 3 à 4 plus grosses lignes, jointes, tronquées à environ 100 caractères.

## 7. API de recherche

Gestion de la clé API : voir §11.

Les livres sont **en français** mais pas forcément d'auteurs français (traductions), donc on filtre sur la langue, pas sur le pays.

**Principale : Google Books**
```
GET https://www.googleapis.com/books/v1/volumes
    ?q={requête}&langRestrict=fr&maxResults=10&printType=books&key={clé}
```
- Gratuite. Avec une clé (projet Google Cloud gratuit) : 1000 requêtes/jour par défaut, largement suffisant.
- Champs utilisés : `id, volumeInfo.title, subtitle, authors, publisher, publishedDate, industryIdentifiers, imageLinks.thumbnail`.

**Secours : Open Library** (si 0 résultat ou score faible côté Google)
```
GET https://openlibrary.org/search.json?q={requête}&language=fre&limit=10
    &fields=key,title,author_name,publisher,first_publish_year,isbn,cover_i
```
- Gratuite, sans clé. Fixer un `User-Agent` qui identifie l'app.

Si la requête « 3 à 4 lignes » ne donne rien, une seconde tentative envoie seulement la plus grosse ligne.

## 8. Scoring et décision

Pour chaque candidat :
- `t` = part des mots du titre du candidat retrouvés dans le texte OCR.
- `a` = part des noms d'auteur du candidat retrouvés dans le texte OCR (0 s'il n'y a pas d'auteur).
- `score = 0.6·t + 0.4·a`

Décision :
- `MATCHED` si `best ≥ 0.8` **et** `best − second ≥ 0.15`.
- `NEEDS_REVIEW` si `best ≥ 0.4` ; on garde les 5 meilleurs candidats.
- `NOT_FOUND` sinon.

**Principe : un faux positif coûte plus cher qu'une vérification.** En cas de doute, `NEEDS_REVIEW`.
Seuils à calibrer sur le corpus de test (§12).

Doublons : un `googleId` ou un ISBN déjà présent → badge « doublon possible ». Ce n'est pas bloquant : on peut posséder deux exemplaires.

## 9. Modèle de données (Room)

```kotlin
@Entity
data class Book(
    @PrimaryKey val id: String,          // UUID ; la photo est toujours covers/{id}.jpg (pas de chemin stocké)
    val createdAt: Long,
    val status: Status,
    val attempts: Int = 0,
    val lastError: String? = null,
    // OCR
    val ocrText: String? = null,
    val ocrLinesJson: String? = null,    // [{text, height}]
    val query: String? = null,           // requête effectivement envoyée
    // Résultat retenu
    val title: String? = null,
    val authors: String? = null,         // "A; B"
    val publisher: String? = null,
    val year: String? = null,
    val isbn: String? = null,
    val sourceId: String? = null,        // id Google Books ou clé Open Library
    val coverUrl: String? = null,
    val score: Double? = null,
    val candidatesJson: String? = null,  // top 5 bruts, pour l'écran de revue
)
```

## 10. Sauvegarde, export, import

Une app installée hors Play Store risque davantage une désinstallation forcée : une mise à jour signée avec une autre clé ne s'installe pas par-dessus (voir §11). Désinstaller efface la base **et** les photos. D'où :

- **Export ZIP** : `library.json` (tous les livres) + dossier `covers/`. Le fichier est écrit via le sélecteur de fichiers Android (`ACTION_CREATE_DOCUMENT`), donc hors de l'app : il survit à une désinstallation.
- **Import ZIP** : restaure les livres et les photos. Un livre dont l'`id` existe déjà est ignoré (pas d'écrasement).
- **Export CSV** : lecture seule, pour un tableur.
- L'Auto Backup Android ne suffit pas : les photos dépassent sa limite de taille.

## 11. Distribution (GitHub, hors Play Store)

- Code sur un repo GitHub. APK publié dans les **GitHub Releases**, installé à la main (sideload).
  Mises à jour : à la main, ou via Obtainium, qui surveille les releases GitHub.
- **Signature** : une seule clé de release (keystore), sauvegardée hors du PC (gestionnaire de mots de passe).
  - Perdre ce keystore = impossible de mettre à jour sans désinstaller, donc perte des données sans export.
  - Ne jamais publier d'APK debug : sa clé diffère de la clé de release, il ne s'installe pas par-dessus.
  - Le keystore et ses mots de passe ne sont **jamais commités** : ils sont lus depuis `keystore.properties`, ignoré par `.gitignore`.
- **Clé Google Books** :
  - Jamais dans le repo : lue depuis `local.properties` (ignoré par git) et injectée dans `BuildConfig.BOOKS_API_KEY`.
  - Si elle est absente, l'app appelle l'API sans clé (quota partagé, moins fiable).
  - Une clé embarquée dans un APK public est extractible : la restreindre à la seule « Books API » dans la console Google Cloud. Elle est gratuite, donc le risque se limite à l'épuisement du quota.
- Pas de dépendance aux Google Play Services : ML Kit est en version bundled (déjà le choix du §2), donc l'app marche aussi sur les téléphones sans Google.
- CI GitHub Actions (build + release signée sur tag) : plus tard, les builds locaux suffisent au début.

## 12. Critères d'acceptation / mesure

Corpus de test : **30 livres réels** de la bibliothèque, mélangeant auteurs français, traductions et poches.

| Mesure | Cible |
|---|---|
| Délai entre deux photos | < 1 s |
| Photos perdues (kill de l'app pendant le traitement, mode avion) | 0 |
| Correct en `MATCHED` automatique | ≥ 60 % |
| **Faux `MATCHED`** (mauvais livre accepté auto) | **≤ 2 %** |
| Résolu après revue manuelle (choix d'un candidat) | ≥ 90 % |

## 13. Questions ouvertes
- Licence du repo (MIT, GPL…) ? Repo public ou privé ?
- ~~Clé Google Books ?~~ Tranché : **clé nécessaire**. Testé le 2026-11-08, l'appel sans clé renvoie 429 (quota partagé épuisé). Sans clé, l'app se rabat sur Open Library.
- Résolution des photos : 1920 px de large suffisent probablement pour l'OCR ; compresser pour l'espace disque ?
- Rogner simplement la photo au cadre-guide (crop rectangulaire fixe, trivial) dès la V1 ?
- Faut-il garder la couverture de l'API en plus de la photo perso, pour un affichage plus propre ?
