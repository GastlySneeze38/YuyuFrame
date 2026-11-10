/**
 * L'adresse d'un serveur telle qu'on la montre et qu'on la rejoint, sans le
 * port par défaut (2026-11-10).
 *
 * Le jeu décrit toujours son serveur en « hôte:port » et ajoute `:25565`
 * quand on n'a pas tapé de port (`PresenceContext.typedForm` : cette forme
 * sert aussi à reconnaître les joueurs YuyuFrame, donc elle ne change pas, et
 * LauncherAPI l'exige). Or un port écrit empêche le jeu de suivre
 * l'enregistrement DNS (SRV) qui dit où se trouve vraiment le serveur : beaucoup
 * de serveurs passent par là, et `play.exemple.fr:25565` peut échouer là où
 * `play.exemple.fr` marche. Un autre port, lui, a été tapé exprès : il reste.
 */
export function withoutDefaultPort(address: string): string {
  const text = address.trim()
  // IPv6 entre crochets : « [2001:db8::1]:25565 ».
  if (text.startsWith('[')) return text.replace(/^(\[[^\]]+\]):25565$/, '$1')
  // Plusieurs deux-points sans crochets : une IPv6 nue, sans port.
  if (text.indexOf(':') !== text.lastIndexOf(':')) return text
  return text.replace(/:25565$/, '')
}
