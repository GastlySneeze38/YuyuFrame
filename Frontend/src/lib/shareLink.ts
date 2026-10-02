/**
 * Liens de partage `yuyuframe://<sorte>/<données>` — pendant côté interface
 * de `Backend/src/share_link.rs`, qui les fabrique et les lit (zstd avec
 * dictionnaire figé, puis base 32 768 en idéogrammes : les données
 * ressemblent à du chinois, c'est normal).
 *
 * Rien n'est hébergé : tout tient dans le lien. Chaque écran qui partage
 * quelque chose a sa sorte (`instance`, `options`…) ; ici on ne fait que
 * reconnaître la sorte d'un lien (pour l'envoyer vers le bon écran, voir
 * `App.tsx`) et le copier. Une sorte nouvelle s'ajoute à `ShareLinkKind` et
 * au routage d'`App.tsx`.
 */

export const SHARE_LINK_SCHEME = 'yuyuframe://'

export type ShareLinkKind = 'instance' | 'options'

const KINDS: ShareLinkKind[] = ['instance', 'options']

/** Au-delà, un message Discord standard refuse le lien. */
export const DISCORD_MESSAGE_LIMIT = 2000

/** La sorte d'un lien de partage, ou `null` si ce n'en est pas un. */
export function shareLinkKind(text: string): ShareLinkKind | null {
  const trimmed = text.trim()
  if (!trimmed.startsWith(SHARE_LINK_SCHEME)) return null
  const kind = trimmed.slice(SHARE_LINK_SCHEME.length).split(/[?/#]/, 1)[0]
  return KINDS.find((k) => k === kind) ?? null
}

/** Fabrique un lien (par le Rust), le copie, et rend sa longueur. */
export async function copyShareLink(make: () => Promise<string>): Promise<number> {
  const link = await make()
  await navigator.clipboard.writeText(link)
  return link.length
}
