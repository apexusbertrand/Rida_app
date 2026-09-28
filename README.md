# Rida pour les pros

Application Android autonome de tenue d'un RIDA (Relevé d'Information, de Décision et d'Action) multi-client,
avec agent IA local (à partir du lot 2). Aucune donnée ne quitte le téléphone, sauf les requêtes envoyées au
fournisseur d'IA choisi par l'utilisateur.

- Spécification : [docs/PROMPT.md](docs/PROMPT.md)
- Architecture : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Modules

| Module | Rôle |
|---|---|
| `:domain` | Règles métier RIDA en Kotlin pur (validation, ID, dates, couleurs, tri, digest, synthèse, recopie, alias) |
| `:xlsx` | Lecture / écriture xlsx sans dépendance |
| `:data` | Room, paramètres, repositories, import/export du classeur |
| `:app` | Interface Jetpack Compose |

## Compilation

La CI GitHub Actions (`.github/workflows/android.yml`) lance les tests et produit l'APK de debug à chaque push
sur `main`. L'APK est disponible dans les artefacts du run, et sur la branche `ci-status`.

En local : `./gradlew :domain:test :xlsx:test :data:testDebugUnitTest :app:assembleDebug` (JDK 17, SDK Android 36).
