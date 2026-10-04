// Incrémenter cette valeur à chaque mise à jour qui change le système de
// connexion (nouveau format de token, migration OAuth, rotation de secret
// backend...) au point d'invalider les sessions Minecraft existantes. Au
// prochain démarrage, tout utilisateur dont la version stockée (voir
// authSystemVersion, useStore.ts) est inférieure verra ReconnectModal une
// fois (voir App.tsx), avant d'être remis à jour silencieusement.
export const AUTH_SYSTEM_VERSION = 1

// Le pendant pour le compte YuyuFrame. À incrémenter quand le serveur ferme
// toutes les sessions (LauncherAPI/migrations/0014_sign_out_everyone.sql,
// launcher 0.1.4 : e-mail confirmé, second facteur, empreinte de l'appareil).
// Au premier démarrage d'une version qui la monte, le launcher oublie sa
// session YuyuFrame et propose de se reconnecter ou de créer un compte
// (YuyuReconnectModal). Contrairement à la constante du dessus, la valeur
// persistée part de 0 : une installation neuve voit aussi l'invitation, ce
// qui est voulu — elle n'a pas de compte connecté non plus.
export const YUYU_AUTH_VERSION = 1
