// Incrémenter cette valeur à chaque mise à jour qui change le système de
// connexion (nouveau format de token, migration OAuth, rotation de secret
// backend...) au point d'invalider les sessions Minecraft existantes. Au
// prochain démarrage, tout utilisateur dont la version stockée (voir
// authSystemVersion, useStore.ts) est inférieure verra ReconnectModal une
// fois (voir App.tsx), avant d'être remis à jour silencieusement.
export const AUTH_SYSTEM_VERSION = 1
