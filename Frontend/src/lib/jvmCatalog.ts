import type { JvmFlagCategory } from '@/types'
import type { JvmFamily } from './jvmFlags'

/**
 * Catalogue documenté des drapeaux JVM.
 *
 * Il existe pour une seule raison : personne ne retient à quoi sert
 * `-XX:G1MixedGCLiveThresholdPercent`, ni ce qu'il vaut par défaut, ni dans
 * quelle plage il a un sens. Sans ça, régler une config revient à recopier des
 * listes trouvées ailleurs sans savoir ce qu'on change — exactement ce qui rend
 * l'exercice décourageant.
 *
 * Il porte aussi le **classement** : c'est lui qui sait qu'un drapeau appartient
 * au ramasse-miettes ou au compilateur, ce qui évite d'imposer ce tri à
 * l'utilisateur (voir `categoryOf`).
 *
 * Les valeurs par défaut sont celles d'un HotSpot récent (Java 17-25). Quand
 * elles dépendent de la machine ou du tas, c'est écrit "ergonomique" plutôt
 * qu'un chiffre inventé — un défaut faux serait pire que pas de défaut.
 */
export interface JvmFlagDoc {
  name: string
  /** `boolean` : s'écrit `-XX:+Nom` / `-XX:-Nom`. `value` : prend une valeur. */
  kind: 'boolean' | 'value'
  category: JvmFlagCategory
  families: JvmFamily[]
  /** Valeur appliquée quand le drapeau n'est pas posé. */
  default: string
  /** Une phrase : ce que ça fait, et l'effet du réglage. */
  hint: string
  /** Ordre de grandeur utile, quand il y en a un. */
  range?: string
  /** Signalé dans l'interface : ces drapeaux exigent
   * `-XX:+UnlockExperimentalVMOptions` pour être acceptés. */
  experimental?: boolean
}

const HOTSPOT: JvmFamily[] = ['hotspot', 'graal']

export const JVM_FLAG_DOCS: JvmFlagDoc[] = [
  // ── Moteur ─────────────────────────────────────────────────────────────────
  {
    name: '-Xmx', kind: 'value', category: 'jvm', families: ['hotspot', 'graal', 'openj9'],
    default: '1/4 de la RAM système', range: 'ex. 6g, 6144m',
    hint: "Taille maximale du tas. Le launcher la calcule déjà depuis la RAM de l'instance — ne la poser ici que pour la forcer.",
  },
  {
    name: '-Xms', kind: 'value', category: 'jvm', families: ['hotspot', 'graal', 'openj9'],
    default: '1/64 de la RAM système', range: 'ex. 6g',
    hint: "Tas alloué au démarrage. L'égaler à -Xmx évite les redimensionnements en cours de partie.",
  },
  {
    name: '-Xss', kind: 'value', category: 'jvm', families: ['hotspot', 'graal', 'openj9'],
    default: '1m', range: '1m à 4m',
    hint: "Pile par thread. À augmenter seulement si un mod à récursion profonde déclenche des StackOverflowError.",
  },
  {
    name: '-XX:AlwaysPreTouch', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Réserve physiquement tout le tas au démarrage : plus de défauts de page pendant le jeu, mais un lancement plus long.",
  },
  {
    name: '-XX:DisableExplicitGC', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Ignore les System.gc() appelés par les mods, qui déclenchent sinon une collecte complète en plein jeu.",
  },
  {
    name: '-XX:PerfDisableSharedMem', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Supprime le fichier de statistiques que la JVM écrit en continu — source de micro-blocages quand le disque est occupé.",
  },
  {
    name: '-XX:UseStringDeduplication', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Fusionne les chaînes identiques pendant les collectes. Gagne de la mémoire, coûte un peu de temps GC.",
  },
  {
    name: '-XX:UseNUMA', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Répartit le tas selon la topologie mémoire du processeur. N'a d'effet que sur multi-socket ou Ryzen multi-CCX.",
  },
  {
    name: '-XX:UseCompressedOops', kind: 'boolean', category: 'jvm', families: ['hotspot', 'graal', 'openj9'],
    default: 'activé sous 32 Go de tas',
    hint: "Références mémoire sur 32 bits au lieu de 64. Déjà actif par défaut : à ne toucher que pour le désactiver.",
  },
  {
    name: '-XX:ActiveProcessorCount', kind: 'value', category: 'jvm', families: HOTSPOT,
    default: 'tous les cœurs', range: 'nombre de cœurs',
    hint: "Nombre de cœurs que la JVM croit avoir. Sert à réserver des cœurs au reste de la machine (OBS, Discord).",
  },
  {
    name: '-XX:UseLargePages', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Pages mémoire de 2 Mo au lieu de 4 Ko : moins de défauts de cache d'adresses sur un gros tas. Exige le privilège Windows « Verrouiller les pages en mémoire », sinon la JVM avertit et continue sans.",
  },
  {
    name: '-XX:UseThreadPriorities', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'activé',
    hint: "Laisse la JVM appliquer ses priorités de threads. Déjà actif : n'a d'intérêt qu'avec ThreadPriorityPolicy.",
  },
  {
    name: '-XX:ThreadPriorityPolicy', kind: 'value', category: 'jvm', families: HOTSPOT,
    default: '0', range: '0 ou 1',
    hint: "À 1, les priorités de threads sont réellement transmises à l'OS — le rendu passe devant les tâches de fond. Sous Linux sans droits root, la JVM avertit et l'ignore.",
  },
  {
    name: '-Xdisableexplicitgc', kind: 'boolean', category: 'jvm', families: ['openj9'],
    default: 'désactivé',
    hint: "Équivalent OpenJ9 de -XX:+DisableExplicitGC : les System.gc() des mods sont ignorés.",
  },

  // ── Ramasse-miettes : sélecteurs ───────────────────────────────────────────
  {
    name: '-XX:UseG1GC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'activé',
    hint: "Collecteur par défaut : bon compromis débit/pauses, et le seul que tous les réglages -XX:G1* concernent.",
  },
  {
    name: '-XX:UseZGC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé',
    hint: "Pauses sous la milliseconde, payées par une barrière sur chaque lecture de référence — mesuré ici à ~26 % de débit en moins.",
  },
  {
    name: '-XX:UseShenandoahGC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé',
    hint: "Évacuation concurrente : pauses courtes sans la taxe de débit de ZGC. Exige -XX:-UseG1GC à côté.",
  },
  {
    name: '-XX:UseParallelGC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé',
    hint: "Débit brut maximum, pauses longues assumées. Utile comme plancher de comparaison.",
  },
  {
    name: '-XX:UseSerialGC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé',
    hint: "Un seul thread de collecte. Sur un petit tas et peu de cœurs, il bat parfois G1 — ailleurs, non.",
  },
  {
    name: '-Xlog', kind: 'value', category: 'gc', families: HOTSPOT,
    default: 'aucun journal', range: 'gc*:file=gc.log:time,uptime,level,tags',
    hint: "Journalise l'activité de la JVM. Avec `gc*`, écrit chaque pause dans un fichier — le seul moyen de dire si une chute de FPS vient du ramasse-miettes.",
  },
  {
    name: '-XX:UseEpsilonGC', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé', experimental: true,
    hint: "Ne collecte rien du tout : le jeu plante dès que le tas est plein. Sert uniquement à mesurer ce que le ramasse-miettes coûte, en le retirant.",
  },
  {
    name: '-Xmn', kind: 'value', category: 'gc', families: ['hotspot', 'graal', 'openj9'],
    default: 'ergonomique', range: 'ex. 1g',
    hint: "Taille fixe de la zone des objets jeunes. Fige ce que le collecteur ajustait tout seul — à ne poser que si on sait pourquoi.",
  },
  {
    name: '-Xgcpolicy', kind: 'value', category: 'gc', families: ['openj9'],
    default: 'gencon', range: 'gencon · optthruput · optavgpause · balanced',
    hint: "Choix du ramasse-miettes OpenJ9. gencon convient aux objets à durée de vie courte de Minecraft.",
  },

  // ── Ramasse-miettes : réglages G1 ──────────────────────────────────────────
  {
    name: '-XX:MaxGCPauseMillis', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '200', range: 'client 30-50 · serveur 150-200',
    hint: "Durée de pause visée. Plus bas = pauses plus courtes mais plus fréquentes. Une frame à 300 fps dure 3 ms : elle n'amortit rien.",
  },
  {
    name: '-XX:G1NewSizePercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '5', range: '20 à 40', experimental: true,
    hint: "Part minimale du tas réservée aux objets jeunes. Trop bas, la JVM collecte sans arrêt ; trop haut, les pauses s'allongent.",
  },
  {
    name: '-XX:G1MaxNewSizePercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '60', range: '40 à 60', experimental: true,
    hint: "Plafond de la même zone. Le laisser large donne de la marge quand le jeu génère beaucoup d'objets d'un coup.",
  },
  {
    name: '-XX:G1HeapRegionSize', kind: 'value', category: 'gc', families: HOTSPOT,
    default: 'ergonomique (1M à 32M)', range: '8M pour 6 Go · 16M au-delà',
    hint: "Taille des blocs que G1 collecte. Trop petits sur un gros tas, ils se comptent par milliers et ralentissent chaque pause.",
  },
  {
    name: '-XX:G1ReservePercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '10', range: '15 à 20',
    hint: "Marge gardée libre pour absorber un pic d'allocation. L'augmenter éloigne le risque de collecte complète.",
  },
  {
    name: '-XX:G1HeapWastePercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '5', range: '5 à 20',
    hint: "Déchet toléré avant de relancer un cycle. Plus haut = moins de collectes, au prix de mémoire gaspillée.",
  },
  {
    name: '-XX:G1MixedGCCountTarget', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '8', range: '3 à 8',
    hint: "Sur combien de collectes étaler le nettoyage des vieux objets. Moins nombreuses mais plus longues, ou l'inverse.",
  },
  {
    name: '-XX:InitiatingHeapOccupancyPercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '45',
    hint: "Remplissage du tas qui déclenche un cycle. Attention : G1 l'ajuste ensuite tout seul, ce n'est qu'une amorce.",
  },
  {
    name: '-XX:G1MixedGCLiveThresholdPercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '85', range: '85 à 90', experimental: true,
    hint: "Au-delà de ce taux d'occupation, un bloc est jugé trop plein pour valoir la peine d'être recyclé.",
  },
  {
    name: '-XX:G1RSetUpdatingPauseTimePercent', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '10', range: '0 à 10',
    hint: "Part de la pause consacrée à la mise à jour des tables de références. À 0, ce travail passe entièrement en tâche de fond.",
  },
  {
    name: '-XX:ParallelRefProcEnabled', kind: 'boolean', category: 'gc', families: HOTSPOT,
    default: 'désactivé',
    hint: "Traite les références faibles en parallèle. Minecraft en crée beaucoup (chunks, textures) : gain net sur les pauses.",
  },
  {
    name: '-XX:SurvivorRatio', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '8', range: '8 à 32',
    hint: "Rapport entre zone d'allocation et zones de survie. Élevé = les objets éphémères meurent avant d'être recopiés.",
  },
  {
    name: '-XX:MaxTenuringThreshold', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '15', range: '1 à 15',
    hint: "Nombre de collectes qu'un objet doit survivre avant d'être promu. À 1, on arrête de recopier ce qui va durer.",
  },
  {
    name: '-XX:ParallelGCThreads', kind: 'value', category: 'gc', families: HOTSPOT,
    default: 'ergonomique', range: 'moitié des cœurs',
    hint: "Threads utilisés pendant une pause. En réduire laisse des cœurs au rendu, mais rallonge chaque pause.",
  },
  {
    name: '-XX:ConcGCThreads', kind: 'value', category: 'gc', families: HOTSPOT,
    default: 'ergonomique', range: '1/4 des cœurs',
    hint: "Threads travaillant en tâche de fond pendant le jeu. C'est eux qui volent des cycles au rendu.",
  },
  {
    name: '-XX:ShenandoahGCMode', kind: 'value', category: 'gc', families: HOTSPOT,
    default: 'satb', range: 'satb · generational',
    hint: "Stratégie de Shenandoah. Le mode `iu` a été supprimé des JDK récents : `generational` le remplace.",
  },
  {
    name: '-XX:ZAllocationSpikeTolerance', kind: 'value', category: 'gc', families: HOTSPOT,
    default: '2.0', range: '2.0 à 5.0',
    hint: "Marge que ZGC garde pour absorber un pic d'allocation. À monter si des \"allocation stall\" apparaissent.",
  },

  // ── Compilateur ────────────────────────────────────────────────────────────
  {
    name: '-XX:DontCompileHugeMethods', kind: 'boolean', category: 'jit', families: HOTSPOT,
    default: 'activé',
    hint: "Activé, HotSpot refuse de compiler les méthodes de plus de 8000 bytecodes — dont plusieurs boucles chaudes de Minecraft. Le désactiver exige MaxNodeLimit.",
  },
  {
    name: '-XX:MaxNodeLimit', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '80000', range: '240000 avec -DontCompileHugeMethods',
    hint: "Taille maximale du graphe que le compilateur accepte de traiter. Sans l'augmenter, il abandonne sur les grosses méthodes.",
  },
  {
    name: '-XX:NodeLimitFudgeFactor', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '2000', range: '8000 avec MaxNodeLimit=240000',
    hint: "Marge de sécurité autour de la limite ci-dessus. Se règle proportionnellement à elle.",
  },
  {
    name: '-XX:ReservedCodeCacheSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '240m', range: '400m à 512m',
    hint: "Place réservée au code compilé. Saturée, la JVM arrête définitivement de compiler pour la session — sans rien dire.",
  },
  {
    name: '-XX:NonNMethodCodeHeapSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: 'ergonomique', range: '12m',
    hint: "Sous-partie du code cache réservée au code interne de la JVM. Se répartit avec les deux suivantes.",
  },
  {
    name: '-XX:ProfiledCodeHeapSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: 'ergonomique', range: '194m',
    hint: "Zone du code cache pour le code compilé rapidement et instrumenté (niveau intermédiaire).",
  },
  {
    name: '-XX:NonProfiledCodeHeapSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: 'ergonomique', range: '194m',
    hint: "Zone du code cache pour le code pleinement optimisé — celui qui fait tourner le jeu une fois chaud.",
  },
  {
    name: '-XX:MaxInlineLevel', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '15', range: '15 à 20',
    hint: "Profondeur d'appels que le compilateur fusionne. Utile sur du code très en couches comme les mods Fabric.",
  },
  {
    name: '-XX:MaxInlineSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '35', range: '35 à 60',
    hint: "Taille maximale (en bytecodes) d'une méthode peu appelée pour être fusionnée dans son appelant.",
  },
  {
    name: '-XX:FreqInlineSize', kind: 'value', category: 'jit', families: HOTSPOT,
    default: '325', range: '325 à 500',
    hint: "Même chose pour une méthode très appelée — la limite qui compte vraiment sur une boucle de rendu.",
  },
  {
    name: '-XX:TieredCompilation', kind: 'boolean', category: 'jit', families: HOTSPOT,
    default: 'activé',
    hint: "Compilation par paliers : rapide d'abord, optimisée ensuite. Le désactiver rallonge beaucoup le démarrage.",
  },
  {
    name: '-XX:UnlockExperimentalVMOptions', kind: 'boolean', category: 'jvm', families: HOTSPOT,
    default: 'désactivé',
    hint: "Autorise les drapeaux marqués expérimentaux. Sans lui, la JVM refuse de démarrer si l'un d'eux est présent.",
  },
  {
    name: '-XX:EnableJVMCI', kind: 'boolean', category: 'jit', families: ['graal'],
    default: 'désactivé', experimental: true,
    hint: "Active l'interface qui permet de brancher un compilateur écrit en Java. Préalable au JIT Graal.",
  },
  {
    name: '-XX:UseJVMCICompiler', kind: 'boolean', category: 'jit', families: ['graal'],
    default: 'désactivé', experimental: true,
    hint: "Remplace C2 par Graal — mesuré à +5 % ici. N'existe que sur une Oracle GraalVM : ailleurs, la JVM ne démarre pas.",
  },
]

const BY_NAME = new Map(JVM_FLAG_DOCS.map((d) => [d.name, d]))

/** Fiche d'un drapeau, à partir de son nom sans signe ni valeur. */
export function flagDoc(name: string): JvmFlagDoc | undefined {
  return BY_NAME.get(name)
}

/**
 * Catégorie d'un drapeau — le catalogue d'abord, sinon des règles sur le nom.
 *
 * C'est ce qui dispense l'utilisateur de savoir si `-XX:+UseNUMA` relève du
 * moteur ou du ramasse-miettes : il ajoute un drapeau, il atterrit au bon
 * endroit. Se tromper d'onglet n'a aucune conséquence sur le lancement (les
 * trois catégories sont concaténées), mais retrouver son drapeau ensuite en a
 * une.
 */
export function categoryOf(name: string): JvmFlagCategory {
  const doc = BY_NAME.get(name)
  if (doc) return doc.category

  const bare = name.replace(/^-XX:/, '')
  if (/^(G1|Z|Shenandoah|Epsilon)/.test(bare)) return 'gc'
  if (/GC(Threads|Time|Pause|Interval|Count|Heap)?$/i.test(bare)) return 'gc'
  if (/^(Use\w*GC|SurvivorRatio|MaxTenuringThreshold|NewRatio|InitiatingHeapOccupancyPercent)$/.test(bare)) return 'gc'
  if (name.startsWith('-Xgcpolicy') || name.startsWith('-Xmn')) return 'gc'
  if (name.startsWith('-Xlog')) return 'gc'

  if (/(Compile|Compiler|Inline|CodeCache|CodeHeap|NodeLimit|JVMCI|Graal|Tiered|Nmethod|OSR)/i.test(bare)) return 'jit'
  if (name.startsWith('-Xjit') || name.startsWith('-Xint') || name.startsWith('-Xcomp')) return 'jit'

  return 'jvm'
}

/** Fiches proposables pour une catégorie et une famille de JVM. */
export function docsFor(category: JvmFlagCategory, family: JvmFamily): JvmFlagDoc[] {
  return JVM_FLAG_DOCS.filter((d) => d.category === category && d.families.includes(family))
}

/** Recherche libre dans tout le catalogue — nom ET explication, pour qu'on
 * puisse trouver « pause » sans connaître `MaxGCPauseMillis`. */
export function searchDocs(query: string, family: JvmFamily): JvmFlagDoc[] {
  const q = query.trim().toLowerCase()
  const pool = JVM_FLAG_DOCS.filter((d) => d.families.includes(family))
  if (!q) return pool
  return pool.filter((d) => `${d.name} ${d.hint} ${d.range ?? ''}`.toLowerCase().includes(q))
}
