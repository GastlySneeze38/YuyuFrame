import { useState } from 'react'
import {
  applyPreset, diffAgainstPreset, diffCount, parseJvmArgs, presetsFor, restoreFromPreset,
  type ArgsDiff, type JvmFamily, type JvmPreset,
} from '@/lib/jvmFlags'
import type { JvmFlagCategory } from '@/types'

/**
 * Le jeu de drapeaux dont la catégorie est issue, et ce qu'on en a changé.
 *
 * Deux problèmes réglés ici. D'abord, un jeu ne s'applique plus à l'aveugle :
 * sa fiche dit d'où il vient, ce qu'il cherche à obtenir, et ce que son
 * application va ajouter / modifier / supprimer — avant de cliquer. Ensuite,
 * il ne disparaît pas une fois appliqué : la config reste marquée « basée
 * sur X » et affiche en permanence les écarts introduits depuis, chacun
 * rétablissable isolément. Sans ça, on ne savait plus si ce qu'on regardait
 * était le jeu d'origine ou trois modifications empilées dessus.
 */
export function BasePanel({ category, family, value, baseId, onChange, onBaseChange }: {
  category: JvmFlagCategory
  family: JvmFamily
  /** Contenu actuel de la catégorie. */
  value: string
  /** Identifiant du jeu de départ, `null` si la catégorie n'en a pas. */
  baseId: string | null
  onChange: (v: string) => void
  onBaseChange: (id: string | null) => void
}) {
  const [browsing, setBrowsing] = useState(false)
  const [expanded, setExpanded] = useState<string | null>(null)

  const presets = presetsFor(category, family)
  const base = presets.find((p) => p.id === baseId) ?? null
  const diff = base ? diffAgainstPreset(value, base.body) : null
  const n = diff ? diffCount(diff) : 0

  const apply = (preset: JvmPreset) => {
    onChange(applyPreset(preset.full ? '' : value, preset))
    // Un complément ne définit pas d'où part la catégorie : il s'ajoute à ce
    // qui est déjà là. Seul un jeu complet devient la base.
    if (preset.full) onBaseChange(preset.id)
    setBrowsing(false)
    setExpanded(null)
  }

  if (presets.length === 0) {
    return (
      <p className="text-[11px] text-[rgba(255,255,255,0.28)]">
        Aucun jeu de drapeaux vérifié pour cette famille de JVM dans cette catégorie.
      </p>
    )
  }

  // ── Choix d'un jeu ─────────────────────────────────────────────────────────
  if (browsing || !base) {
    const full = presets.filter((p) => p.full)
    const addons = presets.filter((p) => !p.full)
    return (
      <div className="flex flex-col gap-2 rounded-2xl border border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.03)] p-3">
        <div className="flex items-center justify-between gap-2">
          <div>
            <p className="text-[12px] font-bold text-[rgba(255,255,255,0.8)]">
              {base ? 'Changer de jeu de départ' : 'Partir d’un jeu de drapeaux'}
            </p>
            <p className="mt-0.5 text-[10px] leading-relaxed text-[rgba(255,255,255,0.32)]">
              Déplie une fiche pour voir ce qu'elle contient et ce qu'elle changerait ici.
            </p>
          </div>
          {base && (
            <button
              onClick={() => setBrowsing(false)}
              className="h-[24px] flex-shrink-0 rounded-md border border-[rgba(255,255,255,0.12)] px-2 text-[10px] font-semibold text-[rgba(255,255,255,0.45)] hover:border-white/25"
            >
              Annuler
            </button>
          )}
        </div>

        {full.map((p) => (
          <PresetCard
            key={p.id}
            preset={p}
            current={value}
            open={expanded === p.id}
            onToggle={() => setExpanded(expanded === p.id ? null : p.id)}
            onApply={() => apply(p)}
          />
        ))}

        {addons.length > 0 && (
          <>
            <p className="mt-1 text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
              Compléments — s'ajoutent sans remplacer
            </p>
            {addons.map((p) => (
              <PresetCard
                key={p.id}
                preset={p}
                current={value}
                open={expanded === p.id}
                onToggle={() => setExpanded(expanded === p.id ? null : p.id)}
                onApply={() => apply(p)}
              />
            ))}
          </>
        )}
      </div>
    )
  }

  // ── Base rattachée ─────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-[rgba(75,63,207,0.35)] bg-[rgba(75,63,207,0.09)] p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(180,172,255,0.75)]">
            Basée sur
          </p>
          <p className="text-[13px] font-bold text-white">{base.label}</p>
          <p className="mt-0.5 text-[10px] leading-relaxed text-[rgba(255,255,255,0.4)]">{base.hint}</p>
        </div>
        <div className="flex flex-shrink-0 items-center gap-1.5">
          <button
            onClick={() => setBrowsing(true)}
            className="h-[24px] rounded-md border border-[rgba(255,255,255,0.15)] bg-[rgba(255,255,255,0.05)] px-2 text-[10px] font-semibold text-[rgba(255,255,255,0.6)] transition-colors hover:border-white/30"
          >
            Changer
          </button>
          <button
            onClick={() => onBaseChange(null)}
            title="Garder les drapeaux, oublier d'où ils viennent"
            className="h-[24px] rounded-md border border-[rgba(255,255,255,0.12)] px-2 text-[10px] font-semibold text-[rgba(255,255,255,0.35)] transition-colors hover:border-white/25 hover:text-[rgba(255,255,255,0.6)]"
          >
            Détacher
          </button>
        </div>
      </div>

      {n === 0 ? (
        <p className="text-[10px] text-[rgba(255,255,255,0.35)]">Conforme au jeu d'origine, aucun écart.</p>
      ) : (
        <div className="flex flex-col gap-1 border-t border-[rgba(255,255,255,0.09)] pt-2">
          <p className="text-[10px] font-semibold text-[rgba(180,172,255,0.8)]">
            {n} écart{n > 1 ? 's' : ''} par rapport au jeu d'origine
          </p>
          {[
            ...diff!.modified.map((d) => ({ ...d, tag: 'modifié' as const })),
            ...diff!.added.map((d) => ({ ...d, tag: 'ajouté' as const })),
            ...diff!.removed.map((d) => ({ ...d, tag: 'retiré' as const })),
          ].map((d) => (
            <div key={`${d.tag}-${d.key}`} className="flex items-center gap-2 text-[10px]">
              <span
                className={`w-[46px] flex-shrink-0 font-semibold ${
                  d.tag === 'modifié' ? 'text-[rgba(240,180,90,0.85)]'
                    : d.tag === 'ajouté' ? 'text-[rgba(130,220,150,0.8)]'
                      : 'text-[rgba(255,140,140,0.8)]'
                }`}
              >
                {d.tag}
              </span>
              <span className="min-w-0 flex-1 truncate font-mono text-[rgba(255,255,255,0.6)]">{d.label}</span>
              <span className="flex-shrink-0 font-mono text-[rgba(255,255,255,0.4)]">
                {d.from !== undefined && d.to !== undefined ? `${d.from} → ${d.to}` : (d.to ?? d.from)}
              </span>
              <button
                onClick={() => onChange(restoreFromPreset(value, base.body, d.key))}
                className="flex-shrink-0 font-semibold text-[rgba(150,140,240,0.8)] transition-colors hover:text-[rgba(190,183,255,1)]"
              >
                rétablir
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function PresetCard({ preset, current, open, onToggle, onApply }: {
  preset: JvmPreset
  current: string
  open: boolean
  onToggle: () => void
  onApply: () => void
}) {
  // Ce que l'application changerait : un jeu complet part d'une catégorie vide,
  // un complément s'ajoute à ce qui est déjà là. Le diff doit refléter le geste
  // réel, sinon l'aperçu ment.
  const after = applyPreset(preset.full ? '' : current, preset)
  const diff = diffAgainstPreset(after, current)

  return (
    <div className="overflow-hidden rounded-xl border border-[rgba(255,255,255,0.09)] bg-[rgba(0,0,0,0.25)]">
      <div className="flex items-center gap-2 px-3 py-2">
        <button onClick={onToggle} className="flex min-w-0 flex-1 items-center gap-2 text-left">
          <svg
            viewBox="0 0 10 6" fill="currentColor" width={8} height={5}
            className={`flex-shrink-0 text-[rgba(255,255,255,0.35)] transition-transform duration-150 ${open ? '' : '-rotate-90'}`}
          >
            <path d="M0 0l5 6 5-6z" />
          </svg>
          <span className="truncate text-[12px] font-bold text-[rgba(255,255,255,0.85)]">{preset.label}</span>
          <span className="flex-shrink-0 rounded-md bg-[rgba(255,255,255,0.05)] px-1.5 py-0.5 text-[9px] font-semibold text-[rgba(255,255,255,0.4)]">
            {parseJvmArgs(preset.body).length} drapeaux
          </span>
        </button>
        <button
          onClick={onApply}
          className="h-[24px] flex-shrink-0 rounded-md border border-[rgba(75,63,207,0.6)] bg-[rgba(75,63,207,0.35)] px-2.5 text-[10px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.5)]"
        >
          Appliquer
        </button>
      </div>

      {open && (
        <div className="flex flex-col gap-2 border-t border-[rgba(255,255,255,0.07)] px-3 py-2.5">
          <p className="text-[11px] leading-relaxed text-[rgba(255,255,255,0.45)]">{preset.hint}</p>

          <DiffSummary diff={diff} full={preset.full} />

          <div className="flex flex-col rounded-lg bg-[rgba(0,0,0,0.35)] p-2">
            {parseJvmArgs(preset.body).map((a, i) => (
              <span key={`${a}-${i}`} className="break-all font-mono text-[10px] leading-relaxed text-[rgba(255,255,255,0.42)]">
                {a}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function DiffSummary({ diff, full }: { diff: ArgsDiff; full: boolean }) {
  const n = diffCount(diff)
  if (n === 0) {
    return <p className="text-[10px] text-[rgba(255,255,255,0.35)]">Déjà appliqué — rien ne changerait.</p>
  }
  return (
    <div className="flex flex-col gap-0.5">
      <p className="text-[10px] font-semibold text-[rgba(255,255,255,0.5)]">Si tu appliques :</p>
      {diff.added.length > 0 && (
        <p className="text-[10px] text-[rgba(130,220,150,0.8)]">
          + {diff.added.length} drapeau{diff.added.length > 1 ? 'x' : ''} ajouté{diff.added.length > 1 ? 's' : ''}
        </p>
      )}
      {diff.modified.map((d) => (
        <p key={d.key} className="font-mono text-[10px] text-[rgba(240,180,90,0.85)]">
          ~ {d.label} {d.from} → {d.to}
        </p>
      ))}
      {diff.removed.length > 0 && (
        <p className="text-[10px] text-[rgba(255,140,140,0.8)]">
          − {diff.removed.map((d) => d.label).join(', ')} {full ? 'supprimés (jeu complet)' : 'supprimés'}
        </p>
      )}
    </div>
  )
}
