# NexNPC

NPCs sous forme d'entités Fabric, avec actions de clic droit et intégration Velocity.

## Compatibilité

- Minecraft Java **26.1.2**
- Fabric Loader **0.19.5 ou plus récent**
- Fabric API **0.155.3+26.1.2 ou plus récent**
- Java **25**
- Plugin compilé contre l'API Velocity **4.3.0**

Les versions Fabric suivent le modèle de projet officiel pour 26.1.2. L'API Velocity 4.3.0 est la dernière version stable publiée et Velocity prend en charge les protocoles Minecraft jusqu'à 26.3 selon sa documentation actuelle.

## Limitation du client vanilla

Un client vanilla ne connaît ni le type d'entité `nexnpc:npc`, ni le renderer qui dessine son modèle de joueur. Le protocole ne permet pas au serveur d'ajouter une nouvelle entrée au registre des entités ou d'associer un skin à une nouvelle entité vivante. Faire apparaître correctement ce NPC sur un client vanilla demanderait un faux joueur et des paquets de joueur, ce que ce projet n'utilise pas.

**Le mod Fabric doit donc être installé sur le serveur et sur chaque client.** Le client utilise une entité Fabric et un modèle joueur avec les couches de skin habituelles. La géométrie est le modèle standard à bras larges. Le skin du pseudo est chargé au premier affichage depuis les services de profils et de textures de Mojang ; si ces services ne répondent pas, une texture de secours est affichée.

## Compilation

À la racine du dépôt, avec Java 25 :

```powershell
.\gradlew.bat build
```

Les JAR livrables sont :

- `fabric/build/libs/NexNPC-Fabric-1.0.0.jar`
- `velocity/build/libs/NexNPC-Velocity-1.0.0.jar`

Le JAR Fabric contient le code commun et le renderer client. Le JAR Velocity est autonome et compile l'API en `compileOnly`.

## Installation

1. Déposez `NexNPC-Fabric-1.0.0.jar` dans `mods/` sur chaque backend Fabric qui héberge des NPCs.
2. Installez le même mod dans `mods/` des clients qui se connectent à ces serveurs.
3. Déposez `NexNPC-Velocity-1.0.0.jar` dans le dossier `plugins/` de Velocity.
4. Ajoutez les backends à la configuration Velocity comme d'habitude. Le mod demande la liste directement au proxy ; aucune liste de serveurs n'est à maintenir dans un fichier NexNPC.

Pour le forwarding moderne vers des backends Fabric, installez aussi [FabricProxy-Lite](https://modrinth.com/mod/fabricproxy-lite) sur chaque backend et configurez-le selon la documentation Velocity. NexNPC n'utilise pas le secret de forwarding pour son propre canal.

Les commandes Fabric restent disponibles sans plugin Velocity, sauf le transfert et les suggestions de serveurs.

## Commandes administrateur

Toutes les commandes `/npc` exigent le niveau de permission serveur 2 (opérateur).

| Commande | Effet |
|---|---|
| `/npc create <nom>` | Crée et sélectionne un NPC à la position de l'administrateur. |
| `/npc select <id>` | Sélectionne un NPC par l'UUID donné dans `/npc list`. |
| `/npc select` | Active ou désactive le mode de sélection par clic droit. Le premier NPC cliqué est sélectionné. |
| `/npc remove` | Supprime le NPC sélectionné. |
| `/npc list` | Liste les NPC chargés, leur UUID, dimension et position. |
| `/npc skin <pseudo>` | Définit le pseudo de skin du NPC sélectionné. |
| `/npc action command <commande>` | Remplace l'action par une commande, exécutée avec les permissions du joueur qui clique. |
| `/npc action server <serveur>` | Remplace l'action par un transfert vers le serveur Velocity. Tab demande la liste au proxy, avec un cache de 20 secondes. |
| `/npc action none` | Efface l'action. |
| `/npc look true` | Fait regarder le joueur le plus proche dans un rayon de 16 blocs. |
| `/npc look false` | Garde l'orientation du NPC. C'est la valeur par défaut. |

La sélection reste propre à la session de l'administrateur. Les NPC, eux, sont enregistrés dans les sauvegardes normales des entités et des chunks : position, nom, pseudo de skin, action et réglage de regard sont enregistrés dans leurs données NBT. Aucun fichier de NPC séparé n'est créé.

## Sécurité et Plugin Messaging

Le canal dédié est `nexnpc:proxy`. Velocity intercepte tous les messages de ce canal et les marque comme traités avant toute analyse. Seuls les messages dont la source est une connexion backend Velocity sont analysés ; les messages reçus directement d'un joueur sont abandonnés. Le joueur est obtenu depuis la connexion backend, jamais depuis un UUID transmis.

Pour une demande de transfert, Velocity vérifie le serveur backend actuellement associé au joueur, le format et l'existence exacte du serveur cible, puis crée la demande de connexion. Les requêtes serveur trop fréquentes sont limitées. Les demandes de liste renvoient uniquement les serveurs configurés dans Velocity. Leur réponse comporte un nonce aléatoire à usage unique afin qu'un client ne puisse pas fabriquer une liste en envoyant le même payload au serveur Fabric. Ce nonce est une donnée de corrélation éphémère, pas un secret applicatif partagé.

Velocity fait confiance aux backends qu'il connaît. Protégez donc les ports des serveurs backend afin que les joueurs ne puissent pas les contourner et ne faites pas fonctionner de backend non fiable dans le même proxy. `forwarding.secret` n'est ni lu ni réutilisé par NexNPC.

## Détails

- Le NPC n'a pas d'IA, de gravité ou de déplacement automatique. Il est invulnérable et non poussable.
- Chaque clic est limité à une action par NPC et par joueur pendant 8 ticks.
- La commande est exécutée comme le joueur qui clique, jamais comme la console.
- Les noms de serveurs sont validés une seconde fois par Velocity avant tout transfert.
- Le téléchargement du skin se fait depuis le client et est mis en cache en mémoire. Il nécessite une connexion aux services Mojang.
- Le code n'intègre aucun extrait de Citizens2.

## Sources de compatibilité

- [Guide Fabric 26.1.2 pour créer une entité](https://docs.fabricmc.net/26.1.2/develop/entities/first-entity)
- [Guide Fabric de mise à niveau vers 26.1](https://docs.fabricmc.net/26.1.2/develop/porting)
- [Messages de plugin Velocity et validation de provenance](https://docs.papermc.io/velocity/dev/plugin-messaging/)
- [Compatibilité des protocoles Velocity](https://docs.papermc.io/velocity/server-compatibility/)
