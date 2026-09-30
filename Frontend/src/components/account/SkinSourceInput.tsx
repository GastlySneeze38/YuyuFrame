import { useState } from 'react'
import { api } from '@/api/client'
import type { SkinVariant } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { useT } from '@/i18n'

/**
 * Skin choisi mais pas encore appliqué : une URL déjà hébergée et le modèle à
 * employer. Voir `commands/account/skin.rs` — le launcher n'héberge aucun
 * skin, il ne fait que désigner ceux qui le sont déjà.
 */
export interface SkinChoice {
  url: string
  variant: SkinVariant
  /** `player:<pseudo>` ou `url`. */
  origin: string
  /** Aperçu, pour montrer ce qui a été trouvé sans dépendre du CORS. */
  dataUri: string
}

/**
 * Bloc compact de choix de skin, pour les endroits où l'écran Skins complet
 * serait de trop — aujourd'hui la création d'un compte hors ligne. L'écran
 * `/skins` reste la maison du système : celui-ci n'en est qu'un raccourci, et
 * ne sait rien appliquer lui-même.
 *
 * Il n'y a plus de mode « fichier local » : un PNG sur le disque n'est visible
 * de personne d'autre, et ne pourra pas l'être sans hébergement.
 */
export function SkinSourceInput({
  value,
  onChange,
}: {
  value: SkinChoice | null
  onChange: (choice: SkinChoice | null) => void
}) {
  const t = useT()
  const [mode, setMode] = useState<'player' | 'url'>('player')
  const [player, setPlayer] = useState('')
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState(false)

  const resolve = async () => {
    if (busy) return
    setBusy(true)
    try {
      if (mode === 'player') {
        if (!player.trim()) return
        const found = await api.skin.resolvePlayer(player.trim())
        onChange({
          url: found.url,
          variant: found.variant,
          origin: `player:${found.username}`,
          dataUri: found.data_uri,
        })
      } else {
        if (!url.trim()) return
        const checked = await api.skin.checkUrl(url.trim())
        onChange({ url: checked.url, variant: 'classic', origin: 'url', dataUri: checked.data_uri })
      }
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex gap-1 rounded-lg bg-[rgba(255,255,255,0.03)] p-0.5">
        {(['player', 'url'] as const).map((m) => (
          <button
            key={m}
            onClick={() => { setMode(m); onChange(null) }}
            className={`flex-1 rounded-md py-1 text-[11px] font-semibold transition-colors ${
              mode === m ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-[rgba(255,255,255,0.35)] hover:text-[rgba(255,255,255,0.6)]'
            }`}
          >
            {m === 'player' ? t('skins.sourcePlayer') : t('skins.sourceUrl')}
          </button>
        ))}
      </div>

      <div className="flex gap-1.5">
        <input
          value={mode === 'player' ? player : url}
          onChange={(e) => {
            if (mode === 'player') setPlayer(e.target.value)
            else setUrl(e.target.value)
            onChange(null)
          }}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); resolve() } }}
          placeholder={mode === 'player' ? t('skins.playerPlaceholder') : 'https://.../skin.png'}
          maxLength={mode === 'player' ? 16 : undefined}
          className="h-10 min-w-0 flex-1 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-sm text-white outline-none transition-colors focus:border-[rgba(75,63,207,0.5)]"
        />
        <button
          onClick={resolve}
          disabled={busy || !(mode === 'player' ? player.trim() : url.trim())}
          className="flex h-10 w-10 items-center justify-center rounded-xl border border-[rgba(255,255,255,0.1)] text-[rgba(255,255,255,0.6)] transition-colors hover:border-[rgba(75,63,207,0.4)] hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
          title={mode === 'player' ? t('skins.search') : t('skins.check')}
        >
          {busy ? (
            <ButtonSpinner size={14} />
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" width={14} height={14}>
              <path d="M5 12l5 5L20 7" />
            </svg>
          )}
        </button>
      </div>

      {value && (
        <div className="flex items-center gap-2 rounded-xl border border-[rgba(75,63,207,0.3)] bg-[rgba(75,63,207,0.08)] p-2">
          {/* Tête recadrée depuis le gabarit : la face fait 8×8 à l'offset
              (8,8) d'une texture de 64 de large. */}
          <div
            className="h-8 w-8 rounded-md [image-rendering:pixelated]"
            style={{
              backgroundImage: `url(${value.dataUri})`,
              backgroundSize: '256px 256px',
              backgroundPosition: '-32px -32px',
            }}
          />
          <p className="truncate text-[11px] text-[rgba(255,255,255,0.7)]">
            {value.origin.startsWith('player:')
              ? t('skins.originPlayer', { name: value.origin.slice('player:'.length) })
              : t('skins.originUrl')}
          </p>
        </div>
      )}
    </div>
  )
}
