import type { ModpackMeta } from '@/types'
import { press, pressIf } from '@/lib/motion'
import { motion } from 'framer-motion'
import type { ResolvedModpackFile } from '@/lib/modrinthModpacks'
import { formatRelativeDate } from '@/lib/modrinthModpacks'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'

export function ModpackBanner({
  meta, menuOpen, showPackContent, packVersionUpdate, updatingPackVersion, installProgress,
  onToggleMenu, onReplace, onRemove, onToggleShowContent, onUpdatePackVersion,
}: {
  meta: ModpackMeta
  /// Le pack est en cours d'installation : la bannière est provisoire (voir
  /// `watchPackInstall` dans Mods.tsx), elle porte la barre de progression et
  /// ne propose aucune action — retirer ou remplacer un pack à moitié posé
  /// n'aurait pas de sens.
  installProgress?: { percent: number; label: string } | null
  menuOpen: boolean
  showPackContent: boolean
  /// Nouvelle version du modpack publiée sur Modrinth (version_id différent
  /// de celui installé) — `null` si à jour, ou pack importé depuis un fichier
  /// local (pas de projet Modrinth à vérifier).
  packVersionUpdate: ResolvedModpackFile | null
  updatingPackVersion: boolean
  onToggleMenu: () => void
  onReplace: () => void
  onRemove: () => void
  onToggleShowContent: () => void
  onUpdatePackVersion: () => void
}) {
  const t = useT()
  const { language } = useStore()
  const installing = !!installProgress
  // Une bannière provisoire ne connaît pas encore tout du pack (un fichier
  // importé n'a ni auteur ni version) : on n'affiche que ce qu'on sait.
  const byline = [meta.author, meta.version_number, formatRelativeDate(meta.date_modified)].filter(Boolean).join(' · ')
  return (
    <div className="relative mb-4 rounded-2xl px-4 py-3.5 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]">
      <div className="flex items-start gap-3">
        <div className="w-12 h-12 rounded-xl flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
          {meta.icon_url ? <img src={meta.icon_url} alt="" className="w-full h-full object-cover" /> : <PlugIcon size={22} color="rgba(255,255,255,0.2)" />}
        </div>
        <div className="min-w-0 flex-1">
          <p className="font-bold truncate text-[14px] text-white">{meta.name}</p>
          {byline && <p className="text-[11px] text-[rgba(255,255,255,0.35)] mt-px">{byline}</p>}
          {meta.summary && <p className="text-[12px] text-[rgba(255,255,255,0.5)] mt-1.5">{meta.summary}</p>}
          {installProgress && (
            <div className="mt-2.5 flex flex-col gap-1.5">
              <div className="flex items-center gap-2">
                <ButtonSpinner size={11} trackColor="rgba(255,255,255,0.15)" />
                <span className="text-[11.5px] font-semibold text-[rgba(255,255,255,0.8)]">{t('mods.installingModpack')}</span>
                <span className="ml-auto text-[11px] font-semibold tabular-nums text-[rgba(255,255,255,0.5)]">{installProgress.percent} %</span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
                <div className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200" style={{ width: `${installProgress.percent}%` }} />
              </div>
              <p className="truncate text-[10.5px] text-[rgba(255,255,255,0.4)]">{installProgress.label}</p>
            </div>
          )}
          <div className={`flex items-center gap-2 mt-2 flex-wrap ${installing ? 'hidden' : ''}`}>
            <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.3)]">
              <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
              {meta.downloads.toLocaleString(language === 'fr' ? 'fr-FR' : 'en-US')}
            </span>
            {meta.categories.slice(0, 3).map((c) => (
              <span key={c} className="rounded-full px-2 py-0.5 text-[10px] bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.4)]">{c}</span>
            ))}
            {packVersionUpdate && (
              <motion.button {...pressIf(!(updatingPackVersion))}
                onClick={onUpdatePackVersion}
                disabled={updatingPackVersion}
                className={`flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[10.5px] font-semibold transition-colors ${
                  updatingPackVersion
                    ? 'bg-[rgba(255,255,255,0.06)] text-[rgba(255,255,255,0.3)] cursor-not-allowed'
                    : 'bg-[rgba(250,204,21,0.14)] text-[rgba(250,204,21,0.9)] cursor-pointer hover:bg-[rgba(250,204,21,0.22)]'
                }`}
              >
                {updatingPackVersion ? (
                  <><ButtonSpinner size={10} trackColor="rgba(255,255,255,0.15)" /> {t('mods.packUpdating')}</>
                ) : (
                  t('mods.packNewVersion', { version: packVersionUpdate.versionNumber })
                )}
              </motion.button>
            )}
          </div>
        </div>
        <div className={`relative flex-shrink-0 ${installing ? 'hidden' : ''}`}>
          <motion.button {...press}
            onClick={onToggleMenu}
            className="flex h-7 w-7 items-center justify-center rounded-lg transition-all duration-150 text-[rgba(255,255,255,0.3)] bg-[rgba(255,255,255,0.05)]"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}><path d="M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 8a2 2 0 100 4 2 2 0 000-4z" /></svg>
          </motion.button>
          {menuOpen && (
            <div className="absolute right-0 top-9 z-20 flex flex-col gap-0.5 rounded-xl p-1 w-[190px] bg-[#191923] border border-[rgba(255,255,255,0.1)] shadow-[0_12px_30px_rgba(0,0,0,0.5)]">
              <motion.button {...press} onClick={onToggleShowContent} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]">
                {showPackContent ? t('mods.hidePackContent') : t('mods.showPackContent')}
              </motion.button>
              <motion.button {...press} onClick={onReplace} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgba(255,255,255,0.8)] hover:bg-[rgba(255,255,255,0.06)]">
                {t('mods.replaceModpack')}
              </motion.button>
              <motion.button {...press} onClick={onRemove} className="rounded-lg px-3 py-2 text-left transition-all duration-150 text-[12px] text-[rgb(248,113,113)] hover:bg-[rgba(200,50,50,0.12)]">
                {t('mods.removeModpack')}
              </motion.button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
