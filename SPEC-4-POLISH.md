# BiblioScan — Spec 4 : peaufinage (brouillon v0.1)

Base : [SPEC.md](SPEC.md), [SPEC-2-UI.md](SPEC-2-UI.md), [SPEC-3-IMPORT.md](SPEC-3-IMPORT.md). Part de `main` (`108f86f`).

## État (branche `feat/polish`, 2026-11-08)

Implémentée. 25 tests unitaires verts, dont les cas réels *La victoire des aigles* (l'édition française est trouvée automatiquement, les éditions anglaises ne le sont jamais) et *La maison des autres* (le tome passe devant le cycle).

Vérifié sur le Pixel 6 :
- miniatures = photos perso, y compris pour les livres validés ;
- menu ⋮ → Paramètres → liste des mots ignorés ;
- « Appliquer aux livres à vérifier » → « 3 livre(s) relancé(s) », recherches refaites avec le nouveau score.

Constats sur les livres réels :
- **Faux « Trouvé » de l'ancien calcul** : *La Bête humaine* était « Trouvé » sous le titre « Zola », par Henri Mitterand (l'auteur de la préface). Le bouton a donc été **étendu aux livres « Trouvé »** et renommé « Appliquer aux livres non validés » (voir §4.4).
- **Mots parasites, non traité dans cette spec** qui bloquent Google** : pour *La Curée*, la requête « 20la la curee texte integra gf » ne trouve rien. « ZOLA » a été lu « 20LA », et « integra » est tronqué. Google exige presque tous les mots : un seul mot parasite fait échouer la recherche. On peut ajouter « gf » et « integra » à la liste des mots ignorés, mais une vraie parade serait de relancer automatiquement la recherche sans chaque ligne tour à tour.

Pas encore vérifié : badge « EN » visible sur une fiche, export puis import ZIP d'une liste personnalisée, « Rétablir la liste par défaut ».

## 1. Miniatures de la bibliothèque = toujours ta photo

- Dans la liste, la miniature est **toujours la photo prise par l'utilisateur**, quel que soit le statut du livre.
- Cela annule la décision SPEC-2 §8 (« couverture de l'API une fois le livre trouvé »).
- La fiche garde les deux images côte à côte (« Votre photo » / « Couverture trouvée ») : la couverture de l'API y sert à vérifier visuellement le bon livre.
- Les cartes « Livres proposés » gardent la couverture de l'API (elles servent justement à comparer).

## 2. Préférer les éditions françaises

### 2.1 Constat (cas réel : *La victoire des aigles*, Naomi Novik, tome 5 de Téméraire)
- Google Books **a** l'édition française : « La victoire des aigles », langue `fr`.
- L'app a pourtant proposé « Victory of Eagles (The Temeraire Series, Book 5) », en anglais, pour trois raisons cumulées :
  1. **Une requête bruitée** : « temeraire naomi novik **hntasy** la victoire des aigles **5** ». « hntasy » est « FANTASY » mal lu, et « 5 » le numéro du tome. Avec ces mots en plus, Google ne renvoyait plus l'édition française.
  2. **Un filtre de langue Google peu fiable** : avec `langRestrict=fr`, Google renvoie quand même des éditions en anglais, suédois ou roumain. Elles étaient classées en tête.
  3. **Des titres courts trop bien notés** : le candidat « Temeraire » (nom de la série) obtenait 1.0, car son unique mot figure sur la couverture. Le même défaut met *La grande patience* (le cycle) à égalité avec *La maison des autres* (le tome).
- Vérifié à la main : « naomi novik la victoire des aigles » renvoie l'édition française en premier.

### 2.2 Changements
1. **Langue du candidat** : nouveau champ `language` dans `Candidate`.
   - Google : `volumeInfo.language` (`fr`, `en`, …).
   - Open Library : inconnue (`null`), car le titre renvoyé est celui de l'œuvre, souvent en langue originale.
2. **Pénalité de langue** : un candidat dont la langue est connue et **différente de `fr`** voit son score **divisé par 2**. Il ne peut donc jamais être retenu automatiquement, mais reste proposé.
3. **Badge de langue** sur les cartes « Livres proposés » quand elle n'est pas `fr` (« EN », « SV »…), pour que l'utilisateur voie qu'il choisit une édition étrangère.
4. **Requête nettoyée** : on retire aussi
   - les **nombres isolés** (numéro de tome, prix) ;
   - les **étiquettes de genre** : `fantasy, science fiction, sf, thriller, policier, polar, bd, manga, jeunesse`.
5. **Score « dans les deux sens »** pour le titre : on combine
   - `p` = part des mots du **titre du candidat** retrouvés sur la couverture (le calcul actuel) ;
   - `r` = part des mots de la **requête** expliqués par le titre + les auteurs du candidat ;
   - titre = `2·p·r / (p + r)` (moyenne harmonique). Le score final reste `0.6·titre + 0.4·auteur`, puis la pénalité de langue.

   Effet attendu sur les cas réels, avec la requête nettoyée « temeraire naomi novik hntasy la victoire des aigles » :

   | Candidat | Avant | Après |
   |---|---|---|
   | La victoire des aigles (fr) | absent de la liste | **≈ 0.91 → trouvé automatiquement** |
   | Temeraire (fr) | 1.0 | ≈ 0.73 |
   | Victory of Eagles (en) | 0.49 | ≈ 0.20 |

   Pour *La maison des autres*, « La grande patience 1 : La maison des autres » passe devant « La grande patience » seul.
6. **`r` est calculé sur la requête** (nettoyée, ou tapée par l'utilisateur), pas sur tout le texte OCR, pour que les mots parasites d'une couverture (« folio », « Prix Nobel »…) ne pénalisent pas le bon candidat.

### 2.3 Tests unitaires
Chaque cas réel devient un test, avec les candidats et le texte OCR réellement obtenus (stockés en base sur le Pixel) :
- *La victoire des aigles* : l'édition `fr` est trouvée automatiquement ; l'anglaise ne l'est jamais.
- *La maison des autres* : le tome passe devant le cycle.
- *L'étranger* (tests existants) : toujours trouvé automatiquement.
- Nombres isolés et étiquettes de genre retirés de la requête.

### 2.4 Livres déjà validés
Rien n'est modifié automatiquement : un livre déjà validé (`CONFIRMED`) reste tel quel. Pour corriger *La victoire des aigles*, il faut ouvrir la fiche, taper le titre français, puis appuyer sur « Rechercher avec ce titre et cet auteur ».

## 3. Après validation : plus de couverture de l'API

Une fois un livre **validé** (`CONFIRMED`), la couverture trouvée en ligne ne sert plus.

- À la validation, `coverUrl` est **vidé**.
- La fiche d'un livre validé n'affiche plus que « Votre photo ». La colonne « Couverture trouvée » disparaît.
- La liste des « Livres proposés » est conservée (c'est du texte, utile pour changer d'avis). Ses cartes continuent d'afficher leur couverture en ligne.
- Si on relance une recherche sur un livre validé et qu'un livre est trouvé automatiquement, `coverUrl` est de nouveau rempli jusqu'à la prochaine validation.
- La photo de l'utilisateur est **conservée telle quelle**, en pleine résolution : une réduction de taille après validation a été envisagée puis **écartée** le 2026-11-08.

## 4. Écran Paramètres : liste des mots ignorés

### 4.1 Besoin
Les mots retirés de la recherche (noms d'éditeur, de collection, « Prix Goncourt »…) sont aujourd'hui écrits en dur dans le code (SPEC §6). Les couvertures réelles en révèlent régulièrement de nouveaux (« GARNIERFLAMMARIONH », « FANTASY »…). L'utilisateur doit pouvoir **compléter ou corriger cette liste lui-même**, sans recompiler l'app.

### 4.2 Accès
- Bibliothèque → ⋮ → **Paramètres**, en dernière entrée du menu.
- Écran standard : barre du haut avec retour et le titre « Paramètres ». Une seule section pour l'instant : **« Mots ignorés sur les couvertures »**.

### 4.3 La liste
- Une ligne par entrée, dans l'ordre alphabétique : le mot ou l'expression, un interrupteur **« Ignorer toute la ligne »** et un bouton 🗑 de suppression.
- En haut : un champ **« Ajouter un mot ou une expression »** et un bouton « Ajouter ».
  - La saisie est normalisée comme le texte OCR (minuscules, sans accents ni ponctuation). « Prix » et « prix » sont donc la même entrée, et un doublon n'est pas ajouté.
- Les **deux comportements**, qui couvrent les 4 listes actuelles du code :

  | Interrupteur | Effet sur une ligne lue sur la couverture | Exemples de la liste par défaut |
  |---|---|---|
  | désactivé (par défaut) | le mot ou l'expression est **retiré** de la ligne, le reste est gardé | editions, collection, folio, livre de poche, j ai lu, fantasy |
  | « Ignorer toute la ligne » | la ligne **entière** est écartée si elle contient le mot | prix, traduit, traduction, preface, postface |

- **Mots collés par l'OCR** : une entrée d'**un seul mot de 7 lettres ou plus** est aussi retirée quand elle apparaît **à l'intérieur** d'un mot (« garnierflammarionh » contient « garnier » et « flammarion »). C'est automatique, sans option à régler. Les mots courts (« prix », « folio ») ne sont cherchés qu'en mot entier, pour ne pas abîmer de vrais titres.
- En bas : **« Rétablir la liste par défaut »**, avec confirmation, qui remplace la liste par celle livrée avec l'app.
- La liste par défaut regroupe les mots actuels du code et les ajouts de §2.2 (étiquettes de genre).

### 4.4 Appliquer les changements aux livres existants
- Les changements valent pour **les prochains livres traités**.
- Un bouton **« Appliquer aux livres non validés »** reconstruit la requête des livres « À vérifier », « Introuvable » **et « Trouvé »** (trouvés automatiquement, jamais validés par l'utilisateur) à partir de leur texte OCR déjà enregistré (`ocrLinesJson`), puis relance la recherche. Pour un livre « Trouvé », les champs remplis automatiquement (titre, auteurs, éditeur…) sont vidés avant la relance, pour qu'une ancienne erreur ne subsiste pas.
  - Pas de nouvel OCR : c'est rapide et ça ne relit pas les photos.
  - Les livres validés ne sont **jamais** touchés.
  - Un message indique « N livres relancés ».

### 4.5 Stockage et technique
- La liste est stockée en JSON dans les préférences de l'app (`SharedPreferences`), par exemple `[{"text": "prix", "wholeLine": true}, …]`. Si les préférences sont absentes ou illisibles, l'app utilise la liste par défaut.
- Elle est **incluse dans l'export ZIP** (`settings.json`) et restaurée à l'import, uniquement si l'app utilise encore la liste par défaut, pour ne jamais écraser une liste personnalisée sans le demander.
- `Matching.buildQuery` / `clean` reçoivent la liste en paramètre au lieu de constantes. Le code reste pur Kotlin, donc testable.
- Les workers lisent la liste au début de chaque passage.

### 4.6 Tests
- Unitaires : comportement « retirer le mot », « ignorer toute la ligne », mot collé de 7 lettres ou plus, mot court non cherché à l'intérieur d'un mot, normalisation de la saisie, doublon refusé.
- La liste par défaut redonne **exactement** les requêtes des tests actuels (aucune régression).
- Sur le Pixel : ajouter « hntasy » → « Appliquer aux livres non validés » → la requête du livre concerné ne contient plus ce mot.

## 5. Hors périmètre
- Interroger la BnF (catalogue des éditions françaises) : une piste si Google et Open Library restent insuffisants, mais c'est une troisième API.
- Rendre la langue préférée configurable.
- Saisir la clé Google Books dans les Paramètres (elle reste dans `local.properties` au moment du build).
- Re-scorer automatiquement les livres déjà traités.

## 6. Critères d'acceptation

| Critère | Comment vérifier |
|---|---|
| Liste : toutes les miniatures sont les photos perso, y compris pour les livres trouvés ou validés | Pixel 6 |
| Réimport de la photo de *La victoire des aigles* → « La victoire des aigles » trouvé automatiquement, en français | Pixel 6 |
| Un candidat anglais n'est jamais retenu automatiquement, et affiche le badge « EN » | test unitaire + fiche |
| Livre validé → `coverUrl` vide, fiche sans « Couverture trouvée » | Pixel 6 |
| Paramètres : ajouter, supprimer, basculer « toute la ligne », rétablir la liste par défaut | Pixel 6 |
| « Appliquer aux livres non validés » relance les livres à vérifier, introuvables et trouvés automatiquement, jamais les validés | Pixel 6 + base |
| La liste personnalisée survit à un redémarrage et part dans l'export ZIP | Pixel 6 |
| Tests existants verts + nouveaux tests sur les cas réels | `./gradlew testDebugUnitTest` |
