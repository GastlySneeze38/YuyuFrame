import { fr, type TranslationSchema } from './fr'

/**
 * L'alphabet galactique standard — l'écriture de la table d'enchantement.
 *
 * C'est une **neuvième langue à part entière** : elle a son entrée dans
 * `Lang`, sa carte dans les réglages, et `t()` y pioche exactement comme dans
 * les huit autres. Rien n'est transformé après coup à l'affichage.
 *
 * ── Pourquoi le dictionnaire est calculé ici plutôt que tapé ──────────────
 * Le fichier existe, mais son contenu se déduit entièrement du français :
 * l'écriture galactique est un chiffrement lettre à lettre, pas une
 * traduction. Recopier les 800 clés en glyphes aurait donné une copie figée
 * — chaque clé ajoutée ensuite serait restée en clair au milieu des glyphes,
 * et personne ne l'aurait vu passer. Calculé au chargement, le dictionnaire
 * suit le français sans qu'on y pense, et il est impossible qu'il s'en
 * désynchronise.
 *
 * Le coût est nul à l'échelle du launcher : environ 800 chaînes transformées
 * une fois, au démarrage du module.
 *
 * ── Les glyphes ───────────────────────────────────────────────────────────
 * Des caractères Unicode choisis pour leur ressemblance, pas la police du
 * jeu : l'embarquer aurait demandé un fichier de police pour une
 * plaisanterie. Un glyphe absent de la police du système s'affiche en carré,
 * sans conséquence sur un texte illisible par construction.
 *
 * Easter egg de la 0.1.0-27 — voir `docs/product/easter-eggs.md`.
 */

/** Une lettre latine → son glyphe. Seules les lettres y sont : chiffres,
 *  ponctuation et espaces traversent intacts, pour que les tailles de
 *  fichiers et les numéros de version gardent leur sens. */
const GLYPHS: Record<string, string> = {
  a: 'ᔑ', b: 'ʖ', c: 'ᓵ', d: '↸', e: 'ᒷ', f: '⎓', g: '⊣', h: '⍑', i: '╎',
  j: '⋮', k: 'ꖌ', l: 'ꖎ', m: 'ᒲ', n: 'リ', o: '𝙹', p: '¡', q: 'ᑑ', r: '∷',
  s: 'ᓭ', t: 'ℸ', u: '⚍', v: '⍊', w: '∴', x: '⌇', y: '⌿', z: '⨅',
}

/**
 * Chiffre un texte, en **laissant les emplacements de variables intacts**.
 *
 * `{{count}}` doit traverser tel quel : transformé, plus rien ne viendrait s'y
 * substituer et l'interface afficherait des accolades en glyphes à la place
 * du nombre. C'est le seul endroit délicat de ce fichier.
 *
 * Les lettres accentuées sont d'abord ramenées à leur lettre de base : sans
 * ça, « é » et « ê » seraient passés en clair au milieu des glyphes et
 * auraient trahi les mots.
 */
function encipher(text: string): string {
  return text
    .split(/(\{\{\w+\}\})/)
    .map((part) =>
      part.startsWith('{{')
        ? part
        : part
          .normalize('NFD')
          .replace(/[̀-ͯ]/g, '')
          .split('')
          .map((c) => GLYPHS[c.toLowerCase()] ?? c)
          .join(''),
    )
    .join('')
}

/** Rejoue la forme du dictionnaire source en chiffrant chaque feuille. */
function encipherAll(node: unknown): unknown {
  if (typeof node === 'string') return encipher(node)
  if (node && typeof node === 'object') {
    return Object.fromEntries(
      Object.entries(node as Record<string, unknown>).map(([k, v]) => [k, encipherAll(v)]),
    )
  }
  return node
}

// Le cast est sûr par construction : `encipherAll` ne change que les feuilles,
// jamais les clés ni l'imbrication — c'est exactement la forme de `fr`.
export const sga = encipherAll(fr) as TranslationSchema

/** Le nom de la langue, dans sa propre écriture : « Enchante ». */
export const SGA_LABEL = encipher('Enchante')
