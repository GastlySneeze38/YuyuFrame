/** Détecte un compte hors ligne (voir mc_add_offline côté Rust) sans aller
 * chercher l'info en base : les UUID hors ligne sont générés en v3
 * (name-based, convention vanilla "OfflinePlayer:<pseudo>"), alors que les
 * comptes Microsoft/Mojang réels reçoivent toujours un UUID v4 (aléatoire).
 * Le nibble de version est le premier caractère du 3ᵉ groupe du format
 * `xxxxxxxx-xxxx-Vxxx-xxxx-xxxxxxxxxxxx`. */
export function isOfflineAccount(uuid: string): boolean {
  return uuid.length >= 15 && uuid[14] === '3'
}
