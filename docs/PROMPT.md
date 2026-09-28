# PROMPT — Application Android « RIDA » (agent local, autonome, publiable sur le Play Store)

> À coller tel quel dans Claude Code (ou tout agent de développement) à la racine d'un dépôt vide.
> Référence fonctionnelle : le workflow n8n « RIDA - Agent multi-canal » en production (Gmail + Google Sheets + Telegram + digest 7h45), à reproduire à l'identique fonctionnellement, sans aucun serveur. La lecture de la boîte Gmail est remplacée par l'envoi de captures d'écran dans le chat (§4) et Telegram par le chat intégré (§3).

---

## 1. Rôle et posture

Tu es un ingénieur Android senior (Kotlin, Jetpack Compose) doublé d'un architecte produit. Tu construis une application **autonome** — aucun backend, aucun n8n, aucun Google Sheets — destinée à terme au **Google Play Store**. Tu travailles **par lots successifs** (section 10), chacun livrable, testé et compilable. Avant chaque lot, tu vérifies toi-même l'état actuel des bibliothèques et API citées (versions, dépréciations, politiques Play) plutôt que de te fier à ta mémoire : plusieurs d'entre elles évoluent vite.

Règles de conduite :
- Jamais d'échec silencieux : toute erreur (réseau, LLM, lecture d'image, parsing) est remontée dans le chat de l'app avec un message clair.
- Jamais d'hypothèse présentée comme un fait : si une API ou une politique Play n'est pas confirmée, tu le dis et tu proposes une alternative.
- Tu signales proactivement toute anomalie ou tout conflit entre ce prompt et une contrainte technique/légale.
- Aucune clé, identifiant, adresse email ou ID codé en dur : tout est paramétrable dans l'app (notamment la **clé API Claude**, §5.1, et **l'adresse destinataire des extraits RIDA**, §7).

---

## 2. Ce qu'est le RIDA (règles métier à reproduire à l'identique)

Le RIDA (Relevé d'Information, de Décision et d'Action) est un registre multi-client tenu par un agent qui se comporte en **PMO expérimenté** : rigueur sur les échéances, synthèse claire, recul critique sur les risques.

### 2.1 Modèle de données d'une ligne (colonnes historiques A→L)

| Champ | Règle |
|---|---|
| **ID** | Entier unique **global** (tous clients confondus), attribué à la création, jamais modifié, jamais réutilisé, même après suppression. |
| **Date** | Date de **création** de la ligne (date du jour du signalement, pas de l'événement). Figée à vie. |
| **Interlocuteur** | Facultatif, défaut = nom de l'utilisateur (paramétrable). |
| **Type** | `INFORMATION` / `DECISION` / `ACTION`. |
| **Sujet** | 120 caractères max — résumer, ne jamais tronquer au milieu d'un mot. |
| **Action** | 120 caractères max, même règle. |
| **Statut** | `A faire` / `En cours` / `Terminé`. |
| **Échéance** | **Obligatoire pour une ACTION** ; à défaut = Date + 15 jours. Facultative pour INFORMATION/DECISION. |
| **Réalisation** | Date de clôture, renseignée quand Statut passe à `Terminé` (date du jour ou date précisée). |
| **Commentaire** | 500 car. max. **Colonne réservée à l'utilisateur** (saisie manuelle dans l'app). L'agent n'y écrit **jamais**, sauf exception « client non identifié » (§2.3). Lors d'une mise à jour, l'agent conserve la valeur existante à l'identique. |
| **Historique** | Journal empilé, jamais écrasé : `JJ/MM/AAAA : <ce qui a été fait/changé>`. Première entrée à la création : `JJ/MM/AAAA : création — <résumé>`. |
| **Recul** | Analyse PMO courte de l'agent (2–3 phrases max : risques, dépendances, cohérence avec le contexte client). Peut rester vide. |

En base, modélise proprement (tables `client`, `rida_line`, `history_entry`, `client_alias`, `app_settings`, `id_counter`…) mais l'export et l'affichage « tableur » doivent restituer exactement ces 12 colonnes dans cet ordre.

### 2.2 Référentiel clients
- Liste officielle et exhaustive des clients, gérée par l'utilisateur dans l'app (équivalent de l'onglet `REFERENTIEL`).
- Un « onglet » = un client. L'agent **ne peut jamais créer un client** absent du référentiel.
- Dictionnaire d'**alias / normalisation** paramétrable par client (ex. dans le RIDA actuel : « Absisses », « Abscisse », « Apsys Cyborg », « Absys Cyborg » → toujours corrigés en « Absys-Cyborg » sans demander confirmation, y compris dans ce que l'agent écrit). Ce mécanisme doit être générique pour la version Store.

### 2.3 Client non identifiable
Si le client ne correspond à aucune entrée du référentiel (ni via alias, ni déductible du sujet/interlocuteurs/conversation) : **ne jamais rejeter, ne jamais créer de client**. La ligne va dans le client spécial système **« NonIdentifié »**, et l'agent écrit dans Commentaire le nom tel que compris (ex. « Client mentionné : Dupond — à rapprocher de DUPONT ? »). C'est la seule exception à la règle Commentaire. L'UI doit permettre de **reclasser** facilement une ligne NonIdentifié vers un vrai client (l'ID ne change pas).

### 2.4 Nouvelle ligne vs mise à jour
- L'agent cherche d'abord dans le client concerné une ligne existante sur le même sujet (même sujet ou même point sous un autre angle). En cas de doute raisonnable, il **demande** à l'utilisateur dans le chat plutôt que de dupliquer ou d'écraser.
- Mise à jour : Date et ID inchangés, Commentaire recopié à l'identique, **ajout** d'une entrée datée dans Historique, actualisation de Recul si l'analyse change. Si l'utilisateur donne une précision qui ressemble à un commentaire, elle va dans Historique et/ou Recul, pas dans Commentaire.
- Un message (ou une capture d'écran) peut contenir plusieurs points distincts (plusieurs clients/sujets) → autant de lignes.

### 2.5 Fusion et suppression (traçabilité)
Aucune suppression physique, aucun ID perdu. Sur demande de fusion ou suppression :
- La ligne qui disparaît garde Date/Interlocuteur/Type et toutes ses autres données ; son Sujet est remplacé par « Fusionnée dans #42 » ou « Supprimée à la demande : <raison> » ; elle passe à l'état `masquée` (soft-delete, remplace l'actuel « texte blanc sur blanc »).
- En fusion, la ligne survivante est mise à jour normalement.
- Un filtre « afficher les lignes masquées » permet de les retrouver.

### 2.6 Règle d'or sur les ID
Dès que l'agent mentionne une ligne (confirmation, liste, synthèse, réponse), il cite **toujours** son ID (`#12`). Si l'utilisateur donne un ID sans client, l'app retrouve la ligne directement (ID global → pas besoin de demander le client, amélioration par rapport à n8n).

### 2.7 Date du jour
La date du jour (fuseau de l'appareil, par défaut Europe/Paris) est **injectée par le code** en tête de chaque requête à l'agent. L'agent ne doit jamais la déduire ou l'estimer (bug connu en production : dates J+7 erronées). Idéalement, les dates de création, J+15 par défaut et Réalisation sont calculées par le code, pas par le LLM.

### 2.8 Déclencheurs valides
Le RIDA ne se met à jour **que sur demande explicite** : message dans le chat de l'app (texte, dictée ou capture d'écran, §4), élément partagé vers l'app, ou demande de synthèse. Jamais de mise à jour implicite.

---

## 3. Chat intégré (remplace Telegram et l'entrée « Claude direct »)

- Écran de conversation style messagerie, historique persistant en local.
- Saisie texte + **dictée vocale** (SpeechRecognizer Android ; les entrées actuelles sont souvent dictées → prévoir des fautes et des noms approximatifs).
- Mémoire conversationnelle glissante (10 derniers échanges, paramétrable), pour que « clôture-la » ou « et pour DERET ? » fonctionnent.
- Chaque réponse de l'agent a deux parties, toujours affichées ensemble :
  - `resume` : une phrase autonome, avec ID(s) si une ligne est concernée (« Clôturé l'action #17 chez DERET », « Ajouté 2 lignes (#39, #40) chez CIM »).
  - `detail` : pour toute question de liste/analyse/recherche, la réponse **complète** (liste à puces avec au minimum ID, client, sujet et l'élément demandé). Jamais de phrase méta du type « liste fournie » sans les données.
- Les **messages automatiques** (digest, recopie commentaires, erreurs) arrivent aussi dans ce fil, marqués par leur origine (⏰, ⚠️), avec notification Android.
- Commandes reconnues par le code avant l'agent (sans LLM) : `recopie commentaire(s)` (regex tolérante équivalente à `/recopie[^.]{0,20}commentaire/i`), `synthèse`, `digest`.
- Cartes cliquables : chaque ID cité dans le chat ouvre la fiche de la ligne.

---

## 4. Entrée par capture d'écran (remplace la lecture de la boîte Gmail)

**Aucune intégration Gmail dans cette version** (ni lecture, ni envoi via API, ni OAuth Google) : fonctionnalité suspendue, trop complexe à ce stade. Ne pas l'implémenter, ne pas demander de scope Google.

À la place, le chat accepte une **image** comme entrée : typiquement une capture d'écran d'un email, d'un message, d'un compte rendu ou d'un tableau. Elle joue le rôle qu'avait l'email « RIDA » dans n8n.

Parcours :
- Bouton pièce jointe dans le chat : galerie / sélecteur de photos Android (Photo Picker, sans permission de stockage), appareil photo, et **plusieurs images dans un même envoi** (email long capturé en plusieurs écrans).
- **Intent de partage** : « Partager » une capture ou un texte depuis n'importe quelle app (Gmail, Outlook, WhatsApp, galerie…) vers RIDA → ouvre le chat avec l'élément joint.
- Texte optionnel accompagnant l'image (ex. « c'est pour DERET, échéance fin du mois »).
- L'agent lit l'image (LLM multimodal), en extrait client / type / sujet / action / échéance / interlocuteur, applique toutes les règles du §2 (dont multi-points → plusieurs lignes, NonIdentifié si client non reconnu), puis répond au format `resume` + `detail`.
- Si l'image est illisible, tronquée ou ambiguë : l'agent le dit et pose une question, il n'invente rien.
- Pour limiter coûts et latence : redimensionner/compresser l'image avant envoi au LLM (taille cible à définir selon la doc du fournisseur), et proposer de recadrer.
- Fallback on-device : OCR local (ML Kit Text Recognition) pour extraire le texte si le fournisseur LLM choisi n'est pas multimodal ; dans ce cas seul le texte extrait est envoyé.
- Confidentialité : l'image est stockée localement avec le message (miniature dans l'historique du chat, rattachée aux lignes créées pour traçabilité), et purgeable dans les paramètres. L'onboarding précise qu'elle est transmise au fournisseur LLM pour analyse.

Architecture : garder une interface `InboundSource` (implémentations `ChatInput`, `ShareIntentSource`) pour pouvoir réintroduire plus tard une source email sans refonte.

---

## 5. Agent local

### 5.1 Principe
L'orchestration (boucle agent, outils, règles, base de données) tourne **entièrement dans l'app**. Le LLM est derrière une interface `LlmProvider` pour pouvoir changer de fournisseur :
- **Fournisseur par défaut** : API Anthropic (Claude, famille Sonnet — le RIDA actuel tourne sur Claude Sonnet, température 0.2), modèle **multimodal** obligatoire pour lire les captures (§4). Vérifie l'identifiant de modèle courant dans la doc Anthropic.
- **Clé API Claude paramétrable** dans l'écran Paramètres (jamais codée en dur, jamais dans le dépôt) : saisie masquée, stockage chiffré (Android Keystore), bouton « Tester la clé » (appel minimal avec message de succès/échec explicite), modification et suppression possibles. Le modèle utilisé est aussi paramétrable (liste avec valeur par défaut). Sans clé valide, l'app bascule en mode dégradé sans LLM avec un bandeau explicite.
- **Fournisseur on-device (lot ultérieur, expérimental)** : modèle embarqué (ex. Gemini Nano via les API GenAI d'Android sur appareils compatibles, ou un petit modèle type Gemma via le runtime LLM on-device de Google en vigueur). À évaluer honnêtement : fiabilité du tool-calling, respect des limites de caractères, qualité du « Recul ». Ne pas le proposer par défaut s'il ne tient pas les tests du §9.
- Mode dégradé **sans LLM** : l'app reste utilisable manuellement (création/édition de lignes, digest, couleurs, recopie commentaires, synthèse) — seule l'interprétation en langage naturel nécessite le LLM.

### 5.2 Outils exposés à l'agent (function calling)
Remplacent les outils HTTP n8n → Google Sheets. Le code garantit les invariants, pas le LLM :
- `lister_clients()` → référentiel + alias + nombre de lignes par client.
- `lire_client(client, filtres?)` → lignes du client (hors masquées sauf demande).
- `lire_ligne(id)`.
- `rechercher(texte, client?, statut?, echeance_avant?)`.
- `creer_ligne(client, interlocuteur, type, sujet, action, statut, echeance?, historique_initial, recul?)` → **l'ID est attribué par la base dans une transaction** (le compteur n'est plus géré par l'agent : supprime le risque de doublon de l'actuel « Définir prochain ID »). Date = aujourd'hui, Commentaire = vide, imposés par le code. Échéance ACTION absente → J+15 calculé par le code.
- `mettre_a_jour_ligne(id, champs_modifiés, entree_historique, recul?)` → le code interdit toute modification de Date, ID et Commentaire, et **ajoute** l'entrée Historique datée.
- `masquer_ligne(id, motif, fusionnee_dans?)`.
- `reclasser_ligne(id, client)` (NonIdentifié → client).
- `generer_synthese(clients?, retard: bool, horizon_jours: int)` → données structurées.
- `envoyer_synthese(destinataire, sujet, corps)` → voir §7.

Validation côté code de chaque appel (longueurs, énumérations, dates JJ/MM/AAAA, client existant) avec message d'erreur exploitable renvoyé à l'agent.

### 5.3 Garde-fous (repris de la production)
- Max 10 itérations par requête.
- **Anti-boucle** : si un outil échoue 2 fois de suite avec la même erreur, arrêt et explication dans la réponse.
- Sortie finale structurée (JSON validé : `resume`, `client`, `detail`, + champs d'envoi de synthèse), avec réparation automatique si le JSON est invalide.
- En cas d'échec technique : message dans le chat « Je n'ai pas pu traiter ta demande (erreur technique). Demande reçue : … ».
- Le prompt système de l'agent reprend fidèlement les règles du §2 ; il est versionné dans le code (pas modifiable par l'utilisateur au lot 1).

---

## 6. Traitements planifiés (remplacent le job n8n 7h45)

Déclenchement **du lundi au vendredi à 7h45** (heure et jours paramétrables), via WorkManager. Précision : sans alarme exacte, l'exécution peut glisser de quelques minutes ; les permissions d'alarme exacte sont restreintes par la politique Play aux apps de type réveil/agenda — ne pas les demander sans me consulter.

Pour chaque client (hors système) :
1. **Recopie Commentaire → Historique** : chaque Commentaire non vide est ajouté à l'Historique sous la forme `JJ/MM/AAAA : <commentaire>` avec la date de **la veille (J-1)** (le job du matin documente la saisie de la veille), puis le Commentaire est vidé. Déclenchée à la demande par la commande chat « recopie commentaire » : même traitement mais avec la **date du jour (J)**. Message de confirmation dans le chat : nombre recopié par client, avec les IDs.
2. **Tri** : non terminées par échéance croissante (retards en premier, sans date en fin de groupe), puis terminées par échéance décroissante (sans date en fin de groupe). Dans l'app, c'est le tri par défaut de chaque vue client (plus besoin de réécrire les données).
3. **Code couleur** (colonnes Statut / Échéance / Réalisation), recalculé à l'affichage :
   - vert pastel `#D9EAD3` par défaut ;
   - orange `#FFA500` si échéance dans les 4 prochains jours (J à J+4) et statut `A faire` ;
   - vert vif `#34A853` si échéance dans les 4 prochains jours et statut `En cours` ;
   - rouge vif `#FF0000` si échéance dépassée et statut ≠ `Terminé` ;
   - rouge pastel `#F4CCCC` sur le **Statut seul** si `Terminé` sans date de Réalisation.
   Prévoir des variantes lisibles en thème sombre et un indicateur non chromatique (icône) pour l'accessibilité.
4. **Digest** : deux messages dans le chat + notification :
   - « 📅 Échéances RIDA du JJ/MM/AAAA » : lignes non terminées dont l'échéance est aujourd'hui, groupées par client, format `#ID Sujet` ;
   - « 🔜 Échéances RIDA du J+1 au J+2 » : idem pour les 2 jours suivants ;
   - « Aucune échéance. » si vide.
5. **Correction orthographique** (présente mais désactivée en production) : option désactivée par défaut, corrige uniquement fautes évidentes de Sujet/Action/Commentaire, sans reformuler, en cas de doute sur un nom propre ne rien changer, et rejet du résultat si la structure ne correspond pas exactement.

---

## 7. Synthèse multi-client

Sur demande (chat ou bouton) : parcourir tous les clients (NonIdentifié inclus s'il a des lignes, jamais les clients système vides), filtrer les lignes non terminées **en retard** (échéance < aujourd'hui, avec nombre de jours de retard) et/ou **à échéance ≤ 7 jours**, filtre client optionnel. Regroupement par client puis « 🔴 En retard » / « 🟠 Échéance ≤ 7 jours », colonnes : ID, Client, Sujet, Action, Type, Interlocuteur, Échéance, Retard (j), Statut.

Diffusion :
- toujours affichée en clair dans le chat ;
- **fichier Excel (.xlsx) généré localement** (ID en première colonne) ;
- envoi par email via **l'intent Android** (`ACTION_SEND` avec pièce jointe) : ouvre le client mail de l'utilisateur pré-rempli (destinataire, objet, corps HTML/texte, xlsx joint). Aucune API Gmail, aucun scope. Limite à assumer : l'utilisateur doit appuyer sur « Envoyer » dans son client mail ; pas d'envoi 100 % automatique (à mentionner dans l'UI).
- **Destinataire paramétrable** dans l'écran Paramètres : adresse par défaut (validation du format), éventuellement plusieurs adresses et une copie ; objet par défaut paramétrable (ex. « Extrait RIDA du JJ/MM/AAAA »). Si l'utilisateur précise une autre adresse dans sa demande au chat, elle prime pour cet envoi ; si aucune adresse n'est paramétrée ni précisée, l'agent la demande.
- Choisis une bibliothèque xlsx adaptée à Android (Apache POI est lourd : vérifie les alternatives et l'impact sur la taille de l'APK).

---

## 8. Stockage, confidentialité, publication

- **100 % local** : Room (SQLite) ; aucune donnée ne quitte l'appareil sauf les requêtes au fournisseur LLM choisi (texte et captures d'écran) et les synthèses que l'utilisateur envoie lui-même depuis son client mail. Le dire clairement dans l'onboarding.
- Chiffrement : secrets (clé API Claude) dans Android Keystore ; envisager le chiffrement de la base (SQLCipher) — à discuter.
- **Import / export** : export complet en `.xlsx` au format du classeur actuel (un onglet par client + onglet REFERENTIEL, 12 colonnes dans l'ordre historique) et en JSON ; **import d'un export du Google Sheets `RIDA_Apexus` existant** pour migrer sans ressaisie (en conservant les ID, dates, historiques, et le compteur d'ID = max + 1). Sauvegarde via Storage Access Framework ; Auto Backup Android à évaluer (exclure les secrets).
- **Préparer l'évolution client-serveur** (hors périmètre actuel) : couche `Repository` découplée de Room, UUID technique en plus de l'ID métier, horodatage `updatedAt` et marqueur de suppression sur toutes les entités, pour une synchro future (voir aussi l'ambition multi-utilisateurs/SaaS du RIDA).
- **Exigences Play Store** : targetSdk à jour selon la règle Google en vigueur, politique de confidentialité, formulaire Data safety, justification des permissions (notifications, microphone pour la dictée, internet), pas de permission superflue, i18n (français d'abord, chaînes externalisées pour l'anglais), nom et identifiant d'application paramétrables (aucune référence client ou personnelle dans le code).
- Aucun nom de client réel dans le code, les tests ou les captures : données de démonstration fictives.

---

## 9. Qualité et critères d'acceptation

Stack attendue : Kotlin, Jetpack Compose + Material 3, architecture MVVM/Clean, Hilt, Room, WorkManager, DataStore, coroutines/Flow, Ktor ou OkHttp, kotlinx.serialization. Tests unitaires sur toutes les règles métier, tests d'instrumentation Room, tests UI Compose sur les parcours clés.

Tests métier obligatoires (sans LLM, sur la couche outils/domaine) :
- ID unique global, jamais réutilisé, y compris après masquage et en création concurrente (ex. deux requêtes chat/partage traitées en parallèle).
- Date de création et ID immuables sur mise à jour ; Commentaire préservé à l'identique.
- Échéance ACTION par défaut = J+15 ; Réalisation remplie au passage à Terminé.
- Limites 120/120/500 respectées sans troncature au milieu d'un mot.
- Recopie Commentaire→Historique : date J-1 en planifié, J à la demande ; Historique jamais écrasé.
- Les 5 règles de couleur et le tri, y compris cas limites (échéance = aujourd'hui, J+4, sans échéance).
- Digest J et J+1..J+2 ; synthèse retard / ≤7 jours avec calcul des jours de retard.
- Client inconnu → NonIdentifié + commentaire ; alias normalisés ; création de client refusée hors référentiel.
- Import de l'export Sheets existant → export → ré-import sans perte.

Jeu d'évaluation de l'agent (avec LLM, exécutable à la demande) : ~30 demandes réelles anonymisées (dictée approximative, multi-points, fusion, clôture par ID, question de liste, captures d'écran d'emails longs, flous ou multi-écrans) avec résultat attendu ; taux de réussite affiché. Sert aussi à juger le fournisseur on-device.

---

## 10. Découpage en lots

1. **Socle** : projet, architecture, Room + domaine RIDA + règles du §2, référentiel clients/alias, UI liste par client (tri + couleurs), fiche ligne éditable (Commentaire manuel), soft-delete, import/export xlsx. Aucun LLM.
2. **Agent + chat** : écran Paramètres (clé API Claude + test, modèle, destinataire des extraits), `LlmProvider` Anthropic, outils §5.2, boucle agent + garde-fous, chat avec mémoire, dictée, commandes directes.
3. **Captures d'écran** : pièces jointes image dans le chat (galerie, appareil photo, multi-images), intent de partage entrant, lecture multimodale, compression, OCR ML Kit en secours.
4. **Planifié** : job 7h45 (recopie J-1, digest), commande recopie J, notifications.
5. **Synthèse** : génération xlsx, envoi au destinataire paramétré via l'intent mail Android.
6. **Store** : onboarding, paramètres, confidentialité, Data safety, accessibilité, thème sombre, i18n, build release signé, fiche Play.
7. **Exploratoire** : fournisseur LLM on-device, évalué sur le jeu du §9.

À la fin de chaque lot : ce qui est livré, ce qui a été testé et comment, écarts vs ce prompt, questions bloquantes pour le lot suivant.

---

## 11. Première action attendue

Ne code pas tout de suite. Réponds d'abord avec :
1. l'architecture proposée (modules, couches, schéma de base) ;
2. tes vérifications sur les points sensibles : lecture multimodale des captures (taille/format d'image, coût), options LLM on-device actuelles, bibliothèque xlsx, planification 7h45 sous les contraintes Play ;
3. la liste de tes questions bloquantes ;
puis démarre le lot 1 après ma validation.
