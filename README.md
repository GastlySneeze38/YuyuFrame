# YuyuFrame — Launcher Minecraft Premium

Launcher Minecraft premium avec synchronisation cloud, gestion avancée des instances, et système multijoueur P2P décentralisé.

## Plans d'abonnement

| Feature | Free | Premium 3.99€/mois | Ultimate 7.99€/mois (bientôt disponible) |
|---------|------|--------------------|----------------------|
| Instances Minecraft | Illimitées | Illimitées | Illimitées |
| Comptes Minecraft | Illimité | Illimité | Illimité |
| Restauration cloud | — | ✓ | ✓ |
| Sync cloud (saves) | — | 3 saves max | 10 saves max |
| Instances synchronisées | — | 4 max | 10 max |
| Statistiques avancées | — | ✓ | ✓ |
| Grade décoratif en jeu | — | ✓ | ✓ |
| Serveur 1 clic | — | — | ✓ |

## Fonctionnalités

- Lancement Minecraft vanilla, Fabric, Forge (toutes versions)
- Gestion d'instances isolées (mods, saves, configs par instance)
- Installation et gestion des mods (toggle, import URL)
- Console de jeu intégrée avec coloration syntaxique
- Synchronisation cloud des saves et configurations
- Statistiques de jeu (temps par instance, sessions, graphiques)
- Multijoueur P2P décentralisé — pas de serveur central requis
- Authentification Microsoft OAuth officielle

## Architecture

```
YuyuFrame 2/
├── Launcher-Client/  Code public (repo séparé YuyuFrame-v2)
│   ├── Backend/      Tauri 2 + Rust — launcher, auth, DB SQLite
│   ├── Frontend/     React + TypeScript — UI Vite + Tailwind
│   └── Launcher-Agent/ DLL JNI — installation de resource packs Modrinth in-game
├── LauncherAPI/    API REST Axum — auth, paiement, cloud sync
└── P2P-Server/
    ├── p2p-agent/  Java Agent — injection Mixin dans la JVM Minecraft
    └── rust-core/  DLL JNI — ownership engine, deltas P2P
```

## Code Signature
Code signing provided by SignPath Foundation
