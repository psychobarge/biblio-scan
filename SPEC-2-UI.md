# BiblioScan — Spec 2 : passe UI/UX (brouillon v0.1)

Base : [SPEC.md](SPEC.md) (V1 telle que construite). Cette passe ne touche **ni au traitement ni aux données** : uniquement l'interface, la navigation et la finition.

## État (branche `feat/ui-ux`, 2026-11-08)

Implémentée en une seule passe, et non en 4 PR. Vérifié sur Pixel 6 GrapheneOS en **navigation 3 boutons** :
- bibliothèque, caméra et fiche : rien sous les barres système ;
- icônes de statut claires sur la caméra.

12 tests unitaires verts (dont le tri et la recherche).

Pas encore vérifié :
- navigation par gestes ;
- mode sombre ;
- police à 200 % ;
- mode revue de bout en bout (aucun livre « à vérifier » sur le téléphone) ;
- écran de permission refusée.

Écarts et précisions par rapport au texte ci-dessous :
- **Caméra : ce que tu vois = la photo.** L'aperçu est une fenêtre 2:3, et non plus un plein écran avec un cadre dessiné par-dessus. Un `ViewPort` CameraX 2:3 recadre l'aperçu et le JPEG sur la même zone.
  - Photo obtenue : 1280×1920.
  - Vérifié sur le Pixel : même cadrage que l'aperçu.
  - Avant, l'aperçu rognait les côtés pour remplir l'écran, alors que la photo gardait tout le champ 4:3 : elle prenait donc beaucoup plus large que le cadre.
- Hors mode revue, « Valider » ramène à la bibliothèque, avec une Snackbar « Livre validé ».
- Fiche : un bouton **« Rechercher avec ce titre et cet auteur »** sous le formulaire.
  - Il enregistre d'abord les champs saisis, puis relance la recherche avec titre + auteur.
  - Le score compare désormais les candidats au texte OCR **et** à la requête : un titre tapé à la main compte, même si l'OCR l'avait mal lu.
  - La relance en texte libre reste disponible dans ⋮.
- Mode revue : les livres sont parcourus du plus ancien au plus récent. « Passer » avance sans rien changer. Le compteur « n / total » est figé au total de départ.
- Couleurs dynamiques : sur le téléphone de test, la palette système est monochrome, donc l'app est en gris. C'est voulu, elle suit le système.

## 1. Objectifs

1. L'app s'ouvre sur la **bibliothèque**, après un splash système.
2. Aucun contrôle sous la barre d'état ou la barre de navigation. Aujourd'hui, le bouton « Bibliothèque » de la capture chevauche les icônes de statut.
3. Des actions placées là où un utilisateur Android les attend : FAB pour scanner, barre de contrôles en bas de la caméra, barre d'app standard.
4. Une revue des livres « à vérifier » rapide, sans aller-retour dans la liste.
5. Accessibilité et thème : couleurs dynamiques, mode sombre, cibles tactiles de 48 dp, statuts qui ne reposent pas sur la seule couleur.

## 2. Hors périmètre
Tablette et paysage (l'app reste en portrait), traduction (pas de `strings.xml` multi-langue), écran de réglages, animations élaborées, recadrage.

## 3. Fondations

### 3.1 Edge-to-edge et insets
- `enableEdgeToEdge()` dans `MainActivity` (Android 15 l'impose déjà avec targetSdk 35 : c'est la cause du chevauchement actuel).
- Écrans avec `Scaffold` : utiliser le `paddingValues` du Scaffold, qui gère déjà les insets des barres.
- Écran caméra : l'aperçu reste plein écran **sous** les barres système, mais tous les contrôles sont placés dans `WindowInsets.safeDrawing`.
- Vérifier avec la navigation par gestes **et** la navigation 3 boutons.

### 3.2 Thème
- Un `BiblioTheme` Material 3 :
  - couleurs dynamiques (Material You) sur Android 12+, palette fixe sinon ;
  - clair/sombre selon le système.
- Barre d'état et barre de navigation transparentes, icônes adaptées au thème (géré par `enableEdgeToEdge`).

### 3.3 Icône et splash
- **Icône adaptative** : premier plan (livre + viseur), fond uni, **couche monochrome** pour les icônes à thème d'Android 13+.
- **Splash** : `androidx.core:core-splashscreen`, `installSplashScreen()` avant `setContent`.
  - Icône de l'app sur la couleur de fond du thème.
  - **Aucune attente artificielle** : le splash disparaît dès que la première frame est prête.
  - Il est gardé à l'écran (`setKeepOnScreenCondition`) seulement jusqu'à la première émission de la liste des livres, pour éviter un état vide qui clignote.

### 3.4 Navigation
- Passer à **Navigation Compose** (routes typées), le tri-état `String` actuel ne suffit plus avec le mode revue.
- Graphe :
  - `library` (départ) → `capture` ;
  - `library` → `book/{id}` ;
  - `book/{id}` → `book/{nextId}` (mode revue).
- Retour prédictif : `android:enableOnBackInvokedCallback="true"` dans le manifeste.
- Retour depuis la capture → bibliothèque.

## 4. Écrans

### 4.1 Bibliothèque (écran d'accueil)
- **Barre d'app** (`TopAppBar`, réduite au scroll) :
  - titre « Bibliothèque » ;
  - action 🔍 recherche ;
  - action ⇅ tri ;
  - menu ⋮ : Exporter ZIP, Importer ZIP, Exporter CSV.
- **Tri** : date d'ajout (plus récent d'abord, **par défaut**), titre A→Z, auteur A→Z.
  - Comparaison insensible aux accents et à la casse ; tri par auteur sur le premier auteur.
  - Les livres sans titre ou sans auteur vont en fin de liste.
  - Le choix est mémorisé localement (préférence de l'app).
- **Recherche** : filtre local, insensible aux accents, sur titre et auteur, combinable avec les filtres de statut.
- **Filtres** (chips, scroll horizontal) : Tous · À vérifier · En cours (`CAPTURED`, `OCR_DONE`) · Erreurs. Chacun avec son compteur ; un filtre vide reste affiché mais grisé.
- **Bandeau de traitement** : « N livres en cours de traitement » sous la barre d'app, tant que N > 0, avec un indicateur de progression indéterminé. Remplace le compteur dans le bouton de la caméra.
- **Bandeau de revue** : « N livres à vérifier — Commencer » si N > 0. Il ouvre le premier livre en mode revue (§4.3).
- **Liste** :
  - miniature 2:3 :
    - **couverture de l'API** (`coverUrl`) une fois le livre `MATCHED` ou `CONFIRMED` ;
    - sinon la photo perso.
    - Si la couverture de l'API ne charge pas (hors ligne, URL morte), on affiche la photo perso.
  - titre sur 2 lignes max, auteurs sur 1 ligne ;
  - **chip de statut avec icône + texte** (pas la couleur seule) ;
  - badge « doublon possible ».
  - Livres en cours : titre remplacé par « Lecture en cours… » en italique.
- **FAB étendu** « 📷 Scanner » en bas à droite, au-dessus de la barre de navigation. Il se réduit en FAB rond quand la liste défile.
- **État vide** (aucun livre) : icône, « Votre bibliothèque est vide », texte court, bouton « Scanner un livre ».
- Retours d'export/import : **Snackbar** (pas de Toast).

### 4.2 Capture
Mise en page caméra classique :
```
┌────────────────────────────┐  ← barre d'état (rien dessous)
│ ←                    ⚡︎?    │  ← zone haute dans les insets : retour (+ réservé)
│                            │
│      ┌──────────────┐      │
│      │  cadre 2:3   │      │  ← cadre-guide
│      │              │      │
│      └──────────────┘      │
│  « Cadrez la couverture »  │  ← indication, masquée après la 1re photo
│                            │
│ [miniature]   ( ◉ )  [Terminé (3)] │  ← barre de contrôles, fond translucide
└────────────────────────────┘  ← barre de navigation (rien dessous)
```
- **Haut gauche** : bouton retour (← vers la bibliothèque), dans la zone sûre.
- **Barre du bas**, sur fond noir translucide, au-dessus de la barre de navigation :
  - **à gauche**, la miniature de la dernière photo, avec une petite animation à son arrivée ; un toucher ouvre la fiche de ce livre ;
  - **au centre**, le déclencheur (72 dp minimum) ;
  - **à droite**, le bouton « Terminé » avec le nombre de photos prises dans la session, qui ramène à la bibliothèque.
- **Retour visuel** à chaque photo : flash blanc bref (environ 100 ms) sur l'aperçu, en plus de la vibration. Le cadre vert pendant l'écriture est conservé.
- **Permission refusée** : écran explicatif + bouton « Ouvrir les réglages » (si la permission est refusée définitivement), au lieu d'un bouton seul sur fond noir.
- Erreur de capture : Snackbar en bas, au-dessus de la barre de contrôles.
- Les touches de volume gardent leur comportement normal (elles ne déclenchent pas la photo).

### 4.3 Fiche livre
- **Barre d'app** : retour, titre du livre (ou « Livre sans titre »), menu ⋮ avec « Relancer la recherche » et « Supprimer ».
- **En-tête** : photo (2:3, toucher = plein écran avec zoom) à côté de la couverture de l'API si elle existe, puis la chip de statut.
- **Formulaire** inchangé (titre, auteurs, éditeur, année, ISBN).
- **Candidats** en cartes sélectionnables (bouton radio). Celui qui est retenu est surligné. Le score s'affiche en clair : « correspondance forte / moyenne / faible » plutôt qu'un pourcentage brut.
- **Bouton principal fixé en bas**, au-dessus de la barre de navigation :
  - « Valider » en mode normal ;
  - « Valider et suivant » en **mode revue**.
- **Mode revue** :
  - ouvert depuis le bandeau « à vérifier » ;
  - après validation, il passe directement au livre suivant à vérifier ;
  - un compteur « 3 / 12 » s'affiche dans la barre d'app ;
  - une action « Passer » laisse le livre en l'état ;
  - à la fin : Snackbar « Revue terminée » et retour à la bibliothèque.
- **Relancer la recherche** : bottom sheet, avec le champ de requête prérempli et le bouton « Relancer ».
- **Texte OCR** : section repliable « Texte lu sur la couverture », fermée par défaut.
- **Supprimer** : dialogue de confirmation conservé.

## 5. Accessibilité
- Cibles tactiles ≥ 48 dp. Tous les boutons à icône ont une `contentDescription` en français.
- Statuts : icône + texte, contraste AA dans les deux thèmes.
- La taille de police système à 200 % ne doit pas casser les écrans (les textes passent à la ligne, pas de troncature des actions).

## 6. Critères d'acceptation

| Critère | Comment vérifier |
|---|---|
| Aucun contrôle sous la barre d'état ou de navigation, sur les 3 écrans | Pixel 6, navigation par gestes puis 3 boutons |
| Lancement à froid → bibliothèque affichée | splash sans attente artificielle, pas d'état vide qui clignote |
| Bibliothèque → caméra en 1 toucher (FAB) ; caméra → bibliothèque en 1 toucher (Terminé ou retour) | manuel |
| Revue de 10 livres « à vérifier » sans repasser par la liste | manuel |
| Mode sombre et couleurs dynamiques corrects | changer le fond d'écran et le thème système |
| Police système à 200 % : écrans utilisables | réglage Affichage |
| Aucune régression du traitement | les 8 tests unitaires restent verts, et une session de 5 photos est traitée |

## 7. Découpage proposé (une PR chacune)
1. Fondations : edge-to-edge, `BiblioTheme`, icône adaptative, splash, Navigation Compose avec la bibliothèque comme écran de départ.
2. Capture : barre de contrôles, miniature, flash, écran de permission.
3. Bibliothèque : FAB, recherche, bandeaux, état vide, chips de statut, Snackbar.
4. Fiche + mode revue.

## 8. Décisions
- ✅ Miniature de la liste : couverture de l'API une fois le livre trouvé ou validé, photo perso sinon (§4.1).
- ✅ Tri : date d'ajout par défaut, plus titre et auteur (§4.1).
- ✅ Touche volume comme déclencheur : non.
