# RIDA Android — Architecture proposée (étape 1 du prompt, avant lot 1)

Version 0.1 — 28/09/2026 — à valider avant de démarrer le lot 1.

---

## 0. Constat bloquant n°1 : où compiler l'application

Vérifié le 28/09/2026 dans l'espace de travail cloud de Claude : **aucun accès aux dépôts nécessaires à un build Android** (dl.google.com, maven.google.com, Maven Central, services.gradle.org refusés par la politique réseau). Java 21 et Gradle sont présents, mais sans le SDK Android ni les dépendances, **impossible de compiler ou de tester ici**. Ton ordinateur n'était pas joignable au moment du test.

Options :

| Option | Principe | Avantages | Limites |
|---|---|---|---|
| **A. GitHub + GitHub Actions** (recommandée) | J'écris le code ici, je pousse sur un dépôt **privé** de ton compte `apexusbertrand` ; une CI GitHub compile, lance les tests et produit l'APK à télécharger sur ton téléphone. | Rien à installer chez toi ; historique Git ; APK téléchargeable à chaque lot ; la CI me renvoie les erreurs de compilation. | Tu dois créer le dépôt privé vide (ex. `rida-android`) ; boucle de correction plus lente qu'un build local. |
| B. Android Studio sur ton PC | Build via ton ordinateur lié à cette session. | Émulateur, débogage fin. | Installation d'Android Studio (~10 Go) ; PC allumé et relié pendant que je travaille. |
| C. Code seul (zip) | Je livre le projet, tu compiles. | Simple. | Aucune vérification de compilation de ma part : non recommandé. |

---

## 1. Vérifications sur les points sensibles

| Sujet | Constat vérifié (sept. 2026) | Décision proposée |
|---|---|---|
| **Play Store — target API** | Depuis le 31/08/2026, nouvelles apps et mises à jour : **targetSdk 36 (Android 16) minimum**. | `targetSdk = 36`, `compileSdk = 36`. |
| **Modèle Claude** | Modèles actuels (tous multimodaux, tool use) : Sonnet 5 `claude-sonnet-5` (2 $ / 10 $ par million de tokens entrée/sortie), Haiku 4.5 (1 $ / 5 $), Opus 5.5 (4 $ / 20 $). | Défaut **Sonnet 5**, modèle modifiable dans Paramètres. |
| **Captures d'écran** | Image ≤ 8000 px et ≤ 10 Mo ; redimensionnement conseillé à **1568 px** sur le grand côté ; coût = ⌈l/28⌉ × ⌈h/28⌉ tokens ; JPEG/PNG/WebP. | Redimensionnement à 1568 px ; les captures très longues (défilantes) sont **découpées en tranches** avec recouvrement pour rester lisibles. Une capture de téléphone coûte ~1 500 tokens, soit ~0,3 centime en Sonnet 5 (estimation). |
| **LLM embarqué** | ML Kit GenAI Prompt API (Gemini Nano) : **bêta**, sortie structurée en **alpha**, pas d'appel d'outils documenté, réservée à certains appareils haut de gamme. Gemini Nano 4 en preview. Gemma 4 via LiteRT-LM possible, mais modèle de plusieurs Go à télécharger. | Confirmé : pas de LLM embarqué en v1. Reste le lot 7, exploratoire. |
| **Fichiers xlsx** | Apache POI est lourd sur Android ; fastexcel (writer) ne documente pas Android ; son reader dépend de `javax.xml.stream`, **absent d'Android**. | **Module maison `:xlsx`** (zip + XML, avec le `XmlPullParser` natif d'Android), sans dépendance : écriture (synthèse, export multi-onglets) et lecture (import de l'export Google Sheets). Environ 400 lignes, testées sur JVM. |
| **Planification 7h45** | Alarmes exactes refusées par défaut depuis Android 14 ; `USE_EXACT_ALARM` réservé par Google Play aux apps réveil/agenda. | `AlarmManager.setWindow` (fenêtre **7h45–8h00**, sans permission spéciale) qui lance un job WorkManager. Réarmement au redémarrage du téléphone. **Rattrapage** à l'ouverture de l'app si le job du jour n'a pas tourné. |
| **Dictée vocale** | `RecognizerIntent` (dialogue système) ne requiert pas la permission micro. | Aucune permission `RECORD_AUDIO`. |
| **Photo / appareil photo** | Le Photo Picker ne demande aucune permission de stockage ; `TakePicture` par intent ne demande pas la permission `CAMERA`. | Permissions finales : `INTERNET`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`. |
| **Stockage de la clé API** | `EncryptedSharedPreferences` (security-crypto) est déprécié. | Clé AES-GCM dans **Android Keystore** + texte chiffré dans DataStore. Exclue des sauvegardes Android. |
| **Envoi du mail de synthèse** | Sans Gmail, l'envoi passe par l'intent `ACTION_SEND` ; le corps HTML n'est pas fiable selon les apps mail. | Corps en texte brut structuré + xlsx joint. L'agent **prépare** l'envoi et affiche un bouton « Ouvrir dans ma messagerie » dans le chat : c'est toi qui appuies sur Envoyer. |

---

## 2. Modules Gradle

```
:app        UI Compose (chat, clients, fiche ligne, paramètres, référentiel), navigation, Hilt, WorkManager, intents
:domain     Kotlin pur, sans Android — règles RIDA : validation, ID, dates J+15, couleurs, tri, digest,
            synthèse, recopie commentaires, normalisation alias. 100 % testé sur JVM.
:data       Room (base), DataStore (paramètres), Keystore (clé API), repositories, stockage des images
:agent      LlmProvider (interface) + AnthropicProvider (HTTP direct, kotlinx.serialization),
            boucle agent, outils RIDA, prompt système versionné, garde-fous
:xlsx       lecture/écriture xlsx sans dépendance
```

Choix délibéré : pas de SDK Anthropic Java (dépendances lourdes). Appel HTTP direct à l'API Messages avec OkHttp, cache de prompt activé sur le prompt système et les outils.

## 3. Flux d'une demande

```
Chat (texte / dictée / captures) ou Partage depuis une autre app
        │
        ▼
Commandes directes sans LLM ?  ── oui ──► recopie commentaire (date J) / synthèse / digest
        │ non
        ▼
File d'attente unique (une requête agent à la fois → pas de conflit d'ID)
        │
        ▼
Agent : date du jour injectée par le code + mémoire 10 échanges + images (1568 px)
        │   ⇅ outils (lire, rechercher, créer, mettre à jour, masquer, reclasser, synthèse)
        │      → chaque appel est validé par :domain ; erreur explicite renvoyée à l'agent
        ▼
Réponse finale via l'outil forcé `repondre(resume, detail, envoi?)` ; ID cliquables
```

Garde-fous repris de n8n : 10 itérations maximum ; arrêt après 2 échecs identiques d'un outil ; message d'erreur technique avec rappel de la demande. Dans la mémoire, les images des échanges passés sont remplacées par « [capture analysée] » (économie de tokens).

**Ce que le code impose, l'agent ne choisit plus :** ID (attribué dans une transaction), date de création, J+15 par défaut, date de Réalisation au passage à Terminé, Commentaire intouchable (sauf exception NonIdentifié), Historique ajouté et jamais réécrit, normalisation des alias. Les limites 120/120/500 sont **refusées avec une erreur** (l'agent reformule) et jamais tronquées.

## 4. Schéma de base (Room)

```
client          id(uuid) · code (nom d'onglet, unique, MAJUSCULES) · libellé · est_systeme (NonIdentifié)
                · ordre · created_at · updated_at · deleted
client_alias    id · client_id → client · alias_normalise (unique) · alias_brut
rida_line       uuid · id_metier (INTEGER UNIQUE = « #ID ») · client_id → client · date_creation
                · interlocuteur · type (INFORMATION|DECISION|ACTION) · sujet(120) · action(120)
                · statut (A faire|En cours|Terminé) · echeance? · realisation? · commentaire(500)
                · recul · masquee · motif_masquage? · fusionnee_dans? · updated_at
history_entry   id · line_uuid → rida_line · date · texte
                · origine (AGENT|UTILISATEUR|RECOPIE_AUTO|RECOPIE_DEMANDE|IMPORT) · created_at
id_sequence     nom · prochaine_valeur        (jamais décrémentée ; import : max(ID importés, compteur REFERENTIEL))
chat_message    id · role (USER|AGENT|SYSTEME) · origine (CHAT|PARTAGE|DIGEST|RECOPIE|ERREUR)
                · texte · resume · detail · envoi_prepare? · created_at · statut
attachment      id · message_id → chat_message · chemin_local · mime · largeur · hauteur · octets
line_attachment line_uuid · attachment_id     (traçabilité : la capture d'origine d'une ligne)
agent_run       id · message_id · modele · tokens_in · tokens_out · iterations · statut · erreur
job_run         type (DIGEST|RECOPIE) · date · statut          (rattrapage et idempotence)
```

La colonne « Historique » affichée ou exportée est reconstituée en concaténant `history_entry` (`JJ/MM/AAAA : texte`, une par ligne). L'export restitue les 12 colonnes A→L dans l'ordre historique.

Préparation de la synchro future : UUID technique, `updated_at` et suppression logique sur toutes les entités ; les repositories isolent Room.

## 5. Paramètres (écran dédié)

- **Clé API Claude** : saisie masquée, bouton « Tester la clé », suppression.
- **Modèle** : Sonnet 5 par défaut.
- **Destinataire des extraits RIDA** : adresse(s) par défaut, copie, objet par défaut.
- Nom par défaut de l'interlocuteur, fuseau horaire (Europe/Paris par défaut).
- Heure et jours du digest (7h45, du lundi au vendredi par défaut).
- Taille de la mémoire du chat, correction orthographique (désactivée par défaut).
- Purge des images, import/export.

## 6. Import depuis le RIDA actuel

Fichier source : dans Google Sheets, *Fichier > Télécharger > .xlsx* de `RIDA_Apexus`.
- `REFERENTIEL` : colonne CLIENT → clients ; C1 → compteur d'ID.
- Tous les autres onglets sauf `Modèle` → lignes du client (onglet `NonIdentifié` → client système).
- Dates au format texte `JJ/MM/AAAA` ou numéro de série Excel : les deux sont gérés.
- L'Historique est découpé en entrées sur le motif `JJ/MM/AAAA : `.
- Les lignes « blanchies » sont reconnues à leur Sujet (« Fusionnée dans #… » / « Supprimée… ») → masquées.
- Rapport d'import affiché : nombre de lignes par client, anomalies (ID en double, dates illisibles).

## 7. Décisions prises par défaut (modifiables)

1. **Lundi matin** : la recopie Commentaire → Historique date de J-1 (dimanche), comme n8n aujourd'hui.
2. **SQLCipher** : non en v1 (le chiffrement natif d'Android suffit pour démarrer) ; à reconsidérer avant la publication sur le Store.
3. **Interface** en français, textes externalisés pour une future version anglaise.
4. **Thème sombre** : couleurs du code RIDA adaptées, plus une icône par état (accessibilité).
5. **minSdk 26** (Android 8), sauf avis contraire.

## 8. Lot 1 — contenu confirmé

Projet Gradle multi-modules + CI · `:domain` complet avec tests (toutes les règles du §2 et du §9 du prompt) · base Room + repositories · référentiel clients et alias · liste par client (tri et couleurs) · fiche ligne éditable (Commentaire manuel, Historique en lecture) · masquage et reclassement · `:xlsx` avec import de l'export Sheets et export complet · APK installable. Pas de LLM dans ce lot.
