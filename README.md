# NexNPC

NexNPC crée des NPC à partir de l'entité native `minecraft:mannequin`. Le mod s'exécute côté serveur Fabric ; les transferts vers les autres serveurs passent par le plugin **NexNPC Velocity Integration**.

## Compatibilité

- Minecraft Java **26.1.2**
- Java **25**
- Fabric Loader **0.19.5** ou plus récent
- Fabric API **0.155.3+26.1.2** ou plus récent
- Fabric Loom **1.18.4**, Gradle Wrapper **9.7.0**
- API Velocity **4.3.0**

Minecraft 26.1.2 contient `EntityType.MANNEQUIN` et son profil natif. NexNPC n'enregistre aucune entité personnalisée : les NPC sont de vrais mannequins vanilla, utilisables par des clients Vanilla.

## Installation

1. Placez `NexNPC-Fabric-1.0.0.jar` dans `mods/` sur **chaque backend Fabric** qui héberge des NPC.
2. Placez `NexNPC-Velocity-1.0.0.jar` dans `plugins/` sur le proxy Velocity.
3. Redémarrez les serveurs et le proxy.

Les clients n'ont pas besoin d'installer NexNPC : mannequin, nom, profil de joueur et rotations utilisent le protocole et le rendu vanilla. Aucun faux joueur ni renderer client NexNPC n'est créé. La résolution du profil de skin peut afficher temporairement le skin par défaut et dépend de l'accès du client aux services de profils Minecraft.

Le plugin Velocity est facultatif pour les NPC qui n'utilisent pas d'action de transfert. Les suggestions de serveurs, les transferts et leurs messages de diagnostic nécessitent le plugin.

## Compilation

Avec Java 25, à la racine du dépôt :

```powershell
.\gradlew.bat clean build --console=plain
```

Les JAR livrables sont générés dans :

- `fabric/build/libs/NexNPC-Fabric-1.0.0.jar`
- `velocity/build/libs/NexNPC-Velocity-1.0.0.jar`

## Commandes

Les commandes de configuration nécessitent la permission opérateur Minecraft `COMMANDS_GAMEMASTER` (niveau 2). Les joueurs sans OP peuvent utiliser les NPC.

| Commande | Effet |
|---|---|
| `/npc create <id>` | Crée un mannequin immobile, nomme le NPC avec son ID et le sélectionne. |
| `/npc select <id>` | Sélectionne le NPC par son ID lisible. L'UUID technique reste interne. |
| `/npc select` | Active ou désactive la sélection par clic droit sur un NPC NexNPC. |
| `/npc remove` | Supprime le NPC sélectionné. |
| `/npc list` | Liste tous les IDs enregistrés, en signalant les NPC dans des chunks déchargés. |
| `/npc rename <nom>` | Modifie le nom affiché sans changer l'ID. Accepte `&0`–`&f`, `&k`–`&o`, `&r` et `&#RRGGBB`. |
| `/npc change id <nouvel_id>` | Modifie l'ID en conservant le nom, le skin et l'action. Les IDs sont uniques, sans distinction de casse. |
| `/npc skin <pseudo>` | Définit le profil de joueur du mannequin sélectionné. |
| `/npc action add command "<commande>"` | Définit une commande exécutée par la console quand un joueur clique. |
| `/npc action add server <serveur>` | Définit un transfert vers un serveur Velocity. La touche Tab demande la liste au proxy. |
| `/npc action none` | Supprime l'action actuelle. |
| `/npc look true` | Oriente le corps et la tête vers le joueur vivant le plus proche dans un rayon de 16 blocs. |
| `/npc look false` | Désactive le regard automatique. Désactivé par défaut. |

Les anciennes formes `/npc action command <commande>` et `/npc action server <serveur>` restent disponibles.

Exemples :

```mcfunction
/npc create home
/npc rename &9! &cHome &9!
/npc change id home1
/npc action add command "tp %player% 5 45 6"
/npc action add command "gamemode survival %player%"
```

`%player%` est remplacé par le pseudo du joueur qui a cliqué. Le pseudo Minecraft n'accepte ni espace ni séparateur de commande. Les commandes sont limitées à une ligne et à 256 caractères ; NexNPC ne donne jamais temporairement le statut OP au joueur.

## Persistance et skins

La position, le nom coloré, le profil, l'immobilité, l'action et le réglage du regard sont enregistrés avec les données natives du mannequin et les tags NBT de l'entité. Les changements de profil sont appliqués au champ natif `profile`, qui est synchronisé par Minecraft avec les joueurs suivant l'entité. Aucun fichier JSON distinct ne contient la configuration des NPC.

Minecraft ne publie pas de setters pour le profil ou le champ `immovable` de son mannequin. NexNPC met à jour ces champs via la lecture/écriture de données d'entité natives de Minecraft, ce qui garde le même type d'entité et le chemin de synchronisation vanilla.

Minecraft conserve aussi un petit `SavedData` nommé `nexnpc:npc_ids` qui réserve les IDs des NPC, y compris ceux dont le chunk est déchargé. La configuration propre à chaque NPC reste dans son entité. `/npc remove` libère la réservation.

Les mannequins NexNPC déjà sauvegardés restent des entités vanilla et sont reconnus au chargement de leur chunk. Les anciennes entités personnalisées `nexnpc:npc` des versions historiques ne sont plus prises en charge : garder leur type enregistré dans le registre des entités oblige Fabric à synchroniser cette entrée vers les clients, ce qui est incompatible avec l'objectif Vanilla sans mod. Sauvegardez le monde avant de remplacer une ancienne version et vérifiez les logs de migration de Minecraft au premier démarrage.

## Regard et interactions

Le callback Fabric consomme le clic droit sur un NPC NexNPC côté serveur et ignore le mannequin vanilla non géré par NexNPC. Les attaques sont annulées pour les NPC gérés. Une interaction est limitée à une action par joueur et par NPC toutes les 8 ticks. Le réglage du regard applique les rotations serveur du corps, de la tête et de la vue ; le suivi d'entité vanilla les transmet aux clients.

## Intégration Velocity

Le canal `nexnpc:proxy` utilise la version 2 du protocole NexNPC. Pour chaque message, le plugin Velocity marque le canal comme traité puis accepte les requêtes uniquement d'une connexion `ServerConnection` qui est encore le backend courant du joueur. Il déduit le joueur de cette connexion ; aucun UUID ou nom de joueur transmis par le mod n'est utilisé pour identifier la personne à transférer.

Pour un transfert, le proxy vérifie le format du nom, l'existence exacte du serveur dans sa propre configuration, le backend actuel et une limite de fréquence. Il envoie les erreurs de destination indisponible au backend. Les réponses aux requêtes de liste utilisent un nonce aléatoire à usage unique. Les messages envoyés directement par un client sont interceptés et rejetés. Le protocole ne lit ni ne réutilise `forwarding.secret`, qui reste réservé au forwarding Velocity.

Protégez les ports des backends comme recommandé pour votre configuration de forwarding : Velocity fait confiance aux connexions backend qu'il accepte.

## Vérification

La compilation Gradle des modules Fabric et Velocity est la vérification automatisée disponible dans ce dépôt. Le lancement de développement `:fabric:runServer` a chargé Minecraft 26.1.2, Fabric Loader 0.19.5 et NexNPC et a exécuté l'initialisation côté serveur ; le serveur s'est ensuite arrêté car aucun `eula.txt` accepté n'existait dans le répertoire de lancement. Les interactions en jeu, le regard visible par un client vanilla et un transfert entre vrais backends n'ont donc pas pu être validés ici.

NexNPC n'inclut aucun code de Citizens2.
