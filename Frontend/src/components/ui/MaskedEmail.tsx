import { useState } from 'react'
import { useT } from '@/i18n'

/**
 * Une adresse e-mail cachée comme un mot de passe : des points, et un œil pour
 * la lire. Cachée par défaut — une capture d'écran, un partage d'écran ou une
 * vidéo du launcher ne doit pas montrer l'adresse du compte sans qu'on l'ait
 * voulu.
 */
export function MaskedEmail({ email, className = '' }: { email: string; className?: string }) {
  const t = useT()
  const [shown, setShown] = useState(false)
  return (
    <span className={`inline-flex min-w-0 max-w-full items-center gap-1.5 ${className}`}>
      <span className={shown ? 'truncate' : 'select-none tracking-widest'}>{shown ? email : '••••••••••'}</span>
      <button
        type="button"
        onClick={() => setShown((s) => !s)}
        aria-label={shown ? t('account.hideEmail') : t('account.showEmail')}
        title={shown ? t('account.hideEmail') : t('account.showEmail')}
        className="shrink-0 text-txt-muted transition-colors hover:text-txt-primary"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
          {shown ? (
            <>
              <path d="M9.88 9.88a3 3 0 1 0 4.24 4.24" />
              <path d="M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68" />
              <path d="M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61" />
              <line x1="2" x2="22" y1="2" y2="22" />
            </>
          ) : (
            <>
              <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" />
              <circle cx="12" cy="12" r="3" />
            </>
          )}
        </svg>
      </button>
    </span>
  )
}
