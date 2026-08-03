import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { ModpackIndexInfo } from '@/types'
import { resolveModpackFile } from '@/lib/modrinthModpacks'
import { resolveCurseforgeModpackFile } from '@/lib/curseforgeModpacks'
import { formatDownloadCount } from '@/lib/format'
import { loaderColor } from '@/lib/loader'
import { displayName } from './modUtils'
import { Spinner } from '@/components/ui/Spinner'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { CloseButton } from '@/components/ui/CloseButton'
import { PlugIcon } from '@/components/ui/icons/PlugIcon'
import { useT } from '@/i18n'
import type { MergedModpackHit } from './ModpackBrowseTab'

/** Aperçu d'un modpack avant install — MC version/loader/liste des mods, résolus
 * depuis le fichier du pack lui-même (voir modpack_fetch_index/_curseforge_index),
 * pas depuis les métadonnées de recherche qui ne les contiennent pas. */
export function ModpackDetailModal({
  hit, mcVersion, loader, installing, installProgress, onClose, onInstall,
}: {
  hit: MergedModpackHit
  mcVersion: string
  loader: string
  installing: boolean
  installProgress?: { percent: number; label: string } | null
  onClose: () => void
  onInstall: () => void
}) {
  const t = useT()
  const [info, setInfo] = useState<ModpackIndexInfo | null>(null)
  const [loadingInfo, setLoadingInfo] = useState(true)
  const [infoError, setInfoError] = useState(false)

  useEffect(() => {
    let cancelled = false
    setInfo(null)
    setInfoError(false)
    setLoadingInfo(true)

    const run = async () => {
      try {
        const idx = hit.source === 'modrinth'
          ? await (async () => {
            // Même fichier que celui qui serait réellement installé (voir
            // resolveModpackFile) — sans le filtre version/loader, l'aperçu
            // pouvait afficher les infos d'un tout autre build que celui qu'on
            // installerait vraiment (ex: MC 26.2 affiché sur une instance 26.1.2).
            const file = await resolveModpackFile(hit.hit.project_id, mcVersion, loader)
            if (!file) throw new Error('Aucun fichier disponible')
            return api.modpacks.fetchIndex(file.url)
          })()
          : await (async () => {
            const file = await resolveCurseforgeModpackFile(hit.hit.id, mcVersion)
            if (!file) throw new Error('Aucun fichier disponible')
            return api.modpacks.fetchCurseforgeIndex(file.url)
          })()
        if (!cancelled) setInfo(idx)
      } catch {
        if (!cancelled) setInfoError(true)
      } finally {
        if (!cancelled) setLoadingInfo(false)
      }
    }
    run()
    return () => { cancelled = true }
  }, [hit, mcVersion, loader])

  const title = hit.source === 'modrinth' ? hit.hit.title : hit.hit.name
  const description = hit.source === 'modrinth' ? hit.hit.description : hit.hit.summary
  const downloads = hit.source === 'modrinth' ? hit.hit.downloads : hit.hit.downloadCount
  const iconUrl = hit.source === 'modrinth' ? hit.hit.icon_url : hit.hit.logoUrl

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.6)] backdrop-blur-[4px]"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="flex w-full max-w-2xl flex-col gap-4 rounded-2xl p-6 bg-[#111118] border border-[rgba(75,63,207,0.3)] shadow-[0_24px_80px_rgba(0,0,0,0.6)] max-h-[85vh]">
        {/* Header */}
        <div className="flex flex-shrink-0 items-start gap-3">
          <div className="w-[52px] h-[52px] rounded-[14px] flex-shrink-0 overflow-hidden bg-[rgba(255,255,255,0.06)] flex items-center justify-center">
            {iconUrl ? <img src={iconUrl} alt="" className="w-full h-full object-cover" /> : <PlugIcon size={24} color="rgba(255,255,255,0.2)" />}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <p className="font-bold text-white text-[16px]">{title}</p>
              {hit.source === 'curseforge' && (
                <span className="flex-shrink-0 rounded-md px-1.5 py-[1px] text-[9px] font-bold text-[#f16436] bg-[rgba(241,100,54,0.15)] border border-[rgba(241,100,54,0.35)]">
                  CURSEFORGE
                </span>
              )}
            </div>
            <p className="text-[12px] text-[rgba(255,255,255,0.4)] mt-0.5">{description}</p>
            <div className="flex flex-wrap items-center gap-1.5 mt-2">
              <span className="flex items-center gap-1 text-[11px] text-[rgba(255,255,255,0.3)]">
                <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>
                {formatDownloadCount(downloads)}
              </span>
              <span className="text-[11px] text-[rgba(255,255,255,0.3)]">{t('mods.byAuthor', { author: hit.hit.author })}</span>
            </div>
          </div>
          <CloseButton onClick={onClose} />
        </div>

        {/* Version MC / loader / nombre de mods */}
        <div className="flex flex-shrink-0 flex-wrap items-center gap-2 min-h-[26px]">
          {loadingInfo ? (
            <Spinner />
          ) : infoError ? (
            <p className="text-[11px] text-[rgba(255,255,255,0.3)]">{t('mods.cannotReachModrinth')}</p>
          ) : info && (
            <>
              {info.mc_version && (
                <span className="rounded-lg px-2.5 py-1 text-[11px] font-semibold bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.7)] border border-[rgba(255,255,255,0.08)]">
                  MC {info.mc_version}
                </span>
              )}
              {info.loader && info.loader !== 'vanilla' && (
                <span
                  className="rounded-lg px-2.5 py-1 text-[11px] font-bold border border-[rgba(255,255,255,0.08)]"
                  style={{ color: loaderColor(info.loader) }}
                >
                  {info.loader}
                </span>
              )}
              <span className="rounded-lg px-2.5 py-1 text-[11px] font-semibold bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.7)] border border-[rgba(255,255,255,0.08)]">
                {t('mods.modCount', { count: info.mods.length })}
              </span>
            </>
          )}
        </div>

        {/* Liste des mods du pack */}
        <p className="flex-shrink-0 text-[11px] font-bold text-[rgba(255,255,255,0.4)] uppercase tracking-[0.06em]">
          {t('mods.packContents')}
        </p>
        <div className="flex flex-1 min-w-0 flex-col overflow-y-auto rounded-xl p-2 bg-[rgba(0,0,0,0.25)] min-h-[120px]">
          {loadingInfo ? null : info && info.mods.length > 0 ? (
            // Triés + extension .jar retirée + alternance de fond : 68 noms de
            // fichiers bruts collés les uns aux autres (souvent chargés de
            // tirets/points de version) formaient un bloc illisible, surtout
            // en petite taille — le tri et l'espacement les rendent scannables.
            // `min-w-0` indispensable : un enfant flex refuse de rétrécir sous
            // la largeur de son contenu tant qu'on ne le force pas, donc
            // `break-words` seul ne suffisait pas — le texte débordait et se
            // faisait rogner net par le conteneur au lieu de passer à la ligne.
            [...info.mods].sort((a, b) => a.localeCompare(b)).map((m, i) => (
              <div
                key={m}
                className={`min-w-0 break-words rounded-lg px-2.5 py-[7px] text-[12.5px] text-[rgba(255,255,255,0.85)] ${i % 2 === 0 ? 'bg-[rgba(255,255,255,0.02)]' : ''}`}
              >
                {displayName(m)}
              </div>
            ))
          ) : !loadingInfo && !infoError ? (
            <p className="text-[12px] text-[rgba(255,255,255,0.3)] text-center py-4">{t('mods.noResults')}</p>
          ) : null}
        </div>

        {/* Install */}
        <button
          onClick={onInstall}
          disabled={installing}
          className={`flex-shrink-0 flex items-center justify-center gap-1.5 rounded-xl font-bold text-white transition-all duration-150 active:scale-95 h-10 text-[13px] ${
            installing ? 'bg-[rgba(75,63,207,0.3)]' : 'bg-[#4B3FCF] hover:bg-[#6155e8]'
          }`}
        >
          {installing ? <ButtonSpinner size={14} trackColor="rgba(255,255,255,0.15)" /> : t('mods.installButton')}
        </button>
        {installing && installProgress && (
          <div className="flex flex-shrink-0 items-center gap-2">
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(255,255,255,0.08)]">
              <div
                className="h-full rounded-full bg-[#4B3FCF] transition-all duration-200"
                style={{ width: `${installProgress.percent}%` }}
              />
            </div>
            <span className="max-w-[140px] flex-shrink-0 truncate text-[10px] text-[rgba(255,255,255,0.35)]">
              {installProgress.label}
            </span>
          </div>
        )}
      </div>
    </div>
  )
}
