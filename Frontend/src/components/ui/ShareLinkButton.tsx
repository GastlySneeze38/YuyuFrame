import { useState } from 'react'
import { DISCORD_MESSAGE_LIMIT } from '@/lib/shareLink'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'

/**
 * « Copier le lien » d'un partage, quel qu'il soit (`lib/shareLink.ts`).
 *
 * Le Rust rend une ou plusieurs parties (`share_link::build`) : chacune tient
 * dans un message Discord. Une seule partie se copie d'un clic ; plusieurs
 * s'affichent en liste, une copie par partie — on les envoie une par message
 * — plus « tout copier » pour qui colle directement dans un launcher.
 */
export function ShareLinkButton({
  make,
  disabled,
  label,
}: {
  make: () => Promise<string[]>
  disabled?: boolean
  label?: string
}) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [parts, setParts] = useState<string[] | null>(null)
  /** Parties déjà copiées, pour qu'on sache où on en est. */
  const [copied, setCopied] = useState<Set<number>>(new Set())

  const copyText = async (text: string, index: number) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied((prev) => new Set(prev).add(index))
    } catch (e) {
      showError(e)
    }
  }

  const create = async () => {
    setBusy(true)
    setParts(null)
    setCopied(new Set())
    try {
      const result = await make()
      setParts(result)
      if (result.length === 1) await copyText(result[0], 0)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const single = parts?.length === 1 ? parts[0] : null

  return (
    <div className="flex flex-col gap-1.5">
      <button
        onClick={create}
        disabled={busy || disabled}
        className="w-fit rounded-xl border border-line bg-surface-2 px-4 py-2 text-[12.5px] font-semibold text-txt-secondary transition-colors hover:text-txt-primary disabled:cursor-not-allowed disabled:opacity-40"
      >
        {busy ? t('shareLink.preparing') : (label ?? t('shareLink.copy'))}
      </button>

      {single !== null && copied.has(0) && (
        <p className="text-[11.5px] text-[rgba(134,239,172,0.85)]">
          {t('shareLink.copied', { count: single.length })}
          {single.length > DISCORD_MESSAGE_LIMIT && (
            <span className="text-warning"> {t('shareLink.tooLongForDiscord', { limit: DISCORD_MESSAGE_LIMIT })}</span>
          )}
        </p>
      )}

      {parts && parts.length > 1 && (
        <div className="flex flex-col gap-2 rounded-xl border border-line bg-surface-2 p-3">
          <p className="text-[12px] text-txt-secondary">{t('shareLink.parts', { count: parts.length })}</p>
          <div className="flex flex-wrap gap-2">
            {parts.map((part, i) => (
              <button
                key={i}
                onClick={() => copyText(part, i)}
                className={`rounded-lg border px-3 py-1.5 text-[11.5px] font-semibold transition-colors ${
                  copied.has(i)
                    ? 'border-[rgba(134,239,172,0.4)] bg-[rgba(134,239,172,0.12)] text-[rgba(134,239,172,0.9)]'
                    : 'border-line text-txt-secondary hover:border-accent/40 hover:text-txt-primary'
                }`}
              >
                {t('shareLink.copyPart', { index: i + 1, total: parts.length })}
              </button>
            ))}
          </div>
          <button
            onClick={() => copyText(parts.join('\n\n'), -1)}
            className="w-fit text-[11.5px] font-semibold text-accent-hover hover:underline"
          >
            {copied.has(-1) ? t('shareLink.allCopied') : t('shareLink.copyAll')}
          </button>
        </div>
      )}
    </div>
  )
}
