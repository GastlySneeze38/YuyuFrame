/**
 * Les dernières données connues, gardées d'un lancement à l'autre
 * (2026-11-06, audit des chargements réseau).
 *
 * Les caches du launcher vivaient en mémoire : chaque démarrage repartait de
 * zéro, et chaque écran restait vide le temps que le réseau réponde — long
 * sur une connexion lente, infini hors ligne. Ici on garde la dernière
 * réponse : l'écran s'affiche tout de suite avec elle, puis se met à jour
 * quand la nouvelle arrive (« stale-while-revalidate »).
 *
 * `localStorage` et pas un fichier côté Rust : la lecture est synchrone, donc
 * disponible dès le premier rendu, sans écran vide d'une image.
 *
 * Bornes : 1,5 Mo en tout (bien sous les ~5 Mo du navigateur, que le magasin
 * principal partage), une entrée de plus de 256 Ko n'est jamais gardée, et
 * les plus anciennes partent quand il faut de la place. Rien de personnel
 * au-delà de ce que l'écran affichait déjà ; les clés `user:` (données d'un
 * compte) sont effacées à la déconnexion (`clearPersistentCache('user:')`).
 */

const PREFIX = 'yf-cache:'
const INDEX = 'yf-cache-index'
const MAX_TOTAL = 1_500_000
const MAX_ENTRY = 256_000

interface IndexEntry { key: string; size: number; at: number }

function readIndex(): IndexEntry[] {
  try {
    const raw = localStorage.getItem(INDEX)
    return raw ? (JSON.parse(raw) as IndexEntry[]) : []
  } catch {
    return []
  }
}

function writeIndex(index: IndexEntry[]) {
  try {
    localStorage.setItem(INDEX, JSON.stringify(index))
  } catch {
    /* plein : l'index sera reconstruit au prochain écrit réussi */
  }
}

/** La dernière valeur gardée pour `key`, ou `null`. `maxAgeMs` : au-delà,
 *  la valeur est jugée trop vieille pour être montrée. */
export function readCache<T>(key: string, maxAgeMs = Infinity): { value: T; at: number } | null {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    if (!raw) return null
    const entry = JSON.parse(raw) as { at: number; value: T }
    if (Date.now() - entry.at > maxAgeMs) return null
    return entry
  } catch {
    return null
  }
}

/** Garde `value` pour `key`. Ne lève jamais : un cache plein n'est pas une erreur. */
export function writeCache<T>(key: string, value: T): void {
  let raw: string
  try {
    raw = JSON.stringify({ at: Date.now(), value })
  } catch {
    return
  }
  if (raw.length > MAX_ENTRY) return
  const index = readIndex().filter((e) => e.key !== key)
  let total = index.reduce((s, e) => s + e.size, 0)
  // Les plus anciennes partent d'abord.
  index.sort((a, b) => a.at - b.at)
  while (total + raw.length > MAX_TOTAL && index.length > 0) {
    const old = index.shift()!
    total -= old.size
    localStorage.removeItem(PREFIX + old.key)
  }
  try {
    localStorage.setItem(PREFIX + key, raw)
    index.push({ key, size: raw.length, at: Date.now() })
    writeIndex(index)
  } catch {
    /* quota du navigateur atteint malgré tout : on renonce pour cette fois */
  }
}

/** Efface les entrées dont la clé commence par `prefix` (tout, sans
 *  préfixe). Les données d'un compte portent `user:` et partent à la
 *  déconnexion ; le reste (catalogue, pings, flotte) n'appartient à personne. */
export function clearPersistentCache(prefix = ''): void {
  const index = readIndex()
  const keep = index.filter((e) => !e.key.startsWith(prefix))
  for (const e of index) if (e.key.startsWith(prefix)) localStorage.removeItem(PREFIX + e.key)
  if (keep.length === 0) localStorage.removeItem(INDEX)
  else writeIndex(keep)
}
