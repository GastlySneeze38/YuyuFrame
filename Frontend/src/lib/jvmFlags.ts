import type { JvmArgsMode } from '@/types'

/**
 * Utilitaires de l'écran "Configuration JVM" (pages/JvmConfig).
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

export function isGcSelector(arg: string): boolean {
  const key = jvmArgKey(arg)
  return key.startsWith('-XX:Use') && key.endsWith('GC')
}

export interface JvmArgsLint {
  count: number
  /** Drapeaux définis deux fois dans le champ — le dernier gagne, mais c'est
   * presque toujours une coquille (un preset collé deux fois). */
  duplicates: string[]
  /** Plusieurs sélecteurs de GC : la JVM refuse de démarrer
   * ("Multiple garbage collectors selected"). */
  gcSelectors: string[]
}

export function lintJvmArgs(raw: string): JvmArgsLint {
  const args = parseJvmArgs(raw)
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

export interface JvmPreset {
  id: string
  label: string
  /** Une phrase — ce que ce jeu de flags cherche à obtenir, et sa provenance. */
  hint: string
  /** `true` : jeu complet, remplace le contenu du champ et bascule en mode
   * "replace". `false` : complément, s'ajoute à ce qui est déjà tapé. */
  full: boolean
  mode: JvmArgsMode
  body: string
}

/**
 * Jeux de drapeaux prêts à tester. Tous vérifiés comme acceptés par un
 * Java 25 (`-XX:+PrintFlagsFinal` / démarrage réel) — plusieurs drapeaux des
 * listes communautaires d'origine ont été RETIRÉS parce qu'ils ont disparu
 * des JDK récents et qu'un `-XX` inconnu empêche la JVM de démarrer tout
 * court : `G1ConcRSHotCardLimit` et `G1ConcRefinementServiceIntervalMillis`
 * (brucethemoose), `ShenandoahGCMode=iu` (mode supprimé).
 */
export const JVM_PRESETS: JvmPreset[] = [
  {
    id: 'aikar',
    label: 'Aikar',
    hint: "La référence côté SERVEUR : dimensionnée pour tenir un tick de 50 ms, pas pour une frame de 3 ms. À comparer, pas à prendre par défaut sur un client.",
    full: true,
    mode: 'replace',
    body: [
      '# Aikar (référence serveur) — https://docs.papermc.io/paper/aikars-flags',
      '-XX:+UseG1GC',
      '-XX:+ParallelRefProcEnabled',
      '-XX:MaxGCPauseMillis=200',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:+DisableExplicitGC',
      '-XX:+AlwaysPreTouch',
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
      '-XX:+PerfDisableSharedMem',
      '-XX:MaxTenuringThreshold=1',
    ].join('\n'),
  },
  {
    id: 'brucethemoose',
    label: 'brucethemoose (client)',
    hint: "Le jeu benchmarké CÔTÉ CLIENT : beaucoup de pauses très courtes plutôt que quelques longues, parce qu'une frame ne peut rien amortir.",
    full: true,
    mode: 'replace',
    body: [
      '# brucethemoose/Minecraft-Performance-Flags-Benchmarks (client, G1)',
      '# Drapeaux retirés car absents des JDK récents : G1ConcRSHotCardLimit,',
      '# G1ConcRefinementServiceIntervalMillis.',
      '-XX:+UseG1GC',
      '-XX:+ParallelRefProcEnabled',
      '-XX:MaxGCPauseMillis=37',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:+DisableExplicitGC',
      '-XX:+AlwaysPreTouch',
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
      '-XX:+PerfDisableSharedMem',
      '-XX:MaxTenuringThreshold=1',
      '-XX:+UseNUMA',
      '-XX:-DontCompileHugeMethods',
      '-XX:MaxNodeLimit=240000',
      '-XX:NodeLimitFudgeFactor=8000',
      '-XX:ReservedCodeCacheSize=400M',
      '-XX:NonNMethodCodeHeapSize=12M',
      '-XX:ProfiledCodeHeapSize=194M',
      '-XX:NonProfiledCodeHeapSize=194M',
    ].join('\n'),
  },
  {
    id: 'shenandoah',
    label: 'Shenandoah',
    hint: "Évacuation concurrente : des pauses courtes sans la taxe de débit des barrières de lecture de ZGC. Le mode `iu` a été supprimé, `generational` le remplace.",
    full: true,
    mode: 'replace',
    body: [
      '# Shenandoah (Java 25) — la négation de UseG1GC est OBLIGATOIRE, sinon',
      '# la JVM refuse de démarrer ("Multiple garbage collectors selected").',
      '-XX:-UseG1GC',
      '-XX:+UseShenandoahGC',
      '-XX:ShenandoahGCMode=generational',
      '-XX:+AlwaysPreTouch',
      '-XX:+DisableExplicitGC',
      '-XX:+PerfDisableSharedMem',
    ].join('\n'),
  },
  {
    id: 'parallel',
    label: 'ParallelGC',
    hint: "Débit brut maximum, pauses longues assumées. Le point de comparaison qui dit combien coûtent réellement les GC concurrents.",
    full: true,
    mode: 'replace',
    body: [
      '# ParallelGC — plancher de comparaison "débit brut"',
      '-XX:-UseG1GC',
      '-XX:+UseParallelGC',
      '-XX:+AlwaysPreTouch',
      '-XX:+DisableExplicitGC',
      '-XX:+PerfDisableSharedMem',
    ].join('\n'),
  },
  {
    id: 'graal-jit',
    label: '+ JIT Graal',
    hint: "Active le compilateur Graal via JVMCI — n'a d'effet que sur une Oracle GraalVM (mesuré à +5 % ici). Ignoré ailleurs, mais refusé au démarrage sur certaines JVM.",
    full: false,
    mode: 'append',
    body: [
      '# JIT Graal (Oracle GraalVM uniquement)',
      '-XX:+UnlockExperimentalVMOptions',
      '-XX:+EnableJVMCI',
      '-XX:+UseJVMCICompiler',
    ].join('\n'),
  },
  {
    id: 'jit-unleashed',
    label: '+ JIT débridé',
    hint: "Lève la limite des 8000 bytecodes (HotSpot refuse de compiler les grosses méthodes, dont plusieurs boucles chaudes de Minecraft) et agrandit le code cache pour qu'il ne sature pas en cours de session.",
    full: false,
    mode: 'append',
    body: [
      '# Débride le JIT — MaxNodeLimit/NodeLimitFudgeFactor sont obligatoires',
      '# avec -XX:-DontCompileHugeMethods, sinon C2 abandonne sur ces méthodes.',
      '-XX:-DontCompileHugeMethods',
      '-XX:MaxNodeLimit=240000',
      '-XX:NodeLimitFudgeFactor=8000',
      '-XX:ReservedCodeCacheSize=400M',
      '-XX:NonNMethodCodeHeapSize=12M',
      '-XX:ProfiledCodeHeapSize=194M',
      '-XX:NonProfiledCodeHeapSize=194M',
    ].join('\n'),
  },
  {
    id: 'gc-log',
    label: '+ Journal GC',
    hint: "Écrit chaque pause dans gc.log à côté du jeu — c'est ce qui permet de dire si une chute de FPS vient bien du GC plutôt que du meshing de chunks.",
    full: false,
    mode: 'append',
    body: [
      '# Journal des pauses GC (fichier gc.log dans le dossier de travail)',
      '-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=10M',
    ].join('\n'),
  },
]
