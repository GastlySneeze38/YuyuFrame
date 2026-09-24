import { useEffect, useState } from 'react'
import { check, type Update } from '@tauri-apps/plugin-updater'
import { relaunch } from '@tauri-apps/plugin-process'
import { useStore } from '@/stores/useStore'

export function UpdateChecker() {
  const [update, setUpdate] = useState<Update | null>(null)
  const [status, setStatus] = useState<'idle' | 'downloading' | 'installing' | 'error'>('idle')
  const [progress, setProgress] = useState(0)

  useEffect(() => {
    check()
      .then((u) => {
        if (u?.available) setUpdate(u)
      })
      .catch(() => {})
  }, [])

  if (!update) return null

  const installUpdate = async () => {
    setStatus('downloading')
    let downloaded = 0
    let total = 0
    try {
      await update.downloadAndInstall((event) => {
        if (event.event === 'Started') {
          total = event.data.contentLength ?? 0
        } else if (event.event === 'Progress') {
          downloaded += event.data.chunkLength
          if (total > 0) setProgress(Math.round((downloaded / total) * 100))
        } else if (event.event === 'Finished') {
          setStatus('installing')
        }
      })
      // Plus rien à mettre de côté pour l'après-redémarrage : les notes de
      // version ne viennent plus du manifeste de mise à jour mais du
      // back-office (annonce de flotte à l'emplacement `modal`, voir
      // App.tsx). Le manifeste figeait le texte à la publication de la
      // release — ni corrigeable après coup, ni publiable sans sortir une
      // version.
      await relaunch()
    } catch {
      setStatus('error')
    }
  }

  return (
    <div
      className="fixed bottom-4 right-4 z-50 w-80 rounded-lg p-4 shadow-xl bg-[#13131A] border border-[rgba(255,255,255,0.08)]"
    >
      <div className="mb-2 text-sm font-semibold text-white">
        Mise à jour disponible — v{update.version}
      </div>
      {status === 'idle' && (
        <>
          <p className="mb-3 text-xs text-[rgba(255,255,255,0.5)]">
            Une nouvelle version de YuyuFrame est prête à être installée.
          </p>
          <button
            onClick={installUpdate}
            className="w-full rounded px-3 py-1.5 text-xs font-medium text-white bg-[#4B3FCF]"
          >
            Télécharger et installer
          </button>
        </>
      )}
      {status === 'downloading' && (
        <p className="text-xs text-[rgba(255,255,255,0.5)]">
          Téléchargement... {progress}%
        </p>
      )}
      {status === 'installing' && (
        <p className="text-xs text-[rgba(255,255,255,0.5)]">
          Installation, redémarrage en cours...
        </p>
      )}
      {status === 'error' && (
        <p className="text-xs text-[rgba(220,90,90,0.85)]">
          Échec de la mise à jour. Réessayez plus tard.
        </p>
      )}
    </div>
  )
}
