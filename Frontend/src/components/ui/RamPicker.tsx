import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { formatRam } from '@/lib/format'
import type { Loader, SystemMemoryInfo } from '@/types'

type Severity = 'ok' | 'orange' | 'red'

interface RamGroup {
  id: string
  /** Libellé du bouton visible sans survol. */
  label: string
  /** Valeurs en Mo proposées au clic (1 seule = pas de sous-liste). */
  values: number[]
  /** Valeur appliquée par un clic direct sur le bouton (sans passer par le sous-menu). */
  defaultValue: number
}

/**
 * 3 paliers fixes, sous-valeurs au clic pour les paliers 2 et 3 (voir
 * `expandedGroup`) — remplace l'ancienne liste plate de 5 boutons (rework
 * RAM, suite Phase 4). Le palier
 * "8 Go" n'a pas sa place à part (il ne servait à rien en tant que bouton
 * isolé) : fondu dans le dernier palier "5 Go+". La RAM personnalisée des
 * Paramètres (`customRamMb`), quand définie, apparaît en 4e bouton
 * INDÉPENDANT à côté des 3 paliers fixes — pas une sous-valeur de "5 Go+",
 * un palier à part entière, sans sous-menu (une seule valeur).
 */
function buildGroups(customRamMb: number | null): RamGroup[] {
  const groups: RamGroup[] = [
    { id: 'g1', label: '2 Go', values: [2048], defaultValue: 2048 },
    { id: 'g2', label: '3-4 Go', values: [3072, 4096], defaultValue: 4096 },
    { id: 'g3', label: '5 Go+', values: [5120, 6144, 7168, 8192], defaultValue: 6144 },
  ]
  if (customRamMb) {
    groups.push({ id: 'g4', label: formatRam(customRamMb), values: [customRamMb], defaultValue: customRamMb })
  }
  return groups
}

/**
 * Code couleur à deux paliers (item 4.5, audit jvm-config) au lieu d'un
 * simple seuil binaire "risky" :
 * - rouge : OOM quasi certain (dépasse la RAM dispo réelle) OU part
 *   déraisonnable de la RAM système (ex: 14 Go sur une machine à 16 Go) —
 *   dans les deux cas ça ne laisse presque plus rien à l'OS/au reste des apps.
 * - orange : au-delà du palier recommandé pour la RAM dispo détectée, mais
 *   pas encore dans la zone rouge — "possible mais problème probable".
 * Chaque cas a sa propre raison affichée dans le tooltip, pas juste la
 * couleur (voir doc jvm-config, §4).
 */
function severityFor(mb: number, info: SystemMemoryInfo): { severity: Severity; reason: string | null } {
  const nearOomMb = Math.max(1024, info.available_mb - 512)
  const unreasonableShareMb = Math.round(info.total_mb * 0.85)

  if (mb > nearOomMb) {
    return { severity: 'red', reason: `Ne laisse presque plus de RAM disponible sur cette machine (~${formatRam(info.available_mb)} libres actuellement) — crash par manque de mémoire quasi certain.` }
  }
  if (mb >= unreasonableShareMb) {
    return { severity: 'red', reason: `Dépasse 85% de la RAM totale du système (${formatRam(info.total_mb)}) — ne laisse presque plus rien à l'OS et au reste des applications.` }
  }
  const recommendedMb = Math.max(1024, info.available_mb - 1536)
  if (mb > recommendedMb) {
    return { severity: 'orange', reason: `Dépasse la RAM disponible recommandée sur cette machine (~${formatRam(recommendedMb)} conseillé) — le jeu risque de mettre du temps à démarrer ou de ralentir tout le système.` }
  }
  return { severity: 'ok', reason: null }
}

interface Recommendation {
  groupId: string
  /** Valeurs considérées recommandées DANS ce groupe — pas forcément tout le
   * groupe (voir plus bas pour "5 Go+"). */
  values: number[]
}

/**
 * Recommande une PLAGE de valeurs, pas une seule (retour utilisateur : sur
 * les grosses configs, un seul chiffre isolé perdait son sens) — palier
 * "2 Go" = 1 seule valeur de toute façon → inchangé, palier "3-4 Go" = tout
 * le palier recommandé, palier "5 Go+" = 5/6/7 Go recommandés SAUF quand le
 * backend demande explicitement 8 Go (`target === 8192`, uniquement le
 * palier "70+ mods" côté Rust — voir `mod_tier_mb` dans `info.rs`) : dans ce
 * cas précis, 8 Go devient la SEULE recommandation. "Sauf si la RAM dispo ne
 * le permet pas" est déjà géré côté backend : `suggested_mb` est plafonné
 * par la RAM réellement disponible AVANT d'arriver ici — si 8 Go n'est pas
 * jouable, le backend renvoie déjà une valeur plus basse et `target` ne vaut
 * alors jamais 8192, ce qui retombe naturellement sur la plage 5/6/7 Go.
 *
 * `target` (`suggested_mb`, backend) situe d'abord le PALIER le plus proche
 * — potentiellement hors des valeurs exactes si la RAM dispo est très
 * contrainte — puis applique la règle de plage propre à ce palier.
 */
function computeRecommendation(groups: RamGroup[], target: number): Recommendation {
  let best = { group: groups[0], diff: Infinity }
  for (const group of groups) {
    for (const value of group.values) {
      const diff = Math.abs(value - target)
      if (diff < best.diff) best = { group, diff }
    }
  }
  if (best.group.id !== 'g3') {
    return { groupId: best.group.id, values: best.group.values }
  }
  const values = target === 8192 ? [8192] : best.group.values.filter((v) => v !== 8192)
  return { groupId: best.group.id, values }
}

export interface RamStatus {
  /** `value` correspond exactement à l'une des valeurs proposées par un
   * palier — sinon, du point de vue de ce picker, rien n'est "sélectionné"
   * (même si `value` est techniquement un nombre valide). */
  isKnownTier: boolean
  /** `value` correspond à la recommandation calculée pour ce loader/ces mods. */
  isRecommended: boolean
}

export function RamPicker({ value, onChange, loader, modCount, onStatusChange }: {
  value: number
  onChange: (v: number) => void
  loader?: Loader
  modCount?: number
  /** Notifié à chaque changement pertinent — sert aux appelants qui veulent
   * bloquer la validation tant qu'aucun palier n'est choisi, ou afficher un
   * avertissement quand le choix s'écarte de la recommandation. */
  onStatusChange?: (status: RamStatus) => void
}) {
  // Aucune détection matérielle n'existait avant — le launcher proposait les
  // mêmes paliers RAM à tout le monde sans regarder la machine, faisant
  // "galérer" au lancement les PC modestes (RAM dispo insuffisante pour
  // l'OS + le reste une fois le tas Java alloué). On avertit visuellement
  // sans bloquer, certains utilisateurs ferment volontairement d'autres
  // apps avant de jouer.
  const [memInfo, setMemInfo] = useState<SystemMemoryInfo | null>(null)
  const customRamMb = useStore((s) => s.customRamMb)
  // Au clic, pas au survol (retour utilisateur : le survol était
  // impraticable, positionnement de la popup peu fiable selon l'endroit où
  // le bouton se trouve dans la modal). Un seul palier ouvert à la fois, la
  // rangée de sous-valeurs s'affiche dans le flux normal (pas en overlay
  // absolu) juste sous les boutons, donc jamais coupée par un conteneur
  // `overflow` parent.
  const [expandedGroup, setExpandedGroup] = useState<string | null>(null)

  useEffect(() => {
    api.system.memoryInfo(loader, modCount)
      .then(setMemInfo)
      .catch(() => {})
  }, [loader, modCount])

  const groups = buildGroups(customRamMb)
  const current = memInfo ? severityFor(value, memInfo) : { severity: 'ok' as Severity, reason: null }
  const recommended = memInfo ? computeRecommendation(groups, memInfo.suggested_mb) : null
  const expanded = groups.find((g) => g.id === expandedGroup) ?? null
  const isKnownTier = groups.some((g) => g.values.includes(value))
  const valueIsRecommended = recommended?.values.includes(value) ?? false

  useEffect(() => {
    onStatusChange?.({ isKnownTier, isRecommended: valueIsRecommended })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isKnownTier, valueIsRecommended])

  const handleGroupClick = (group: RamGroup) => {
    if (group.values.length > 1) {
      setExpandedGroup((g) => (g === group.id ? null : group.id))
    } else {
      onChange(group.defaultValue)
      setExpandedGroup(null)
    }
  }

  const handleSubValueClick = (v: number) => {
    onChange(v)
    setExpandedGroup(null)
  }

  return (
    <div>
      <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">RAM</label>
      <div className="flex gap-1.5 mt-1">
        {groups.map((group) => {
          const isSelected = group.values.includes(value)
          const isRecommended = recommended?.groupId === group.id
          const isExpandable = group.values.length > 1
          const isOpen = expandedGroup === group.id
          const { severity, reason } = memInfo ? severityFor(group.defaultValue, memInfo) : { severity: 'ok' as Severity, reason: null }

          return (
            <button
              key={group.id}
              onClick={() => handleGroupClick(group)}
              title={reason ?? (isRecommended ? 'Recommandé pour cette configuration' : undefined)}
              className={`relative flex items-center gap-1 rounded-xl text-xs font-semibold transition-all duration-150 h-[34px] px-2.5 ${
                isSelected
                  ? 'bg-[rgba(75,63,207,0.35)] border border-[rgba(75,63,207,0.7)] text-[rgba(255,255,255,0.95)]'
                  // "Ouvert" (sous-liste dépliée) est un état DIFFÉRENT de
                  // "sélectionné" — même style que la sélection ici créait un
                  // conflit visuel (retour utilisateur) : déplier "5 Go+" le
                  // faisait paraître choisi alors que "2 Go" l'était
                  // réellement, les deux boutons avaient l'air actifs à la fois.
                  : isOpen
                    ? 'bg-[rgba(255,255,255,0.07)] border border-[rgba(255,255,255,0.3)] text-[rgba(255,255,255,0.8)]'
                    : severity === 'red'
                      ? 'bg-[rgba(0,0,0,0.35)] border border-[rgba(220,60,50,0.55)] text-[rgba(240,110,100,0.7)]'
                      : severity === 'orange'
                        ? 'bg-[rgba(0,0,0,0.35)] border border-[rgba(220,140,40,0.5)] text-[rgba(240,180,90,0.6)]'
                        : isRecommended
                          ? 'bg-[rgba(0,0,0,0.35)] border border-[rgba(70,200,120,0.55)] text-[rgba(120,220,150,0.85)]'
                          : 'bg-[rgba(0,0,0,0.35)] border border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.35)]'
              }`}
            >
              {group.label}
              {isExpandable && (
                <svg viewBox="0 0 10 6" fill="currentColor" width={8} height={5} className={`flex-shrink-0 transition-transform duration-150 opacity-50 ${isOpen ? 'rotate-180' : 'rotate-0'}`}>
                  <path d="M0 0l5 6 5-6z" />
                </svg>
              )}
              {isRecommended && !isSelected && (
                <span className="absolute -top-1 -right-1 h-1.5 w-1.5 rounded-full bg-[rgba(70,200,120,0.9)]" />
              )}
            </button>
          )
        })}
      </div>

      {expanded && (
        <div className="flex flex-wrap gap-1.5 mt-1.5 p-1.5 rounded-xl bg-[rgba(0,0,0,0.25)] border border-[rgba(255,255,255,0.06)]">
          {expanded.values.map((v) => {
            const sub = memInfo ? severityFor(v, memInfo) : { severity: 'ok' as Severity, reason: null }
            const subSelected = value === v
            const subRecommended = recommended?.groupId === expanded.id && recommended.values.includes(v)
            return (
              <button
                key={v}
                onClick={() => handleSubValueClick(v)}
                title={sub.reason ?? (subRecommended ? 'Recommandé pour cette configuration' : undefined)}
                className={`rounded-lg text-[11px] font-semibold whitespace-nowrap transition-all duration-150 h-[28px] px-3 ${
                  subSelected
                    ? 'bg-[rgba(75,63,207,0.4)] text-white'
                    : sub.severity === 'red'
                      ? 'text-[rgba(240,110,100,0.75)] hover:bg-[rgba(220,60,50,0.15)]'
                      : sub.severity === 'orange'
                        ? 'text-[rgba(240,180,90,0.7)] hover:bg-[rgba(220,140,40,0.15)]'
                        : subRecommended
                          ? 'text-[rgba(120,220,150,0.85)] hover:bg-[rgba(70,200,120,0.15)]'
                          : 'text-[rgba(255,255,255,0.5)] hover:bg-[rgba(255,255,255,0.08)]'
                }`}
              >
                {formatRam(v)}
              </button>
            )
          })}
        </div>
      )}

      {/* Une seule ligne d'aide à la fois, la plus utile pour la situation
          actuelle — priorité : danger réel (RAM machine) > choix valide mais
          pas la recommandation. Le cas "rien de sélectionné" n'a PAS sa
          propre ligne ici : la modale appelante l'affiche déjà au-dessus de
          son bouton de validation (voir `onStatusChange`) — en avoir deux
          faisait doublon pour rien (retour utilisateur). */}
      {current.reason ? (
        <p className={`text-[10px] mt-1 ${current.severity === 'red' ? 'text-[rgba(240,110,100,0.85)]' : 'text-[rgba(240,180,90,0.75)]'}`}>
          {current.severity === 'red' ? '⛔' : '⚠'} {current.reason}
        </p>
      ) : !valueIsRecommended && recommended ? (
        <p className="text-[10px] mt-1 text-[rgba(120,220,150,0.75)]">
          💡 Recommandé pour cette configuration : {recommended.values.map(formatRam).join(' / ')}.
        </p>
      ) : null}
    </div>
  )
}
