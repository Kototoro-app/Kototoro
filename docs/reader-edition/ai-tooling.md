# OmniRoute et Caveman

## Intégration au dépôt

- OmniRoute officiel : https://github.com/cheaper-inference/OmniRoute, paquet npm `omniroute@3.8.53`.
- Dépendances isolées dans `tools/ai/`. Elles ne sont pas des dépendances Gradle et ne sont pas embarquées dans l’APK.
- Profil Codex facultatif `omniroute` dans `.codex/config.toml`. Le fournisseur par défaut n’est pas changé.
- Skill de rédaction Caveman : `.agents/skills/caveman/SKILL.md`, copie du commit upstream indiqué dans `UPSTREAM.md`, licence Apache-2.0 incluse.
- Le skill conserve le code, les commandes, les erreurs et les résultats de tests. Il ne compresse pas les données utilisateur ni les chapitres.

## Démarrage local

Node.js 24 est recommandé. Depuis la racine du dépôt :

```sh
npm ci --prefix tools/ai
npm run --prefix tools/ai check
npm run --prefix tools/ai start
```

Ouvrir http://localhost:20128. Ajouter ses propres connexions fournisseur dans le tableau de bord,
puis créer une clé de point d’entrée. Fournir cette clé dans la variable d’environnement
`OMNIROUTE_API_KEY` du terminal local ou du gestionnaire de secrets, jamais dans Git ni dans le chat.

Depuis un terminal où cette variable est disponible :

```sh
codex --profile omniroute
```

Le profil vise `http://localhost:20128/v1` et utilise le protocole Responses. Le modèle `auto`
nécessite au moins une connexion fournisseur éligible. Pour un serveur distant, utiliser HTTPS
et adapter le profil localement. Ne pas exposer le tableau de bord sur Internet par défaut.

Dans un hôte Codex qui découvre les skills du dépôt, invoquer `$caveman`.
Aucun hook global, télémétrie Caveman Cloud ou proxy Caveman supplémentaire n’est installé.

## Vérification et limites

La version npm, les exigences Node et l’intégrité publiées ont été consultées.
Le modèle et les connexions fournisseur de cette conversation ne sont pas reconfigurables par
un profil de dépôt : aucun routage de cette conversation via OmniRoute n’est prétendu.
Aucune clé ni connexion fournisseur OmniRoute n’a été fournie. Un test d’inférence et une mesure
de coût nécessitent cette configuration ; aucun gain de tokens ou de coût n’est annoncé.
Le paquet OmniRoute publié représente environ 915 Mo avant dépendances. L’installation complète
est séparée de la construction Android pour éviter de consommer le disque des runners Android.

## Audit des dépendances de l’outil

L’audit du verrou npm initial a signalé neuf vulnérabilités transitives : six faibles,
deux modérées et une critique dans DOMPurify. La version corrective `3.4.17` existe ;
un override exact est appliqué pour empêcher le verrou de conserver les versions vulnérables.
Le workflow manuel vérifie aussi `npm audit --audit-level=high`. Le résultat du nouvel audit
et le test du CLI doivent être consultés avant d’annoncer une validation de l’outil.

Nouvel audit après correction : **0 critique, 0 élevée, 0 modérée, 7 faibles**.
Mesure : `npm audit --package-lock-only --json --prefix tools/ai`, 10 novembre 2026.
Ces sept avis faibles restent à suivre. Le résultat décrit le verrou npm, pas un test
d’inférence ni une validation exhaustive du tableau de bord OmniRoute.
