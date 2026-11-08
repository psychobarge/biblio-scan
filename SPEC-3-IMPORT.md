# BiblioScan — Spec 3 : import de photos depuis le téléphone (v0.4)

Base : [SPEC.md](SPEC.md) (traitement, fiabilité) et [SPEC-2-UI.md](SPEC-2-UI.md) (écrans). Cette spec part de `main` après la fusion de la passe UI/UX (`e3670ef`).

## État (branche `feat/photo-import`, 2026-11-08)

Implémentée. Vérifié sur le Pixel 6 (GrapheneOS) :
- ⋮ → Importer des photos ouvre le sélecteur système ; **aucune permission de stockage** n'est accordée à l'app ;
- photos de galerie copiées en 1446×1920 (300 à 450 Ko au lieu d'environ 4 Mo), à l'endroit, puis traitées sans autre action.

17 tests unitaires verts (dont la taille cible et le filtre des noms de collection).

Pas encore vérifié :
- import de 20 à 50 photos d'un coup ;
- app tuée pendant l'import ;
- image corrompue ;
- photo prise en paysage.

Ajouts faits pendant l'essai réel :
- la liste remonte en haut quand un nouveau livre arrive (avant, les livres importés restaient hors de vue) ;
- correction de la mesure de taille du texte et filtre des noms de collection (voir SPEC §6).

Ordre d'affichage : avec le tri « date d'ajout » (plus récent d'abord), la dernière photo du lot apparaît en premier.

## 1. Besoin

Prendre une rafale de photos avec l'appareil photo du téléphone, sans ouvrir l'app, puis plus tard :
**Bibliothèque → Importer des photos → sélection → traitement de toutes les photos**, sans autre action.

## 2. Décisions

- ✅ **minSdk 26 → 28** (Android 9), pour utiliser `ImageDecoder`.
- ✅ **Pas de recadrage** : les photos importées partent directement en OCR (voir §8 pour le test qui a conduit à ce choix).
- ❌ « Partager vers BiblioScan » depuis la galerie : non nécessaire.
- ⏸ Détection des doublons à l'import (empreinte SHA-256) : pas pour le moment. Le badge « doublon possible » existant suffit.

## 3. Parcours

1. Dans la Bibliothèque, l'entrée **« Importer des photos »** se trouve à deux endroits :
   - dans le menu ⋮ ;
   - comme second bouton de l'état vide, à côté de « Scanner un livre ».
2. Le **sélecteur de photos Android** (Photo Picker) s'ouvre, **images uniquement**, en sélection multiple (jusqu'à la limite du système, en général 100).
   - Aucune permission de stockage n'est demandée : seules les photos choisies sont lisibles par l'app.
   - Il est intégré au système depuis Android 13, donc disponible sur GrapheneOS.
3. Retour immédiat à la Bibliothèque, avec une Snackbar « Import de N photos… ».
4. Chaque photo devient un livre `CAPTURED` dès qu'elle est copiée. Le bandeau « N livres en cours de traitement » et la liste se remplissent au fur et à mesure.
5. À la fin : Snackbar **« N photos importées »**, ou « N photos importées, K illisibles » s'il y a eu des échecs.
6. Ensuite, c'est le traitement habituel : OCR → recherche → Trouvé / À vérifier.

## 4. Copie d'une photo importée

Pour chaque URI sélectionnée, **dans l'ordre de la sélection**, de façon séquentielle et dans le scope applicatif (l'import continue si on quitte l'écran) :

1. **Décoder** avec `ImageDecoder`, qui applique l'orientation EXIF, en **réduisant** à 1920 px maximum sur le grand côté. Une photo de galerie fait environ 4000×3000 et 4 Mo ; une photo prise dans l'app fait 1280×1920.
2. **Ré-encoder** en JPEG (qualité 90) vers `covers/{uuid}.jpg.tmp`, puis **renommer** en `.jpg` : un fichier à moitié écrit n'est jamais vu comme une photo.
3. **Insérer** la ligne `CAPTURED`, avec `createdAt` = heure de l'import + index pour garder l'ordre de la sélection.
4. Mettre l'OCR en file toutes les 10 photos et à la fin, pour que le traitement démarre sans attendre la fin de l'import.

Une photo illisible (fichier corrompu, format non supporté) est ignorée et comptée dans « K illisibles ». Elle ne bloque pas les autres.

Conseil de prise de vue, à afficher une fois dans l'état vide ou la Snackbar : *le livre doit remplir la photo, sans autre livre visible derrière*.

## 5. Fiabilité

- Mêmes invariants que la capture (SPEC §4) : la photo est sur le disque **avant** sa ligne, et la réconciliation au démarrage rattrape une photo sans ligne.
- **Si l'app est tuée pendant l'import** : les photos déjà copiées sont sauves. Le reste n'est pas importé, puisque les URI du sélecteur ne sont valides que le temps de la session. L'utilisateur réimporte les photos manquantes.
- Réimporter la même photo crée un second livre, signalé ensuite par le badge « doublon possible ».
- Les photos de la galerie ne sont **jamais modifiées ni supprimées**.
- Aucun changement de schéma de base de données.

## 6. Technique

| Point | Choix |
|---|---|
| Sélection | `ActivityResultContracts.PickMultipleVisualMedia()` + `PickVisualMedia.ImageOnly` |
| Décodage | `ImageDecoder` + `setTargetSize` (EXIF appliqué) ; minSdk 28 |
| Import | `Books.importPhotos(ctx, uris): ImportResult(ok, failed)`, à côté de `onPhotoSaved` |
| Test unitaire | calcul de la taille cible : réduction, ratio conservé, pas d'agrandissement des petites images |

## 7. Hors périmètre

- **Tout recadrage** des photos importées, manuel ou automatique.
- « Partager vers BiblioScan » depuis la galerie.
- Détection des doublons à l'import.
- Import de PDF ou de vidéos ; suppression des photos de la galerie.

## 8. Test du recadrage automatique (2026-11-08) : abandonné

- **Méthode** : détecteur d'objets ML Kit embarqué (sans les services Google), lancé sur le Pixel 6 avec **10 photos réelles** prises à l'appareil photo du téléphone (livre tenu en main), plus 6 photos prises dans l'app.
- **Résultat** : sur les 10 photos réelles, le détecteur n'a **jamais** trouvé le livre. Il encadre un élément de l'illustration de la couverture (un flacon, une roue, une médaille, un personnage). La règle de décision aurait appliqué automatiquement 3 de ces cadres, ce qui aurait **coupé le titre et l'auteur**.
- **Constat** : sur ces photos, le livre remplit déjà environ 85 % de l'image. Le reste est une main et un fond flou, sans texte parasite. Le recadrage n'apporte presque rien à l'OCR.
- **À reconsidérer seulement si** des livres en arrière-plan provoquent des erreurs d'identification en pratique. Pistes alors :
  - détection des bords (OpenCV, lourd) ;
  - recadrage manuel avant OCR : un écran avec un cadre ajustable par 4 poignées et un bouton « Pivoter 90° », les photos importées attendant dans un statut `TO_CROP` jusqu'à validation.

## 9. Critères d'acceptation

| Critère | Comment vérifier |
|---|---|
| 20 photos sélectionnées → 20 livres dans la liste, traités ensuite sans action | Pixel 6 |
| Pas de permission de stockage demandée | Réglages de l'app → Autorisations |
| Photo importée ≤ 1920 px, orientation correcte (photo prise en paysage incluse) | miniature + taille du fichier |
| App tuée pendant l'import → aucune photo copiée perdue, aucun `.tmp` vu comme un livre | adb pendant un import de 50 photos |
| Une image corrompue dans la sélection → les autres sont importées, message « 1 illisible » | manuel |
| Les 12 tests existants restent verts + le nouveau test de taille cible | `./gradlew testDebugUnitTest` |
