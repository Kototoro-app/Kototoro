# Kototoro Reader Edition — audit initial

Base publiée : `v2.4.1`, commit `f7b68e1b3816e44896d11742881eabfa752911b1`.
Branche de travail : `feature/reader-ui-overhaul`. La branche par défaut `devel` reste intacte.

## Phase 1 : inspections effectuées

1. Dépôt cloné et état de travail vérifié avant modification.
2. Branches distantes inventoriées : aucune branche explicitement nommée stable.
3. Tags comparés : v2.4.1 retenu comme référence publiée.
4. Licence racine Apache-2.0 vérifiée ; notices tierces conservées.
5. AGENTS.md, CLAUDE.md et guide CMP lus.
6. Modules Gradle inventoriés (voir inventory.json).
7. Architecture Android : Hilt, Room, Navigation3, Compose ; présentation partagée core-ui.
8. Contrats parser-api et ABI Kotatsu/Mihon repérés ; ne pas renuméroter leurs types.
9. Lecteurs manga et roman existants repérés ; aucune réécriture de leur stockage.
10. Consommateurs du package vidéo identifiés, y compris hors de video/.
11. Media3 : dépendance commune avec la synthèse vocale des romans.
12. Anime4KShaderAssets : dépendance des améliorations d’images manga ; doit être déplacée avant suppression vidéo.
13. Stockage des fichiers locaux, index de contenu, espaces et sessions inspectés.
14. WorkManager : planification des chapitres et de la synchronisation déjà présente.
15. Workflows inventoriés : debug manuel limité à une compilation ARM64, sans tests ni lint.
16. Tests existants inventoriés ; tests JVM, commonTest et instrumentés distincts.
17. Signature debug : suffixe `.debug`, installation séparée de la version stable.
18. Première commande de référence exécutée : échec réseau Java avant compilation.
19. Proxy Gradle configuré uniquement dans le répertoire utilisateur, hors dépôt.
20. Nouvelle tentative de référence : résolution Foojay échouée ; magasin CA système ajouté pour le JDK téléchargé par Gradle. Validation en cours.
21. GitHub Actions : liste des workflows vide ; dispatch debug répond 404, paramètres Actions 403. Activation du fork demandée à l’utilisateur.
22. Aucun secret ni fichier local.properties ajouté au dépôt.

## Fonctionnalités à préserver

Accueil alimenté par historique, nouveautés et recommandations ; bibliothèques paginées,
collections, sélection multiple ; recherche multi-source ; lecteurs manga paginés/continus,
RTL/LTR, roman et EPUB ; progression, notes, téléchargement de chapitres ; sauvegardes ZIP,
WebDAV/Google Drive et compatibilité des extensions de lecture.

## Causes et risques identifiés

| Domaine | Constat dans le code | Intervention nécessaire |
| --- | --- | --- |
| Périmètre | Espaces intégrés anime, filtres vidéo et sources audiovisuelles | Politique lecture commune et retrait des points d’entrée |
| Démarrage | Initialisation Aniyomi/Cloudstream dans BaseApp | Retirer le démarrage audiovisuel après découplage |
| Navigation | AppRouter démarre VideoPlayerActivity directement | Retirer routes et manifeste ensemble |
| Téléchargement | DownloadWorker contient la pipeline vidéo et les chapitres | Extraire/supprimer les branches vidéo sans changer la queue de lecture |
| Données | Types, noms de sources et espaces sont persistés | Projection lecture sans DELETE, sans migration destructive |
| Thème | MaterialExpressiveTheme et AMOLED déjà implémentés | Consolider l’existant, corriger les paramètres réellement ignorés |
| Lisibilité | Plusieurs styles décoratifs et glass/blur disponibles | Surfaces opaques et hiérarchie sobre par défaut |
| Accessibilité | De nombreux composants personnalisés | Contrôler sémantique, 48dp, grandes polices sur appareil |
| CI | Workflow debug compile uniquement | Tests, lint, audit dépendances, APK et rapports bloquants |
| Performances | Aucun avant/après mesuré dans cette session | Ne revendiquer aucun gain avant mesure reproductible |

Les constats UX sont issus du code. Aucun parcours visuel, TalkBack ou test appareil n’est
encore certifié. Une compilation ne constitue pas une validation UX.

## Références produit

- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/ui/compose/accessibility
- https://developer.apple.com/design/human-interface-guidelines/typography

Principes retenus : couleur sémantique/dynamique Android, hiérarchie typographique,
contenu prioritaire, gestes système préservés, actions contextuelles et surfaces sobres.
Aucune dépendance SwiftUI dans le projet Android.

## OmniRoute et Caveman

Aucun outil ou skill portant ces noms dans le catalogue connecté, les skills locaux ou
le dépôt initial. Les URL exactes ont été demandées. Aucune intégration ni installation
n’est prétendue ; la refonte Android peut avancer indépendamment.
