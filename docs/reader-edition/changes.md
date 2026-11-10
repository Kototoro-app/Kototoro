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

## Automatisations et dernière vérification du périmètre

Le scanner local et les notes ne présentent plus les œuvres vidéo. Les anciens fichiers et notes
restent stockés ; les exports des archives restent indépendants de cette projection UI.
Les actualisations périodiques de chapitres et la synchronisation Google Drive attendent une batterie
suffisante et utilisent un backoff exponentiel. Le lancement manuel garde sa politique interactive.
La planification périodique est actualisée au démarrage par WorkManager UPDATE, sans recréer de queue.
Aucun gain énergétique chiffré n’est revendiqué.

Le premier pipeline Reader Edition (42810153051) a détecté un défaut de préparation SDK :
`android-actions/setup-android@v3` tentait d’installer l’ancien paquet `tools`. Le workflow précise
maintenant `packages: platform-tools` et installe explicitement les entrées nécessaires au projet.

## Raccordements et validation continue

Le pipeline 42810609820 a compilé les modules partagés et détecté deux raccordements
obsolètes dans AISettingsRoute. Le callback vidéo a été retiré du composant intermédiaire.
Les liens d’ajout de dépôts audiovisuels, les schémas Android Aniyomi et l’installation de
paquets Aniyomi/Cloudstream sont également refusés dans Reader Edition.
La résolution d’un livre par son identifiant dans AppRouter utilise désormais une coroutine
liée à l’activité et Dispatchers.IO pour la base de données ; le runBlocking du chemin de
navigation a été retiré. Aucune amélioration de latence chiffrée n’est revendiquée.

Git HTTPS et plusieurs téléchargements Maven ont ensuite répondu HTTP 503 dans l’environnement
cloud. La publication a continué par les API de l’intégration GitHub, en vérifiant l’égalité
du SHA de l’arbre source et en conservant les anciens commits locaux sous
`refs/reader-edition/local-before-api`. La branche stable reste intacte.

## Découverte des services de suivi

La vérification des points d’entrée a trouvé une seconde cause d’exposition de catalogues
anime : DiscoverViewModel retournait toutes les catégories dans l’onglet Tous et réintroduisait
les catégories originales quand un filtre ne trouvait rien. La politique ReaderDiscoveryPolicy
filtre maintenant les catégories avant ces deux chemins. Les liens directs sont contrôlés avant
l’affichage du cache. Les recherches de métadonnées ne demandent plus de résultats anime, et les
catalogues sans type explicite utilisent le format manga.

Dix-huit tests ciblés ont été compilés avec le compilateur Kotlin 2.4.0 puis exécutés sous
JUnit Platform 1.8.2 sur les sources réelles : 18 réussis, aucun ignoré. Ce contrôle isolé
n’est pas une validation Gradle/Android ni un parcours UX. Le pipeline complet reste la référence.

Le parcours de premier lancement propose uniquement manga et roman. Ses listes d’écosystèmes
installables retirent Aniyomi, TVBox et Cloudstream. Le filtre d’installation refuse aussi
ces écosystèmes si une ancienne sélection vidéo est restaurée. Les tests des filtres de
langue et de compatibilité manga/roman sont conservés.

Le job instrumenté 129819529308 du pipeline 42813145069 a échoué avant l’exécution des tests :
le compilateur Kotlin a atteint le plafond de 2Go introduit par le workflow. Les commandes CI
reprennent désormais les 6Go déjà prévus par gradle.properties. Le rapport ne présente pas cet
échec de compilation comme un résultat de test appareil.

## Dernière session et stockage

Les raccourcis de reprise ignorent désormais les espaces retirés avant toute activation. Les
fixtures de tests utilisent des romans pour vérifier la lecture hors ligne et les espaces actifs.
Le dernier contrôle CI a compilé l’application, puis trouvé des références de tests aux anciens
fournisseurs vidéo et à TorrentUtils. Ces références sont retirées avec leurs composants exclusifs.

Les quotas de cache ne comprennent plus de réglages vidéo, torrent ou Danmaku. Les presets
modifient uniquement les caches de lecture. Le diagnostic conserve le comptage des anciens fichiers
sous « Autres données » et celui des anciens caches sous « Autres caches » ; il ne supprime rien.
Les callbacks et observations de qualité vidéo devenus inutiles sont retirés des réglages de téléchargement.

## Sélecteur de format et historique de reprise

Le contrôle à trois positions vidéo/manga/roman est remplacé par un menu Material à choix
unique : Tous, Manga, Roman. Son implémentation de menu réside dans core-ui et est consommée
par les barres Android et desktop. L’ancre Android conserve les liens de focus TV et une cible
de 48dp ; le menu expose le choix sélectionné dans sa sémantique. Les anciens filtres vidéo
restaurés affichent Tous. Un test appareil ouvre le menu à 200% et choisit Roman.

La reprise globale utilisait directement les dernières lignes Room, en contournant le filtre
d’affichage des listes. HistoryRepository applique maintenant la politique sur le type persisté
et le fournisseur, puis élargit la fenêtre jusqu’au prochain livre. Trois tests couvrent les
vidéos récentes, un lot entier retiré et les métadonnées manga erronées d’un fournisseur vidéo.
Aucune ligne historique n’est effacée. Les commandes « lecture vidéo » de la reprise sont retirées.

Le pipeline 42815793737 a validé la compilation principale mais a échoué sur les références
de tests retirées et sur une dépendance UI Automator manquante pour le nouveau test d’interface.
La dépendance existante du catalogue est raccordée à androidTest ; les tests n’avaient pas
encore été exécutés sur émulateur. Le cache Gradle est désormais inscriptible sur cette branche
afin de réutiliser les dépendances et les résultats des compilations successives.
