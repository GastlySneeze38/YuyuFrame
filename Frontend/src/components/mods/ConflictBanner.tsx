import { AnimatePresence, motion } from 'framer-motion'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { SNAP, press } from '@/lib/motion'
import { displayName } from './modUtils'
import type { ModConflict } from '@/api/client'
import { useT } from '@/i18n'

/**
 * Incompatibilités déjà présentes entre les mods installés.
 *
 * Elles viennent de ce que les mods déclarent eux-mêmes : un `breaks` visant
 * une version installée, ou une plage `depends` que la version présente ne
 * respecte plus. Ce sont celles que Fabric découvre au démarrage — trop tard,
 * après un écran noir et un journal à lire. Les montrer ici, c'est les
 * montrer pendant qu'on peut encore agir.
 *
 * Le bandeau ne corrige rien de lui-même : régler un conflit veut dire
 * changer de version ou retirer un mod, et c'est un arbitrage qui appartient
 * à la personne, pas au launcher. Il mène en revanche à l'essai de
 * compatibilité de l'instance (`CompatTab`), qui est l'endroit où ces
 * arbitrages se proposent : ce que les mods déclarent n'est qu'un indice,
 * l'essai dit ce que le loader refuse réellement, et avec quels gestes.
 */
export function ConflictBanner({ conflicts, instanceId }: { conflicts: ModConflict[]; instanceId: string }) {
  const t = useT()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)

  if (conflicts.length === 0) return null

  return (
    <motion.div
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={SNAP}
      className="mb-3 rounded-xl border border-warning/35 bg-warning/[0.07] px-3.5 py-2.5"
    >
      {/* Deux boutons côte à côte, pas l'un dans l'autre : le titre déplie la
          liste, le second mène à l'essai. */}
      <div className="flex items-center gap-2.5">
      <motion.button
        {...press}
        onClick={() => setOpen((v) => !v)}
        className="flex min-w-0 flex-1 items-center gap-2.5 text-left"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" className="h-4 w-4 flex-none text-warning">
          <path d="M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" />
        </svg>
        <span className="flex-1 text-[12px] font-semibold text-warning">
          {t('mods.conflictsTitle', { count: conflicts.length })}
        </span>
        <motion.svg
          viewBox="0 0 24 24" fill="currentColor" width={10} height={10}
          animate={{ rotate: open ? 90 : 0 }}
          transition={SNAP}
          className="flex-none text-warning/70"
        >
          <path d="M8 5v14l11-7z" />
        </motion.svg>
      </motion.button>
      <motion.button
        {...press}
        // Même adresse que la modale de plantage pour Java : elle ouvre les
        // paramètres de l'instance sur l'onglet demandé (voir `Instances.tsx`).
        onClick={() => navigate(`/instances?settings=${encodeURIComponent(instanceId)}&tab=compat`)}
        className="flex-none rounded-lg border border-warning/40 bg-warning/10 px-2.5 py-1 text-[11px] font-semibold text-warning transition-colors hover:bg-warning/20"
      >
        {t('mods.conflictsRunTest')}
      </motion.button>
      </div>

      <AnimatePresence initial={false}>
        {open && (
          <motion.ul
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            {conflicts.map((c, i) => (
              <li key={i} className="mt-2 text-[11px] leading-relaxed text-txt-secondary">
                <span className="font-semibold text-txt-primary">{displayName(c.declared_by)}</span>
                {c.kind === 'breaks'
                  ? t('mods.conflictBreaks', { target: displayName(c.target), version: c.target_version || '?' })
                  : t('mods.conflictDepends', { target: displayName(c.target), expected: c.expected, version: c.target_version || '?' })}
              </li>
            ))}
          </motion.ul>
        )}
      </AnimatePresence>
    </motion.div>
  )
}
