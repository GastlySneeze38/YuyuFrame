//! Reconnaître en jeu les autres joueurs YuyuFrame : tout ce qui interroge
//! LauncherAPI (`POST /v1/presence/lookup`) et s'en souvient.
//!
//! Java ne fait que poser une question, sans jamais attendre : « ce joueur
//! (UUID + pseudo de son profil) utilise-t-il YuyuFrame ? ». La réponse vient
//! de la mémoire (`directory.rs`) ; un joueur inconnu ou dont la réponse a
//! vieilli est mis en file, et un thread à nous (`worker.rs`) vide cette file
//! par paquets (`client.rs`). Ni réseau ni attente sur le fil du jeu.
//!
//! Ce qui part chez nous n'est jamais un UUID ni un pseudo, mais leur
//! empreinte commune (`fingerprint.rs`), la même que calcule le serveur.
//!
//! Serveurs de jeu en mode hors ligne (2026-10-08) : l'UUID y est dérivé du
//! pseudo, donc l'empreinte porte aussi l'adresse du serveur, et c'est le jeu
//! — seul à savoir où il est — qui s'y annonce, dans la même requête que sa
//! liste de joueurs (`POST /v1/presence/offline`). Java donne l'adresse et
//! notre profil par `yf_presence_context` ; sans adresse (solo, ou réglage
//! « Serveurs Microsoft uniquement »), rien de tout cela ne part.
//!
//! Ces questions partent avec le jeton d'accès du compte, que le launcher
//! envoie sur l'entrée standard du jeu à chaque renouvellement (`token.rs`) :
//! il est lu ICI et ne passe ni par la ligne de commande, ni par
//! l'environnement, ni par Java. Sans jeton — lancement hors launcher, joueur
//! sans compte YuyuFrame, launcher fermé depuis plus d'un quart d'heure —
//! tout répond « inconnu » et rien ne part.
//!
//! Java y entre par `bridge.rs`, appelé par FFM. Côté Java :
//! `runtime/presence/NativePresence.java`.

mod bridge;
mod client;
mod directory;
mod fingerprint;
mod token;
mod worker;
