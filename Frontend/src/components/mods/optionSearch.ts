/**
 * Recherche dans les réglages de `options.txt`.
 *
 * Le problème n'est pas le volume — quelques dizaines de clés — mais le
 * vocabulaire. Les clés sont en anglais, en chameau, parfois préfixées
 * (`soundCategory_master`, `key_key.attack`), alors qu'on les cherche en
 * français et de mémoire. Un simple `includes` ne trouve donc rien à partir
 * de « distance », de « son » ou d'une frappe approximative.
 *
 * D'où quatre façons de correspondre, de la plus sûre à la plus permissive.
 * Chacune a son rang, et le rang décide de l'ordre : une correspondance
 * exacte passe toujours avant une approximation, et l'interface peut séparer
 * ce qu'elle a trouvé de ce qu'elle suggère.
 */

export type MatchKind = 'prefix' | 'contains' | 'group' | 'fuzzy' | 'near'

export interface SearchCandidate {
  key: string
  /** Libellé traduit, absent pour une clé que l'interface ne sait pas nommer. */
  label?: string
  /** Groupe traduit (Vidéo, Son…), pour situer le résultat. */
  group?: string
  /**
   * Autres noms du même réglage, cherchables mais jamais affichés.
   *
   * C'est par là que passe l'anglais : on désigne couramment une option de
   * Minecraft par son nom anglais — « brightness », « render distance » —
   * alors que le jeu et le launcher tournent en français. Sans ça, seul le
   * libellé de la langue courante répondrait, et la moitié des recherches
   * ne rendrait rien.
   */
  aliases?: string[]
  /** Pendant de `aliases` pour le nom du groupe (« Sound » pour « Son »). */
  groupAliases?: string[]
}

export interface SearchResult extends SearchCandidate {
  kind: MatchKind
  score: number
}

/**
 * Forme comparable d'un texte : sans accents, sans casse, et sans les
 * séparateurs qui n'existent que dans les clés (`_`, `.`, `-`, espaces).
 *
 * C'est ce dernier point qui compte le plus : sans lui, « soundmaster » ne
 * trouverait jamais `soundCategory_master`, et « distance affichage » ne
 * trouverait pas « Distance d'affichage ».
 */
export function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[\s._\-']/g, '')
}

/**
 * Vrai si toutes les lettres de `needle` apparaissent dans `haystack` dans
 * le même ordre, pas forcément d'affilée. C'est ce qui fait marcher les
 * abréviations : « rdist » trouve `renderDistance`.
 *
 * Rend aussi l'étalement de la correspondance : plus les lettres trouvées
 * sont resserrées, meilleur est le résultat.
 */
export function subsequenceSpan(needle: string, haystack: string): number | null {
  if (needle.length === 0) return null
  let first = -1
  let last = -1
  let n = 0
  for (let h = 0; h < haystack.length && n < needle.length; h++) {
    if (haystack[h] === needle[n]) {
      if (first < 0) first = h
      last = h
      n++
    }
  }
  return n === needle.length ? last - first + 1 : null
}

/**
 * Distance d'édition, bornée : au-delà de `max`, la valeur exacte n'a aucun
 * intérêt puisqu'on ne proposera pas le résultat. S'arrêter tôt évite de
 * remplir une matrice complète pour chaque clé à chaque frappe.
 */
export function editDistance(a: string, b: string, max: number): number {
  if (Math.abs(a.length - b.length) > max) return max + 1
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i)
  for (let i = 1; i <= a.length; i++) {
    const row = [i]
    let best = i
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      const value = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + cost)
      row.push(value)
      if (value < best) best = value
    }
    // Toute la ligne dépasse le seuil : les suivantes ne peuvent que monter.
    if (best > max) return max + 1
    prev = row
  }
  return prev[b.length]
}

/** Rang de base de chaque façon de correspondre. L'écart entre deux rangs est
 *  plus grand que tout ajustement, donc un `contains` passe toujours devant
 *  un `fuzzy`, quelle que soit la longueur des clés.
 *
 *  `group` se place entre les deux : chercher « son » doit remonter les
 *  réglages du groupe Son avant ceux dont le nom contient ces trois lettres
 *  par accident — « champ de vision » en contient un, éparpillé. Mais un
 *  réglage dont le nom propre contient la requête reste devant. */
const RANK: Record<MatchKind, number> = { prefix: 4000, contains: 3000, group: 2600, fuzzy: 2000, near: 1000 }

/** Retrait appliqué aux noms non affichés. Assez pour départager deux
 *  réglages à égalité, trop peu pour faire perdre une correspondance
 *  littérale face à une approximation — les rangs restent séparés de 400 au
 *  minimum. */
const ALIAS_PENALTY = 25

/** Au-delà, une faute de frappe n'en est plus une. Deux suffisent pour les
 *  cas réels (inversion, lettre en trop, lettre manquante) sans rapprocher
 *  des mots qui n'ont rien à voir. */
const MAX_TYPO = 2

function scoreAgainst(query: string, text: string): { kind: MatchKind; score: number } | null {
  if (!text) return null

  const at = text.indexOf(query)
  if (at === 0) return { kind: 'prefix', score: RANK.prefix - text.length }
  if (at > 0) return { kind: 'contains', score: RANK.contains - at * 4 - text.length }

  const span = subsequenceSpan(query, text)
  // Un étalement énorme veut dire des lettres éparpillées d'un bout à
  // l'autre : la correspondance est vraie mais ne veut rien dire.
  if (span !== null && span <= query.length * 4) {
    return { kind: 'fuzzy', score: RANK.fuzzy - span }
  }

  // Frappe approximative : on ne compare qu'à longueur comparable, sinon
  // « fov » serait « proche » de n'importe quel mot de trois lettres du
  // fichier.
  if (query.length >= 3 && Math.abs(text.length - query.length) <= MAX_TYPO) {
    const distance = editDistance(query, text, MAX_TYPO)
    if (distance <= MAX_TYPO) return { kind: 'near', score: RANK.near - distance * 100 }
  }
  return null
}

/**
 * Classe les réglages pour une requête.
 *
 * La clé **et** le libellé traduit sont testés, et le meilleur des deux est
 * retenu : on cherche indifféremment « renderDistance » et « distance
 * d'affichage ». À égalité, un réglage que l'interface sait nommer passe
 * devant une clé brute — il est plus utile à qui cherche.
 */
export function searchOptions(rawQuery: string, candidates: SearchCandidate[], limit = 8): SearchResult[] {
  const query = normalize(rawQuery)
  if (query.length === 0) return []

  const results: SearchResult[] = []
  for (const candidate of candidates) {
    const onKey = scoreAgainst(query, normalize(candidate.key))
    const onLabel = candidate.label ? scoreAgainst(query, normalize(candidate.label)) : null
    // Les autres noms sont légèrement en retrait : quand deux réglages
    // différents répondent, l'un par son libellé affiché et l'autre par un
    // nom qu'on ne montre pas, celui qu'on lit à l'écran passe devant.
    const onAlias = (candidate.aliases ?? [])
      .map((alias) => scoreAgainst(query, normalize(alias)))
      .filter((m): m is { kind: MatchKind; score: number } => m !== null)
      .map((m) => ({ kind: m.kind, score: m.score - ALIAS_PENALTY }))
      .sort((a, b) => b.score - a.score)[0] ?? null

    // Le groupe ne se cherche qu'en début de mot : une correspondance
    // approximative sur « Vidéo » ou « Son » ramasserait la moitié du fichier
    // sans que personne ne comprenne pourquoi.
    const groupTexts = [candidate.group, ...(candidate.groupAliases ?? [])]
      .filter((g): g is string => !!g)
      .map(normalize)
    const groupHit = groupTexts.find((g) => g.startsWith(query))
    const onGroup = groupHit
      ? { kind: 'group' as const, score: RANK.group - groupHit.length }
      : null

    const best = [onKey, onLabel, onAlias, onGroup]
      .filter((m): m is { kind: MatchKind; score: number } => m !== null)
      .sort((a, b) => b.score - a.score)[0]
    if (!best) continue
    // Un réglage que l'interface sait nommer passe devant une clé brute à
    // égalité : il est plus utile à qui cherche.
    results.push({ ...candidate, kind: best.kind, score: best.score + (candidate.label ? 50 : 0) })
  }

  results.sort((a, b) => b.score - a.score || a.key.localeCompare(b.key))
  return results.slice(0, limit)
}

/**
 * Réglages de la même famille qu'une clé donnée.
 *
 * Minecraft groupe par préfixe (`soundCategory_master`, `soundCategory_music`
 * …). Avoir trouvé le volume général, c'est très souvent vouloir régler la
 * musique juste après — autant les montrer plutôt que de faire recommencer la
 * recherche.
 */
export function relatedOptions(key: string, candidates: SearchCandidate[], limit = 4): SearchCandidate[] {
  const separator = key.search(/[_.]/)
  if (separator <= 0) return []
  const family = key.slice(0, separator + 1)
  return candidates
    .filter((c) => c.key !== key && c.key.startsWith(family))
    .slice(0, limit)
}
