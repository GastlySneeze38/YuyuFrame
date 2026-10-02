import { useState } from 'react'
import { copyShareLink, DISCORD_MESSAGE_LIMIT } from '@/lib/shareLink'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * « Copier le lien » d'un partage, quel qu'il soit (`lib/shareLink.ts`).
 *
 * Le même partout : fabrique le lien par `make`, le copie, puis dit ce qu'on
 * a dans le presse-papiers — sa longueur, et s'il dépasse ce qu'accepte un
 * message Discord, qu'il faudra l'envoyer autrement (en fichier texte, ou
 * dans un message plus long). Les liens d'options, compressés, y arrivent
 * vite ; ceux d'instance rarement.
 */
export function ShareLinkButton({
  make,
  disabled,
  label,
}: {
  make: () => Promise<string>
  disabled?: boolean
  label?: string
}) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState<number | null>(null)

  const copy = async () => {
    setBusy(true)
    setCopied(null)
    try {
      setCopied(await copyShareLink(make))
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        onClick={copy}
        disabled={busy || disabled}
        className="w-fit rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
      >
        {busy ? t('shareLink.preparing') : (label ?? t('shareLink.copy'))}
      </button>
      {copied !== null && (
        <p className="text-[11.5px] text-[rgba(134,239,172,0.85)]">
          {t('shareLink.copied', { count: copied })}
          {copied > DISCORD_MESSAGE_LIMIT && (
            <span className="text-warning"> {t('shareLink.tooLongForDiscord', { limit: DISCORD_MESSAGE_LIMIT })}</span>
          )}
        </p>
      )}
    </div>
  )
}
