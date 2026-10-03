import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { ShareLinkStatus } from '@/types'
import type { ShareLinkKind } from '@/lib/shareLink'
import { useT } from '@/i18n'

/**
 * Coller un lien de partage, en une ou plusieurs parties (`lib/shareLink.ts`).
 *
 * Les parties d'un long lien arrivent souvent en plusieurs messages : on les
 * colle à la suite, dans n'importe quel ordre, en une fois ou en plusieurs.
 * Le champ dit lesquelles il a et lesquelles manquent, et prévient le parent
 * (`onReady`) dès que le lien est complet — avec tout le texte, que le Rust
 * relit lui-même.
 *
 * `value` permet au parent d'ajouter une partie arrivée par un clic sur un
 * lien (`App.tsx`) pendant que la fenêtre est ouverte.
 */
export function ShareLinkInput({
  kind,
  value,
  onReady,
  onUrl,
  placeholder,
}: {
  kind: ShareLinkKind
  value?: string
  onReady: (text: string) => void
  /** Si fourni, une adresse `http(s)://` collée ici est acceptée et remise
   *  telle quelle (base de skin : un PNG hébergé ailleurs). */
  onUrl?: (url: string) => void
  placeholder: string
}) {
  const t = useT()
  const [text, setText] = useState(value ?? '')
  const [status, setStatus] = useState<ShareLinkStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** Dernier texte complet transmis : on ne relance pas l'aperçu pour rien. */
  const sent = useRef<string | null>(null)

  useEffect(() => {
    if (value !== undefined) setText(value)
  }, [value])

  useEffect(() => {
    const trimmed = text.trim()
    if (!trimmed) {
      setStatus(null)
      setError(null)
      return
    }
    // Une adresse web n'est pas un lien de partage : l'écran qui en accepte
    // (`onUrl`) la reçoit telle quelle. Un court délai, parce qu'une adresse
    // se tape aussi à la main et qu'on ne va pas la chercher à chaque lettre.
    if (onUrl && /^https?:\/\/\S+$/i.test(trimmed)) {
      setStatus(null)
      setError(null)
      const timer = window.setTimeout(() => {
        if (sent.current === trimmed) return
        sent.current = trimmed
        onUrl(trimmed)
      }, 400)
      return () => window.clearTimeout(timer)
    }
    let alive = true
    api.shareLink
      .status(trimmed)
      .then((s) => {
        if (!alive) return
        if (s.kind !== kind) {
          setStatus(null)
          setError(t('shareLink.wrongKind'))
          return
        }
        setError(null)
        setStatus(s)
        if (s.complete && sent.current !== trimmed) {
          sent.current = trimmed
          onReady(trimmed)
        }
      })
      .catch((e) => {
        if (!alive) return
        setStatus(null)
        setError(String(e))
      })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, kind])

  const missing = status && !status.complete
    ? Array.from({ length: status.total }, (_, i) => i + 1).filter((i) => !status.received.includes(i))
    : []

  return (
    <div className="flex flex-col gap-1.5">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={placeholder}
        rows={status && status.total > 1 ? 4 : 2}
        className="w-full resize-none rounded-xl border border-line bg-black/40 px-3 py-2 text-[12.5px] text-txt-primary outline-none placeholder:text-txt-muted focus:border-accent/60"
      />
      {status && status.total > 1 && (
        <p className={`text-[11.5px] ${status.complete ? 'text-[rgba(134,239,172,0.85)]' : 'text-txt-secondary'}`}>
          {status.complete
            ? t('shareLink.allParts', { total: status.total })
            : t('shareLink.missingParts', { received: status.received.length, total: status.total, missing: missing.join(', ') })}
        </p>
      )}
      {error && <p className="text-[11.5px] text-warning">{error}</p>}
    </div>
  )
}
