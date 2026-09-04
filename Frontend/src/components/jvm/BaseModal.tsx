import { useState } from 'react'
import {
  applyPreset, diffAgainstPreset, diffCount, parseJvmArgs, presetsFor, restoreFromPreset,
  type ArgsDiff, type JvmFamily, type JvmPreset,
} from '@/lib/jvmFlags'
import { JvmModal } from './Modal'
import type { JvmFlagCategory } from '@/types'

/**
 * Le jeu de drapeaux dont une catégorie est issue — en modale.
 *
 * Deux problèmes réglés ici. Un jeu ne s'applique plus à l'aveugle : sa fiche
 * dit d'où il vient, ce qu'il cherche à obtenir, et ce que son application va
 * ajouter / modifier / supprimer, avant de cliquer. Et il ne disparaît pas une
 * fois appliqué : la catégorie reste marquée « basée sur X » et liste les
 * écarts introduits depuis, chacun rétablissable isolément — sans quoi on ne
 * sait plus si ce qu'on regarde est le jeu d'origine ou trois modifications
 * empilées dessus.
 *
 * En modale et non sur la page : sept jeux et leurs fiches occupaient la moitié
 * de l'écran en permanence, y compris pour une catégorie qu'on ne touche pas.
 */
export function BaseModal({ category, family, value, baseId, onChange, onBaseChange, onImport, onClose }: {
  category: JvmFlagCategory
  family: JvmFamily
  value: string
  baseId: string | null
  onChange: (v: string) => void
  onBaseChange: (id: string | null) => void
  /** Ouvre l'import d'un jeu collé — il concerne toute la config, pas
   * seulement cette catégorie, donc il est traité par l'écran parent. */
  onImport: () => void
  onClose: () => void
}) {
  const presets = presetsFor(category, family)
  const base = presets.find((p) => p.id === baseId) ?? null
  const [browsing, setBrowsing] = useState(!base)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [query, setQuery] = useState('')

  const diff = base ? diffAgainstPreset(value, base.body) : null
  const n = diff ? diffCount(diff) : 0

  const apply = (preset: JvmPreset) => {
    onChange(applyPreset(preset.full ? '' : value, preset))
    // Un complément ne définit pas d'où part la catégorie : il s'ajoute à ce
    // qui est déjà là. Seul un jeu complet devient la base.
    if (preset.full) onBaseChange(preset.id)
    onClose()
  }

  if (presets.length === 0) {
    return (
      <JvmModal title="Jeux de drapeaux" onClose={onClose} width={520}>
        <p className="px-5 py-6 text-[12px] text-[rgba(255,255,255,0.35)]">
          Aucun jeu vérifié pour cette famille de JVM dans cette catégorie.
        </p>
      </JvmModal>
    )
  }

  // ── Base rattachée ─────────────────────────────────────────────────────────
  if (base && !browsing) {
    return (
      <JvmModal
        title={base.label}
        sub={base.hint}
        onClose={onClose}
        width={600}
        footer={
          <>
            <button
              onClick={() => { onBaseChange(null); onClose() }}
              title="Garder les drapeaux, oublier d'où ils viennent"
              className="h-[30px] rounded-lg border border-[rgba(255,255,255,0.12)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-colors hover:border-white/25 hover:text-[rgba(255,255,255,0.7)]"
            >
              Détacher
            </button>
            <button
              onClick={() => setBrowsing(true)}
              className="h-[30px] rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-3.5 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)]"
            >
              Changer de jeu
            </button>
          </>
        }
      >
        <div className="px-5 py-4">
          {n === 0 ? (
            <p className="text-[12px] text-[rgba(255,255,255,0.4)]">Conforme au jeu d'origine, aucun écart.</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              <p className="text-[11px] font-semibold text-[rgba(180,172,255,0.85)]">
                {n} écart{n > 1 ? 's' : ''} par rapport au jeu d'origine
              </p>
              {[
                ...diff!.modified.map((d) => ({ ...d, tag: 'modifié' as const })),
                ...diff!.added.map((d) => ({ ...d, tag: 'ajouté' as const })),
                ...diff!.removed.map((d) => ({ ...d, tag: 'retiré' as const })),
              ].map((d) => (
                <div key={`${d.tag}-${d.key}`} className="flex items-center gap-2 text-[11px]">
                  <span
                    className={`w-[52px] flex-shrink-0 font-semibold ${
                      d.tag === 'modifié' ? 'text-[rgba(240,180,90,0.85)]'
                        : d.tag === 'ajouté' ? 'text-[rgba(130,220,150,0.8)]'
                          : 'text-[rgba(255,140,140,0.8)]'
                    }`}
                  >
                    {d.tag}
                  </span>
                  <span className="min-w-0 flex-1 truncate font-mono text-[rgba(255,255,255,0.65)]">{d.label}</span>
                  <span className="flex-shrink-0 font-mono text-[rgba(255,255,255,0.4)]">
                    {d.from !== undefined && d.to !== undefined ? `${d.from} → ${d.to}` : (d.to ?? d.from)}
                  </span>
                  <button
                    onClick={() => onChange(restoreFromPreset(value, base.body, d.key))}
                    className="flex-shrink-0 font-semibold text-[rgba(150,140,240,0.85)] transition-colors hover:text-[rgba(190,183,255,1)]"
                  >
                    rétablir
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </JvmModal>
    )
  }

  // ── Choix d'un jeu ─────────────────────────────────────────────────────────
  // La recherche porte aussi sur l'intention ET sur le contenu : on cherche
  // « pauses » ou « Shenandoah » sans savoir sous quel nom le jeu est rangé.
  const q = query.trim().toLowerCase()
  const matching = q
    ? presets.filter((p) => `${p.label} ${p.hint} ${p.body}`.toLowerCase().includes(q))
    : presets
  const full = matching.filter((p) => p.full)
  const addons = matching.filter((p) => !p.full)

  return (
    <JvmModal
      title={base ? 'Changer de jeu de départ' : 'Partir d’un jeu de drapeaux'}
      sub="Déplie une fiche pour voir ce qu'elle contient et ce qu'elle changerait ici."
      onClose={onClose}
      width={620}
      footer={base ? (
        <button
          onClick={() => setBrowsing(false)}
          className="h-[30px] rounded-lg border border-[rgba(255,255,255,0.12)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.5)] hover:border-white/25"
        >
          Retour
        </button>
      ) : undefined}
    >
      <div className="sticky top-0 z-10 flex items-center gap-2 border-b border-[rgba(255,255,255,0.07)] bg-[#111018] px-5 py-3">
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Chercher un jeu — nom, intention (« pauses ») ou drapeau (« Shenandoah »)"
          className="h-[32px] min-w-0 flex-1 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-[12px] text-white outline-none placeholder:text-[rgba(255,255,255,0.25)] focus:border-[rgba(75,63,207,0.6)]"
        />
        {/* Un jeu trouvé ailleurs se colle au lieu de se chercher — même
            question ("d'où viennent ces drapeaux ?"), donc même endroit. */}
        <button
          onClick={onImport}
          className="h-[32px] flex-shrink-0 rounded-xl border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.05)] px-3 text-[11px] font-semibold text-[rgba(255,255,255,0.6)] transition-colors hover:border-white/25 hover:text-[rgba(255,255,255,0.9)]"
        >
          Coller un jeu…
        </button>
      </div>

      <div className="flex flex-col gap-2 px-5 py-4">
        {matching.length === 0 && (
          <p className="py-8 text-center text-[12px] text-[rgba(255,255,255,0.3)]">
            Aucun jeu ne correspond.
          </p>
        )}

        {full.length > 0 && (
          <p className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.3)]">
            Jeux complets — remplacent la catégorie
          </p>
        )}
        {full.map((p) => (
          <PresetCard
            key={p.id} preset={p} current={value}
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
                key={p.id} preset={p} current={value}
                open={expanded === p.id}
                onToggle={() => setExpanded(expanded === p.id ? null : p.id)}
                onApply={() => apply(p)}
              />
            ))}
          </>
        )}
      </div>
    </JvmModal>
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
  if (diffCount(diff) === 0) {
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
