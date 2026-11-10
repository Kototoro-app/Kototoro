# Lots Reader Edition

## Phase 2 — périmètre de lecture et retrait Android vidéo

Actions techniques réalisées :

1. Politique de lecture portable dans core-domain.
2. Tests des familles manga/manhwa/manhua/roman.
3. Tests des noms de fournisseurs retirés même si leurs métadonnées sont trompeuses.
4. Tests des types restaurés lorsque l’extension est indisponible.
5. Suppression de l’espace anime intégré du catalogue actif.
6. Projection des espaces personnalisés sur les types de lecture sans modifier leurs lignes Room.
7. Refus de créer de nouveaux espaces vidéo.
8. Restauration de l’ancienne sélection anime vers manga.
9. Conservation des identifiants historiques et types sérialisés.
10. Retrait de l’onglet vidéo de la navigation partagée.
11. Restauration du filtre vidéo historique vers tous les formats de lecture.
12. Filtrage de l’onglet Tous, des favoris et de l’historique selon le périmètre de lecture.
13. Retrait des filtres de fournisseurs audiovisuels proposés.
14. Filtrage du catalogue de sources et des paquets proposés.
15. Retrait du démarrage Aniyomi et Cloudstream.
16. Retrait de leurs fournisseurs de la sélection des repositories.
17. Refus des fournisseurs audiovisuels dans la sélection centrale.
18. Retrait de VideoPlayerActivity et de ses routes Android.
19. Suppression des implémentations du lecteur vidéo, de ses contrôles, sous-titres et casting Android.
20. Suppression de la pipeline de téléchargement vidéo/HLS/torrent de DownloadWorker.
21. Suppression des dépendances de lecture vidéo UI/HLS/DASH, Nextlib, Danmaku et jlibtorrent de l’APK Android.
22. Préservation de Media3 ExoPlayer/OkHttp, nécessaires à la synthèse vocale des romans.
23. Déplacement des shaders d’amélioration des pages dans reader/translate/data.
24. Retrait des destinations de réglages vidéo et de leurs résultats de recherche.
25. Retrait des commandes d’import vidéo proposées.
26. Retrait des commandes de nettoyage vidéo/torrent/Danmaku de l’interface.
27. Retrait du choix de qualité de téléchargement vidéo.
28. Adaptation des tests du périmètre et ajout d’un cas de restauration d’espace retiré.
29. Suppression des tests exclusivement dédiés aux composants supprimés ; tests de lecture préservés.
30. Analyse statique des références résiduelles et `git diff --check`.

Aucune table ni colonne supprimée. Aucun fichier utilisateur supprimé. Les anciens horodatages de
notes vidéo restent exportables avec les archives. Les fichiers de stockage vidéo demeurent sur disque.

### Limites de ce lot

Les ABI/contrats historiques et certains adaptateurs de sources audiovisuelles restent encore dans
le dépôt. Le proxy TVBox a été isolé sous core/parser/tvbox ; il doit disparaître avec le découplage
complet des adaptateurs. Les types de données de compatibilité ne constituent pas une route de lecture.
Le retrait de tous ces adaptateurs et de leurs réglages internes doit être terminé avant de qualifier
l’application d’exclusivement lecture dans toutes ses entrées externes.

## Phase 3 — composants, outillage et validation

- Titres des états vides déclarés comme en-têtes d’accessibilité ; alignement centré et action de récupération.
- Actions d’en-têtes d’accueil et filtres partagés à 48dp minimum.
- Hauteur du hero d’accueil adaptée à la taille de police système.
- Tests de lecture restaurée et test appareil de l’action d’un état vide à 200% de taille de police.
- Wrapper Gradle vérifié par SHA-256 publié.
- ABI debug configurable explicitement pour l’émulateur x86_64, ARM64 conservé par défaut.
- Contrôle de dépendances du runtime Android contre les bibliothèques de lecture vidéo retirées.
- Workflow Reader Edition : tests JVM, tests Android JVM, Lint, contrôle du graphe, APK, signature,
  sommes de contrôle, rapports et exécution instrumentée manuelle sur émulateur.
- OmniRoute épinglé dans un sous-projet npm de développement ; aucun composant embarqué dans l’APK.
- Profil Codex facultatif sans secret ; skill Caveman avec provenance et licence.

Les interfaces utilisent toujours les données et composants réels de Kototoro. Ce lot ne constitue
pas une refonte visuelle complète de tous les écrans. Les validations réelles seront consignées dans
`tests.md` ; les parcours non exécutés doivent rester explicitement non validés.
