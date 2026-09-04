import type { JvmArgsMode, JvmFlagCategory, JvmVendor } from '@/types'

/**
 * Utilitaires de l'écran "Configurations JVM" (pages/JvmProfiles,
 * pages/JvmProfileEditor).
 *
 * Ce fichier duplique volontairement une partie du raisonnement de
 * `merge_jvm_args` côté Rust (Backend/src/minecraft/launcher/jvm_args.rs) :
 * la fusion réelle reste faite au lancement par le backend, ici on ne s'en
 * sert que pour AVERTIR pendant la frappe (doublons, sélecteurs de GC
 * concurrents) sans attendre un aller-retour de résolution JVM. Toute
 * divergence entre les deux se voit immédiatement dans l'aperçu, qui lui
 * vient bien du backend.
 */

/** Même découpage que `parse_user_jvm_args` côté Rust : lignes vides et
 * lignes commençant par `#` ignorées, puis découpage sur les espaces. */
export function parseJvmArgs(raw: string): string[] {
  return raw
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith('#'))
    .flatMap((l) => l.split(/\s+/))
    .filter(Boolean)
}

/** Même identité que `arg_key` côté Rust — `-XX:+Foo` et `-XX:-Foo` sont le
 * même réglage, `-XX:Foo=1` et `-XX:Foo=2` aussi. */
export function jvmArgKey(arg: string): string {
  for (const prefix of ['-Xmx', '-Xms', '-Xmn', '-Xss', '-Xgcpolicy:']) {
    if (arg.startsWith(prefix)) return prefix.replace(/:$/, '')
  }
  if (arg.startsWith('-XX:')) {
    const name = arg.slice(4).replace(/^[+-]/, '')
    return `-XX:${name.split('=')[0]}`
  }
  if (arg.startsWith('-D')) return `-D${arg.slice(2).split('=')[0]}`
  return arg
}

/** Un sélecteur de GC **actif**. La forme négative (`-XX:-UseG1GC`) n'en est
 * pas un : elle éteint un collecteur, elle n'en choisit aucun — et c'est même
 * la manière normale d'en poser un autre (le jeu Shenandoah contient les deux).
 * Les compter ensemble ferait crier au conflit sur une config parfaitement
 * valide. */
export function isGcSelector(arg: string): boolean {
  if (arg.startsWith('-Xgcpolicy:')) return true
  if (!arg.startsWith('-XX:+Use')) return false
  return arg.endsWith('GC')
}

/** Nom lisible du collecteur qu'un drapeau sélectionne. */
export function gcSelectorLabel(arg: string): string {
  if (arg.startsWith('-Xgcpolicy:')) return arg.slice('-Xgcpolicy:'.length)
  const name = arg.slice('-XX:+Use'.length)
  return name === 'ShenandoahGC' ? 'Shenandoah' : name
}

// ── Drapeaux structurés ──────────────────────────────────────────────────────

/**
 * Un drapeau décomposé en nom + valeur, pour l'éditeur en liste.
 *
 * Le nom ne se retape jamais une fois le drapeau ajouté : c'est là que se
 * logent les fautes de frappe, et un `-XX:MaxGCPauseMilis` mal orthographié
 * empêche la JVM de démarrer sans que rien n'indique lequel des vingt drapeaux
 * est en cause. La valeur, elle, reste librement modifiable — c'est ce qu'on
 * fait varier d'un test à l'autre.
 *
 * Les lignes de commentaire sont conservées comme des entrées à part entière :
 * les jeux de drapeaux livrés en contiennent, et les perdre à la première
 * modification effacerait l'explication de ce qu'on est en train de tester.
 */
export type JvmArgKind = 'boolean' | 'value' | 'flag' | 'comment'

export interface JvmArgEntry {
  id: number
  kind: JvmArgKind
  /** Nom seul, signe et valeur exclus : `-XX:MaxGCPauseMillis`, `-XX:AlwaysPreTouch`, `-Xmx`. */
  name: string
  /** Séparateur d'origine, pour réécrire le drapeau à l'identique : `-XX:Foo=1`
   * (`=`), `-Xgcpolicy:gencon` (`:`), `-Xmx6g` (aucun). */
  sep: '' | '=' | ':'
  /** `boolean` : `+` ou `-`. `value` : la valeur. `comment` : la ligne entière. */
  value: string
}

let nextEntryId = 1

const SIZE_FLAGS = ['-Xmx', '-Xms', '-Xmn', '-Xss']

/** Décompose un drapeau. Jamais de rejet : ce qui n'entre dans aucune forme
 * connue devient un drapeau sans valeur, réécrit tel quel. */
export function parseArgEntry(token: string): JvmArgEntry {
  const id = nextEntryId++
  if (/^-XX:[+-]/.test(token)) {
    return { id, kind: 'boolean', name: `-XX:${token.slice(5)}`, sep: '', value: token[4] }
  }
  if (token.startsWith('-XX:') || token.startsWith('-D')) {
    const eq = token.indexOf('=')
    if (eq === -1) return { id, kind: 'flag', name: token, sep: '', value: '' }
    return { id, kind: 'value', name: token.slice(0, eq), sep: '=', value: token.slice(eq + 1) }
  }
  for (const p of SIZE_FLAGS) {
    if (token.startsWith(p)) return { id, kind: 'value', name: p, sep: '', value: token.slice(p.length) }
  }
  const colon = token.indexOf(':')
  if (token.startsWith('-X') && colon > 0) {
    return { id, kind: 'value', name: token.slice(0, colon), sep: ':', value: token.slice(colon + 1) }
  }
  return { id, kind: 'flag', name: token, sep: '', value: '' }
}

export function serializeArgEntry(e: JvmArgEntry): string {
  switch (e.kind) {
    case 'comment': return e.value
    case 'boolean': return `-XX:${e.value}${e.name.slice(4)}`
    case 'value': return `${e.name}${e.sep}${e.value}`
    default: return e.name
  }
}

/** Texte brut → entrées. Même découpage que `parse_user_jvm_args` côté Rust
 * (plusieurs drapeaux par ligne acceptés), commentaires préservés. */
export function parseArgEntries(raw: string): JvmArgEntry[] {
  const out: JvmArgEntry[] = []
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) continue
    if (trimmed.startsWith('#')) {
      out.push({ id: nextEntryId++, kind: 'comment', name: '', sep: '', value: trimmed })
      continue
    }
    for (const token of trimmed.split(/\s+/)) {
      if (token) out.push(parseArgEntry(token))
    }
  }
  return out
}

export function serializeArgEntries(entries: JvmArgEntry[]): string {
  return entries.map(serializeArgEntry).join('\n')
}

/** Recolle une valeur saisie séparément au nom, avec le séparateur qu'attend
 * la forme du drapeau : `-XX:Foo` prend `=`, `-Xmx` ne prend rien,
 * `-Xgcpolicy` prend `:`. */
export function attachArgValue(name: string, value: string): string {
  if (!value) return name
  if (name.startsWith('-XX:') || name.startsWith('-D')) return `${name}=${value}`
  if (SIZE_FLAGS.includes(name)) return `${name}${value}`
  if (name.startsWith('-X')) return `${name}:${value}`
  return `${name}=${value}`
}

/** Noms de drapeaux connus pour cette catégorie et cette famille, tirés des
 * jeux livrés — proposés en autocomplétion pour éviter d'avoir à les taper. */
export function suggestionsFor(category: JvmFlagCategory, family: JvmFamily): string[] {
  const names = new Set<string>()
  for (const preset of presetsFor(category, family)) {
    for (const token of parseJvmArgs(preset.body)) {
      const e = parseArgEntry(token)
      names.add(e.kind === 'boolean' ? `-XX:+${e.name.slice(4)}` : e.name)
    }
  }
  return [...names].sort()
}

// ── La grille ────────────────────────────────────────────────────────────────

/** Famille de JVM réellement obtenue à partir de la grille. C'est elle, pas le
 * libellé du vendeur, qui décide quels drapeaux ont un sens : la syntaxe GC
 * d'OpenJ9 (`-Xgcpolicy:*`) n'a rien à voir avec celle d'HotSpot, et le JIT
 * Graal n'existe que sur une GraalVM. */
export type JvmFamily = 'hotspot' | 'openj9' | 'graal'

/** Même règle que `resolve_auto_vendor` côté Rust — dupliquée ici uniquement
 * pour dire ce que "Auto" choisirait, la décision réelle reste au backend. */
export function autoVendorFor(ramMb: number): 'openj9' | 'temurin' {
  return ramMb <= 2048 ? 'openj9' : 'temurin'
}

export function familyFor(vendor: JvmVendor, ramMb: number): JvmFamily {
  const resolved = vendor === 'auto' ? autoVendorFor(ramMb) : vendor
  if (resolved === 'openj9') return 'openj9'
  if (resolved === 'graal') return 'graal'
  return 'hotspot'
}

/** Une GraalVM reste une HotSpot côté GC et mémoire : elle n'ajoute qu'un
 * compilateur. Tout ce qui vaut pour HotSpot vaut donc aussi pour elle. */
function familyMatches(preset: JvmPreset, family: JvmFamily): boolean {
  if (preset.families.includes(family)) return true
  return family === 'graal' && preset.families.includes('hotspot')
}

// ── Analyse du champ ─────────────────────────────────────────────────────────

export interface JvmArgsLint {
  count: number
  /** Drapeaux définis deux fois — le dernier gagne, mais c'est presque
   * toujours une coquille (un jeu de drapeaux collé deux fois). */
  duplicates: string[]
  /** Plusieurs sélecteurs de GC : la JVM refuse de démarrer
   * ("Multiple garbage collectors selected"). */
  gcSelectors: string[]
}

/** Analyse l'ensemble des catégories d'un coup : un doublon entre la catégorie
 * GC et la catégorie JIT est exactement aussi cassant qu'un doublon interne, et
 * ne se verrait pas en analysant chaque champ isolément. */
export function lintJvmArgs(...raws: string[]): JvmArgsLint {
  const args = raws.flatMap(parseJvmArgs)
  const seen = new Map<string, number>()
  for (const a of args) {
    const k = jvmArgKey(a)
    seen.set(k, (seen.get(k) ?? 0) + 1)
  }
  return {
    count: args.length,
    duplicates: [...seen.entries()].filter(([, n]) => n > 1).map(([k]) => k),
    gcSelectors: args.filter(isGcSelector),
  }
}

// ── Jeux de drapeaux ─────────────────────────────────────────────────────────

export interface JvmPreset {
  id: string
  label: string
  /** Une phrase — ce que ce jeu cherche à obtenir, et sa provenance. */
  hint: string
  category: JvmFlagCategory
  /** Familles de JVM pour lesquelles ce jeu a un sens. */
  families: JvmFamily[]
  /** `true` : jeu complet, remplace le contenu de sa catégorie.
   * `false` : complément, s'ajoute à ce qui est déjà là. */
  full: boolean
  body: string
}

export const CATEGORY_META: Record<JvmFlagCategory, { label: string; sub: string; empty: string }> = {
  jvm: {
    label: 'Moteur',
    sub: "Mémoire, threads, comportement général de la JVM.",
    empty: 'Aucun réglage moteur',
  },
  gc: {
    label: 'Ramasse-miettes',
    sub: "Le collecteur et son réglage. En poser un ici remplace celui des réglages JVM.",
    empty: 'Aucun réglage de ramasse-miettes',
  },
  jit: {
    label: 'Compilateur',
    sub: "Ce que la JVM accepte de compiler en code natif, et la place qu'elle garde pour le stocker.",
    empty: 'Aucun réglage de compilateur',
  },
}

/**
 * Jeux de drapeaux prêts à tester, filtrés par la grille (voir
 * `presetsFor`). Tous vérifiés comme acceptés par un Java 25 — plusieurs
 * drapeaux des listes communautaires d'origine ont été RETIRÉS parce qu'ils
 * ont disparu des JDK récents et qu'un `-XX` inconnu empêche la JVM de
 * démarrer tout court : `G1ConcRSHotCardLimit` et
 * `G1ConcRefinementServiceIntervalMillis` (brucethemoose),
 * `ShenandoahGCMode=iu` (mode supprimé).
 */
export const JVM_PRESETS: JvmPreset[] = [
  // ── Moteur ─────────────────────────────────────────────────────────────────
  {
    id: 'engine-client',
    label: 'Base client',
    hint: "Ce que le launcher pose déjà de son côté : tas pré-touché, System.gc() des mods ignoré, pas de fichier perf OS.",
    category: 'jvm',
    families: ['hotspot'],
    full: true,
    body: [
      '-XX:+AlwaysPreTouch',
      '-XX:+DisableExplicitGC',
      '-XX:+PerfDisableSharedMem',
      '-XX:+UseStringDeduplication',
    ].join('\n'),
  },
  {
    id: 'engine-numa',
    label: '+ NUMA',
    hint: "N'a d'effet que sur une machine multi-socket ou un Ryzen à plusieurs CCX ; inoffensif ailleurs.",
    category: 'jvm',
    families: ['hotspot'],
    full: false,
    body: '-XX:+UseNUMA',
  },
  {
    id: 'engine-stack',
    label: '+ Pile large',
    hint: "2 Mo de pile par thread — utile quand un mod à récursion profonde déclenche des StackOverflowError.",
    category: 'jvm',
    families: ['hotspot', 'openj9'],
    full: false,
    body: '-Xss2m',
  },
  {
    id: 'engine-largepages',
    label: '+ Grandes pages',
    hint: "Demande à l'OS des pages mémoire de 2 Mo au lieu de 4 Ko : moins de défauts de cache d'adresses sur un gros tas. Sans le privilège Windows « Verrouiller les pages en mémoire », la JVM démarre quand même et l'ignore avec un avertissement.",
    category: 'jvm',
    families: ['hotspot'],
    full: false,
    body: '-XX:+UseLargePages',
  },
  {
    id: 'engine-threadprio',
    label: '+ Priorité des threads',
    hint: "Laisse la JVM appliquer réellement ses priorités de threads, pour que le rendu passe devant les tâches de fond. Sous Linux sans droits root, la JVM avertit et continue sans.",
    category: 'jvm',
    families: ['hotspot'],
    full: false,
    body: ['-XX:+UseThreadPriorities', '-XX:ThreadPriorityPolicy=1'].join('\n'),
  },
  {
    id: 'engine-openj9',
    label: 'Base OpenJ9',
    hint: "Le strict nécessaire côté OpenJ9 : rien de plus n'a été vérifié en conditions réelles, et un drapeau inconnu empêche la JVM de démarrer.",
    category: 'jvm',
    families: ['openj9'],
    full: true,
    body: ['-XX:+UseCompressedOops', '-Xdisableexplicitgc'].join('\n'),
  },

  // ── Ramasse-miettes ────────────────────────────────────────────────────────
  {
    id: 'gc-brucethemoose',
    label: 'brucethemoose (client)',
    hint: "Le jeu benchmarké CÔTÉ CLIENT : beaucoup de pauses très courtes plutôt que quelques longues, parce qu'une frame ne peut rien amortir.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: [
      '# brucethemoose/Minecraft-Performance-Flags-Benchmarks (client, G1)',
      '-XX:+UseG1GC',
      '-XX:+ParallelRefProcEnabled',
      '-XX:MaxGCPauseMillis=37',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:G1NewSizePercent=23',
      '-XX:G1MaxNewSizePercent=40',
      '-XX:G1HeapRegionSize=16M',
      '-XX:G1ReservePercent=20',
      '-XX:G1HeapWastePercent=20',
      '-XX:G1MixedGCCountTarget=3',
      '-XX:InitiatingHeapOccupancyPercent=10',
      '-XX:G1MixedGCLiveThresholdPercent=90',
      '-XX:G1RSetUpdatingPauseTimePercent=0',
      '-XX:SurvivorRatio=32',
      '-XX:MaxTenuringThreshold=1',
    ].join('\n'),
  },
  {
    id: 'gc-aikar',
    label: 'Aikar (serveur)',
    hint: "La référence côté SERVEUR : dimensionnée pour tenir un tick de 50 ms, pas une frame de 3 ms. À comparer, pas à prendre par défaut sur un client.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: [
      '# Aikar — https://docs.papermc.io/paper/aikars-flags',
      '-XX:+UseG1GC',
      '-XX:+ParallelRefProcEnabled',
      '-XX:MaxGCPauseMillis=200',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:G1NewSizePercent=30',
      '-XX:G1MaxNewSizePercent=40',
      '-XX:G1HeapRegionSize=8M',
      '-XX:G1ReservePercent=20',
      '-XX:G1HeapWastePercent=5',
      '-XX:G1MixedGCCountTarget=4',
      '-XX:InitiatingHeapOccupancyPercent=15',
      '-XX:G1MixedGCLiveThresholdPercent=90',
      '-XX:G1RSetUpdatingPauseTimePercent=5',
      '-XX:SurvivorRatio=32',
      '-XX:MaxTenuringThreshold=1',
    ].join('\n'),
  },
  {
    id: 'gc-aikar-bigheap',
    label: 'Aikar — gros tas (≥ 12 Go)',
    hint: "La variante que documente Aikar au-delà de 12 Go : zone jeune plus large, blocs de 16 Mo, cycle déclenché plus tôt. Sur un petit tas, elle est contre-productive.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: [
      '# Aikar, variante gros tas — https://docs.papermc.io/paper/aikars-flags',
      '-XX:+UseG1GC',
      '-XX:+ParallelRefProcEnabled',
      '-XX:MaxGCPauseMillis=200',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:G1NewSizePercent=40',
      '-XX:G1MaxNewSizePercent=50',
      '-XX:G1HeapRegionSize=16M',
      '-XX:G1ReservePercent=15',
      '-XX:G1HeapWastePercent=5',
      '-XX:G1MixedGCCountTarget=4',
      '-XX:InitiatingHeapOccupancyPercent=20',
      '-XX:G1MixedGCLiveThresholdPercent=90',
      '-XX:G1RSetUpdatingPauseTimePercent=5',
      '-XX:SurvivorRatio=32',
      '-XX:MaxTenuringThreshold=1',
    ].join('\n'),
  },
  {
    id: 'gc-g1-minimal',
    label: 'G1 minimal',
    hint: "Le collecteur par défaut avec seulement les deux réglages qui font consensus. Le point de départ neutre quand on veut mesurer l'effet d'un seul drapeau ajouté ensuite.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: ['-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200'].join('\n'),
  },
  {
    id: 'gc-shenandoah',
    label: 'Shenandoah',
    hint: "Évacuation concurrente : des pauses courtes sans la taxe de débit des barrières de lecture de ZGC. Le mode `iu` a été supprimé, `generational` le remplace.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: [
      '# La négation de UseG1GC est OBLIGATOIRE, sinon la JVM refuse de',
      '# démarrer ("Multiple garbage collectors selected").',
      '-XX:-UseG1GC',
      '-XX:+UseShenandoahGC',
      '-XX:ShenandoahGCMode=generational',
    ].join('\n'),
  },
  {
    id: 'gc-shenandoah-satb',
    label: 'Shenandoah — SATB',
    hint: "Le mode historique de Shenandoah, non générationnel : il parcourt tout le tas à chaque cycle. À comparer au mode générationnel, qui gagne presque toujours sur Minecraft.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: ['-XX:-UseG1GC', '-XX:+UseShenandoahGC', '-XX:ShenandoahGCMode=satb'].join('\n'),
  },
  {
    id: 'gc-zgc',
    label: 'ZGC générationnel',
    hint: "Pauses sous la milliseconde, payées par une barrière sur chaque lecture de référence — mesuré ici à ~26 % de débit en moins. Utile comme point de comparaison.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: ['-XX:-UseG1GC', '-XX:+UseZGC', '-XX:ZAllocationSpikeTolerance=5.0'].join('\n'),
  },
  {
    id: 'gc-parallel',
    label: 'ParallelGC',
    hint: "Débit brut maximum, pauses longues assumées. Le plancher qui dit combien coûtent réellement les GC concurrents.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: ['-XX:-UseG1GC', '-XX:+UseParallelGC'].join('\n'),
  },
  {
    id: 'gc-serial',
    label: 'SerialGC',
    hint: "Un seul thread de collecte, aucune coordination. Sur un petit tas et peu de cœurs, il bat parfois G1 — au-delà, il décroche vite.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: ['-XX:-UseG1GC', '-XX:+UseSerialGC'].join('\n'),
  },
  {
    id: 'gc-epsilon',
    label: 'Epsilon — mesure',
    hint: "Ne collecte RIEN : le jeu plante dès que le tas est plein, souvent en quelques minutes. Ce n'est pas une config de jeu — c'est la seule façon de mesurer ce que le ramasse-miettes coûte réellement, en le retirant.",
    category: 'gc',
    families: ['hotspot'],
    full: true,
    body: [
      '# Outil de mesure, PAS une config jouable : plante quand le tas est plein.',
      '-XX:-UseG1GC',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:+UseEpsilonGC',
    ].join('\n'),
  },
  {
    id: 'gc-log',
    label: '+ Journal GC',
    hint: "Écrit chaque pause dans gc.log à côté du jeu — le seul moyen de dire si une chute de FPS vient du GC plutôt que du meshing de chunks.",
    category: 'gc',
    families: ['hotspot'],
    full: false,
    body: '-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10M',
  },
  {
    id: 'gc-j9-gencon',
    label: 'gencon',
    hint: "Générationnel concurrent — adapté aux objets à durée de vie courte de Minecraft (entités, particules, paquets).",
    category: 'gc',
    families: ['openj9'],
    full: true,
    body: '-Xgcpolicy:gencon',
  },
  {
    id: 'gc-j9-optthruput',
    label: 'optthruput',
    hint: "Débit d'abord, pauses plus longues — l'équivalent OpenJ9 de ParallelGC.",
    category: 'gc',
    families: ['openj9'],
    full: true,
    body: '-Xgcpolicy:optthruput',
  },
  {
    id: 'gc-j9-optavgpause',
    label: 'optavgpause',
    hint: "Pauses lissées au détriment du débit — l'équivalent OpenJ9 d'un collecteur concurrent.",
    category: 'gc',
    families: ['openj9'],
    full: true,
    body: '-Xgcpolicy:optavgpause',
  },
  {
    id: 'gc-j9-balanced',
    label: 'balanced',
    hint: "Régions, pensé pour les gros tas — le seul qui ait un intérêt au-delà de ~4 Go sur OpenJ9.",
    category: 'gc',
    families: ['openj9'],
    full: true,
    body: '-Xgcpolicy:balanced',
  },
  {
    id: 'gc-j9-nursery',
    label: '+ Pouponnière large',
    hint: "Agrandit la zone des objets jeunes de gencon. Minecraft en crée énormément qui meurent aussitôt : les laisser mourir sur place évite de les recopier. À adapter au tas alloué.",
    category: 'gc',
    families: ['openj9'],
    full: false,
    body: '-Xmn1g',
  },

  // ── Compilateur ────────────────────────────────────────────────────────────
  {
    id: 'jit-unleashed',
    label: 'JIT débridé',
    hint: "Lève la limite des 8000 bytecodes — HotSpot refuse par défaut de compiler les grosses méthodes, dont plusieurs boucles chaudes de Minecraft.",
    category: 'jit',
    families: ['hotspot'],
    full: true,
    body: [
      '# MaxNodeLimit/NodeLimitFudgeFactor sont obligatoires avec',
      '# -XX:-DontCompileHugeMethods, sinon C2 abandonne sur ces méthodes.',
      '-XX:-DontCompileHugeMethods',
      '-XX:MaxNodeLimit=240000',
      '-XX:NodeLimitFudgeFactor=8000',
    ].join('\n'),
  },
  {
    id: 'jit-codecache',
    label: '+ Code cache large',
    hint: "Le code cache par défaut (240 Mo) peut saturer en cours de session : le JIT arrête alors définitivement de compiler, sans rien dire.",
    category: 'jit',
    families: ['hotspot'],
    full: false,
    body: [
      '-XX:ReservedCodeCacheSize=400M',
      '-XX:NonNMethodCodeHeapSize=12M',
      '-XX:ProfiledCodeHeapSize=194M',
      '-XX:NonProfiledCodeHeapSize=194M',
    ].join('\n'),
  },
  {
    id: 'jit-inline',
    label: '+ Inlining agressif',
    hint: "Fusionne plus d'appels imbriqués dans le code compilé. Utile sur du code très en couches — un mod Fabric traverse souvent dix méthodes triviales avant de faire quoi que ce soit.",
    category: 'jit',
    families: ['hotspot'],
    full: false,
    body: ['-XX:MaxInlineLevel=20', '-XX:MaxInlineSize=45', '-XX:FreqInlineSize=500'].join('\n'),
  },
  {
    id: 'jit-graal',
    label: '+ JIT Graal',
    hint: "Remplace C2 par Graal via JVMCI — mesuré à +5 % ici. N'existe que sur une Oracle GraalVM : ailleurs, la JVM refuse de démarrer.",
    category: 'jit',
    families: ['graal'],
    full: false,
    body: ['-XX:+UnlockExperimentalVMOptions', '-XX:+EnableJVMCI', '-XX:+UseJVMCICompiler'].join('\n'),
  },
]

/** Les jeux qui ont un sens pour cette catégorie ET cette grille. */
export function presetsFor(category: JvmFlagCategory, family: JvmFamily): JvmPreset[] {
  return JVM_PRESETS.filter((p) => p.category === category && familyMatches(p, family))
}

// ── Écarts par rapport au jeu de départ ──────────────────────────────────────

export interface ArgsDiffItem {
  /** Identité du réglage (voir `jvmArgKey`) — sert de clé et de cible aux
   * opérations de rétablissement. */
  key: string
  /** Le drapeau tel qu'il apparaît, pour l'affichage. */
  label: string
  /** Valeur du jeu d'origine, absente si le drapeau n'y était pas. */
  from?: string
  /** Valeur actuelle, absente si le drapeau a été retiré. */
  to?: string
}

export interface ArgsDiff {
  added: ArgsDiffItem[]
  modified: ArgsDiffItem[]
  removed: ArgsDiffItem[]
}

function shownValue(e: JvmArgEntry): string {
  return e.kind === 'boolean' ? (e.value === '+' ? 'activé' : 'désactivé') : e.value
}

/** Ce qui a bougé depuis le jeu de départ. Les commentaires sont ignorés : ils
 * n'ont aucun effet au lancement, et les compter ferait clignoter un "1 écart"
 * pour une ligne d'explication déplacée. */
export function diffAgainstPreset(current: string, presetBody: string): ArgsDiff {
  const byKey = (raw: string) => {
    const map = new Map<string, JvmArgEntry>()
    for (const e of parseArgEntries(raw)) {
      if (e.kind === 'comment') continue
      map.set(jvmArgKey(serializeArgEntry(e)), e)
    }
    return map
  }
  const base = byKey(presetBody)
  const now = byKey(current)
  const diff: ArgsDiff = { added: [], modified: [], removed: [] }

  for (const [key, e] of now) {
    const b = base.get(key)
    if (!b) {
      diff.added.push({ key, label: e.name, to: shownValue(e) })
    } else if (b.value !== e.value) {
      diff.modified.push({ key, label: e.name, from: shownValue(b), to: shownValue(e) })
    }
  }
  for (const [key, e] of base) {
    if (!now.has(key)) diff.removed.push({ key, label: e.name, from: shownValue(e) })
  }
  return diff
}

export function diffCount(d: ArgsDiff): number {
  return d.added.length + d.modified.length + d.removed.length
}

/** Remet un réglage dans l'état du jeu de départ : la valeur d'origine si le
 * jeu le contenait, sa suppression sinon. Une seule fonction pour les trois
 * sortes d'écart — c'est le même geste du point de vue de l'utilisateur. */
export function restoreFromPreset(current: string, presetBody: string, key: string): string {
  const original = parseArgEntries(presetBody).find(
    (e) => e.kind !== 'comment' && jvmArgKey(serializeArgEntry(e)) === key,
  )
  const entries = parseArgEntries(current)
  const idx = entries.findIndex((e) => e.kind !== 'comment' && jvmArgKey(serializeArgEntry(e)) === key)

  if (!original) {
    return serializeArgEntries(idx === -1 ? entries : entries.filter((_, i) => i !== idx))
  }
  const restored: JvmArgEntry = { ...original, id: idx === -1 ? original.id : entries[idx].id }
  if (idx === -1) return serializeArgEntries([...entries, restored])
  return serializeArgEntries(entries.map((e, i) => (i === idx ? restored : e)))
}

/** Applique un jeu au contenu actuel d'une catégorie. */
export function applyPreset(current: string, preset: JvmPreset): string {
  if (preset.full) return preset.body
  const base = current.trimEnd()
  return base ? `${base}\n${preset.body}` : preset.body
}

/** Le collecteur **réellement** appliqué, et d'où il vient.
 *
 * Un sélecteur posé dans les drapeaux l'emporte toujours sur celui de la
 * grille (`merge_jvm_args` retire alors tout le bloc GC généré) — afficher le
 * réglage de la grille sans regarder les drapeaux annoncerait donc souvent un
 * collecteur qui ne tourne pas. */
export function effectiveGc(gcPolicy: string, family: JvmFamily, ...raws: string[]): {
  label: string
  fromFlags: boolean
} {
  const selector = raws.flatMap(parseJvmArgs).filter(isGcSelector).pop()
  if (selector) return { label: gcSelectorLabel(selector), fromFlags: true }
  // Les policies OpenJ9 (`gencon`, `optthruput`...) sont des noms propres en
  // minuscules, seuls les sigles HotSpot se mettent en capitales.
  if (gcPolicy !== 'auto') {
    const label = gcPolicy === 'g1' ? 'G1GC' : gcPolicy === 'zgc' ? 'ZGC' : gcPolicy
    return { label, fromFlags: false }
  }
  return { label: family === 'openj9' ? 'gencon' : 'G1GC ou ZGC', fromFlags: false }
}

export const ARGS_MODES: { id: JvmArgsMode; label: string; sub: string }[] = [
  { id: 'append', label: 'Compléter la base', sub: 'Le tuning du launcher est conservé, vos drapeaux écrasent leurs homologues.' },
  { id: 'replace', label: 'Remplacer la base', sub: 'Seuls -Xmx/-Xms et les library path survivent. Tout le reste vient de vous.' },
]
