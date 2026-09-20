import { memo, useState } from 'react'
import { SNAP, listItemVariants, press, pressIf } from '@/lib/motion'
import { AnimatePresence, motion } from 'framer-motion'
import type { Mod } from '@/types'
import { formatBytes } from '@/lib/format'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { Toggle } from '@/components/ui/Toggle'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { displayName, type ModUpdate } from './modUtils'
import { useT } from '@/i18n'

/** Mémoïsé : rendu en liste (potentiellement des dizaines de mods) — les
 * callbacks reçoivent l'identifiant du mod pour que le parent puisse passer
 * des références stables (useCallback) au lieu d'une closure par ligne. */
export const ModRow = memo(function ModRow({
  mod, version, modrinthName, projectId, cfModId, pinned, update, updating, switchingVersion, logoUrl,
  onToggle, onDelete, onUpdate, onSwitchVersion,
}: {
  mod: Mod
  /** Ses mises à jour sont ignorées (interrupteur de la fiche du mod). */
  pinned: boolean
  version: string | null
  modrinthName: string | null
  projectId: string | null
  cfModId: number | null
  update: ModUpdate | null
  updating: boolean
  switchingVersion: boolean
  logoUrl: string | null
  onToggle: (mod: Mod) => void
  onDelete: (name: string) => void
  onUpdate: (update: ModUpdate) => void
  onSwitchVersion: (mod: Mod) => void
}) {
  const t = useT()
  const [confirm, setConfirm] = useState(false)
  return (
    // La ligne entière réagit au survol : elle se décale de trois pixels et
    // son fond s'éclaircit. Dans une liste de cinquante mods tous bâtis
    // pareil, c'est ce qui dit lequel on est en train de viser — surtout au
    // moment d'aller cliquer sur la corbeille, tout à droite de la ligne.
    <motion.div
      variants={{ ...listItemVariants, row: { x: 3, backgroundColor: 'rgba(255,255,255,0.075)' } }}
      whileHover="row"
      transition={SNAP}
      className={`flex items-center rounded-2xl px-4 py-3 border border-[rgba(255,255,255,0.06)] transition-[opacity,border-color] duration-150 hover:border-[rgba(255,255,255,0.14)] ${
        mod.enabled ? 'bg-[rgba(255,255,255,0.04)] opacity-100' : 'bg-[rgba(255,255,255,0.018)] opacity-60'
      }`}
    >
      {/* Section gauche : icône + nom (flex-1) */}
      <div className="flex items-center gap-3 min-w-0 flex-1">
        {/* L'icône du mod avance un peu plus que la ligne : ça donne de la
            profondeur au geste sans déformer la grille. */}
        <motion.div
          variants={{ row: { scale: 1.08, rotate: -3 } }}
          transition={SNAP}
          className={`w-9 h-9 rounded-[10px] flex-shrink-0 overflow-hidden flex items-center justify-center ${
          mod.enabled ? 'bg-[rgba(75,63,207,0.15)]' : 'bg-[rgba(255,255,255,0.05)]'
        }`}>
          {logoUrl
            ? <img src={logoUrl} alt="" className="w-full h-full object-cover" />
            : <PlugIcon size={18} color={mod.enabled ? 'rgba(120,110,230,0.8)' : 'rgba(255,255,255,0.2)'} />
          }
        </motion.div>
        {/* Ne grandit pas : le badge se pose juste après le bloc nom + taille
            au lieu d'être renvoyé à l'autre bout de la ligne. Le `min-w-0`
            laisse le nom se tronquer quand il est trop long. */}
        <div className="min-w-0">
          <p className={`truncate font-semibold text-[13px] ${mod.enabled ? 'text-[rgba(255,255,255,0.9)]' : 'text-[rgba(255,255,255,0.4)]'}`}>
            {modrinthName || displayName(mod.name)}
          </p>
          <p className="mt-px text-[11px] text-[rgba(255,255,255,0.22)]">{formatBytes(mod.size)}</p>
        </div>

        {/* À côté du nom et de sa taille, pas en dessous : l'état « mises à
            jour ignorées » ne se voyait que dans la fiche du mod, qu'il faut
            ouvrir. Sans repère dans la liste, un mod figé volontairement
            ressemble à un mod à jour. */}
        <AnimatePresence>
          {pinned && (
            <motion.span
              initial={{ opacity: 0, scale: 0.8, x: -4 }}
              animate={{ opacity: 1, scale: 1, x: 0 }}
              exit={{ opacity: 0, scale: 0.8, x: -4 }}
              transition={SNAP}
              title={t('mods.ignoreUpdatesDesc')}
              className="flex flex-none items-center gap-1.5 rounded-md bg-[rgba(255,255,255,0.07)] px-2 py-[3px] text-[11px] font-semibold uppercase tracking-[0.05em] text-[rgba(255,255,255,0.45)]"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" width={11} height={11}>
                <path d="M12 5v9M8 10l4 4 4-4M5 19h14" />
              </svg>
              {t('mods.updatesIgnored')}
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      {/* Section centre : version (vraiment au milieu car flanquée de 2 flex-1) */}
      <div className="flex-shrink-0 w-[110px] text-center px-2">
        <span className={`text-[11px] font-medium whitespace-nowrap overflow-hidden text-ellipsis block ${
          version ? 'text-[rgba(255,255,255,0.45)]' : 'text-[rgba(255,255,255,0.15)]'
        }`}>
          {version ?? '—'}
        </span>
      </div>

      {/* Section droite : actions (flex-1, alignées à droite) */}
      <div className="flex items-center justify-end gap-2 flex-1">
        {update && (() => {
          const blocked = update.blockedBy.length > 0
          return (
            <motion.button {...pressIf(!(updating || blocked))}
              onClick={() => onUpdate(update)}
              disabled={updating || blocked}
              title={
                blocked
                  ? t('mods.updateBlockedBy', { list: update.blockedBy.join(', ') })
                  : t('mods.updateTo', { version: update.newVersion })
              }
              className={`flex h-7 flex-shrink-0 items-center gap-1 rounded-lg px-2 transition-all duration-150 text-[10px] font-bold border ${
                blocked
                  ? 'bg-[rgba(248,113,113,0.1)] border-[rgba(248,113,113,0.28)] text-[rgba(248,113,113,0.7)]'
                  : updating
                    ? 'bg-[rgba(255,255,255,0.04)] border-[rgba(250,204,21,0.28)] text-[rgba(255,255,255,0.2)]'
                    : 'bg-[rgba(250,204,21,0.12)] border-[rgba(250,204,21,0.28)] text-[rgba(250,204,21,0.85)]'
              } ${updating || blocked ? 'cursor-not-allowed' : 'cursor-pointer'}`}
            >
              {blocked ? (
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}>
                  <path d="M12 2L1 21h22L12 2zm0 4.5L19.5 19h-15L12 6.5zM11 10v5h2v-5h-2zm0 6v2h2v-2h-2z" />
                </svg>
              ) : updating ? (
                <ButtonSpinner size={12} color="rgba(250,204,21,0.6)" trackColor="rgba(255,255,255,0.1)" />
              ) : (
                <svg viewBox="0 0 24 24" fill="currentColor" width={10} height={10}>
                  <path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z" />
                </svg>
              )}
              {update.newVersion}
            </motion.button>
          )
        })()}
        {(projectId || cfModId) && (
          <motion.button {...pressIf(!(switchingVersion))}
            onClick={() => onSwitchVersion(mod)}
            disabled={switchingVersion}
            title={t('mods.switchVersion')}
            className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150 ${
              switchingVersion
                ? 'text-[rgba(255,255,255,0.15)] cursor-not-allowed'
                : 'text-[rgba(255,255,255,0.3)] hover:text-[rgba(180,170,255,0.9)] hover:bg-[rgba(75,63,207,0.12)] cursor-pointer'
            }`}
          >
            {switchingVersion ? (
              <ButtonSpinner size={13} trackColor="rgba(255,255,255,0.1)" />
            ) : (
              <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
                <path d="M12 5V2L8 6l4 4V7c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm-6 7c0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3c-3.31 0-6-2.69-6-6z" />
              </svg>
            )}
          </motion.button>
        )}
        <Toggle checked={mod.enabled} onChange={() => onToggle(mod)} size="sm" title={mod.enabled ? t('mods.disable') : t('mods.enable')} />
        {confirm ? (
          <div className="flex items-center gap-1 flex-shrink-0">
            <motion.button {...press} onClick={() => { onDelete(mod.name); setConfirm(false) }} className="text-[10px] font-semibold text-[rgb(248,113,113)] bg-[rgba(200,50,50,0.15)] rounded-[7px] py-[3px] px-[7px]">{t('mods.deleteShort')}</motion.button>
            <motion.button {...press} onClick={() => setConfirm(false)} className="text-[10px] text-[rgba(255,255,255,0.4)] bg-[rgba(255,255,255,0.06)] rounded-[7px] py-[3px] px-[7px]">{t('mods.cancelShort')}</motion.button>
          </div>
        ) : (
          <motion.button {...press} onClick={() => setConfirm(true)}
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-xl transition-all duration-150 text-[rgba(255,255,255,0.2)] hover:text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]">
            <svg viewBox="0 0 24 24" fill="currentColor" width={16} height={16}>
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
            </svg>
          </motion.button>
        )}
      </div>
    </motion.div>
  )
})
