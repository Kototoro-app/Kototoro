# Retour arrière

- Ne pas fusionner cette branche dans `devel` pendant la validation.
- Base immuable de référence : `v2.4.1` / `f7b68e1b3816e44896d11742881eabfa752911b1`.
- Pour consulter le code initial sans perdre le travail : `git worktree add ../Kototoro-baseline v2.4.1`.
- Pour annuler un lot publié, utiliser `git revert <commit>` sur la branche de développement ; ne pas forcer la branche stable.
- L’APK debug conserve le suffixe `.debug`, donc les données de la version stable ne sont pas touchées.
- Avant de tester une mise à niveau avec le même identifiant et la même signature, exporter une sauvegarde depuis les paramètres et copier les fichiers locaux. Une APK signée différemment ne peut pas remplacer l’installation existante.
- Les données vidéo historiques doivent rester dans le stockage et les archives ; leur retrait de l’interface ne doit lancer ni nettoyage ni suppression.
- Ne pas désinstaller l’application pour corriger un échec de migration. Conserver le journal, sauvegarder, puis réinstaller une version compatible avec le même schéma et la même signature.
- Un downgrade Android peut être refusé ; restaurer la sauvegarde dans une installation compatible, sans effacer l’unique copie des données.
