import { PLANS } from '@/data/plans'
import { PlanIcon } from './PlanIcon'

export type CheckoutState = 'idle' | 'loading' | 'waiting' | 'success' | 'timeout' | 'error'

export function UpgradeModal({
  plan,
  checkoutState,
  checkoutError,
  onClose,
  onCheckout,
  onRefresh,
  refreshing,
}: {
  plan: string
  checkoutState: CheckoutState
  checkoutError: string | null
  onClose: () => void
  onCheckout: () => Promise<void>
  onRefresh: () => Promise<void>
  refreshing: boolean
}) {
  const planMeta = PLANS.find((p) => p.id === plan)!
  const busy = checkoutState === 'loading' || checkoutState === 'waiting'
  const accentColor = planMeta.color === '#818cf8' ? '#4B3FCF' : 'rgba(245,158,11,0.9)'
  const accentText = planMeta.color === '#818cf8' ? 'white' : '#09090D'

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(6px)' }}
      onClick={busy ? undefined : onClose}
    >
      <div
        className="flex flex-col gap-6 rounded-2xl p-8 w-full max-w-sm relative"
        style={{
          background: '#111118',
          border: `1px solid ${planMeta.borderColor}`,
          boxShadow: `0 0 60px ${planMeta.glowColor}, 0 24px 48px rgba(0,0,0,0.6)`,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Close */}
        {!busy && (
          <button
            onClick={onClose}
            className="absolute right-4 top-4 flex items-center justify-center rounded-lg transition-all duration-150"
            style={{ width: 28, height: 28, color: 'rgba(255,255,255,0.25)', background: 'rgba(255,255,255,0.04)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.7)'; e.currentTarget.style.background = 'rgba(255,255,255,0.08)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.25)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
              <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
            </svg>
          </button>
        )}

        {/* Icon + title */}
        <div className="flex flex-col items-center gap-3 text-center">
          <div
            className="flex items-center justify-center rounded-2xl"
            style={{ width: 56, height: 56, background: planMeta.badgeBg, border: `1px solid ${planMeta.borderColor}` }}
          >
            {checkoutState === 'success' ? (
              <svg viewBox="0 0 24 24" fill="#4ade80" width={22} height={22}>
                <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
              </svg>
            ) : (
              <PlanIcon plan={plan} color={planMeta.color} />
            )}
          </div>
          <div>
            <h2 className="font-black text-white" style={{ fontSize: 20, letterSpacing: '-0.01em' }}>
              {checkoutState === 'success' ? 'Plan activé !' : `Plan ${planMeta.name}`}
            </h2>
            <p style={{ fontSize: 13, color: checkoutState === 'success' ? '#4ade80' : planMeta.color, fontWeight: 700, marginTop: 2 }}>
              {checkoutState === 'success' ? `Bienvenue sur ${planMeta.name}` : `${planMeta.price}€ / mois`}
            </p>
          </div>
        </div>

        {/* State-dependent body */}
        {checkoutState === 'idle' && (
          <div
            className="flex flex-col gap-2 rounded-xl p-4"
            style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)' }}
          >
            <div className="flex items-center gap-2">
              <svg viewBox="0 0 24 24" fill={planMeta.color} width={14} height={14} style={{ flexShrink: 0 }}>
                <path d="M20 4H4c-1.11 0-2 .89-2 2v12c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z" />
              </svg>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'rgba(255,255,255,0.7)' }}>
                Paiement sécurisé via Lemon Squeezy
              </span>
            </div>
            <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', lineHeight: 1.6 }}>
              Vous allez être redirigé vers la page de paiement dans votre navigateur. Votre plan sera activé <span style={{ color: 'rgba(255,255,255,0.65)', fontWeight: 600 }}>automatiquement</span> après confirmation du paiement.
            </p>
          </div>
        )}

        {(checkoutState === 'waiting' || checkoutState === 'loading') && (
          <div
            className="flex flex-col items-center gap-3 rounded-xl p-5"
            style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)' }}
          >
            <span className="h-8 w-8 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.08)', borderTopColor: planMeta.color }} />
            <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.5)', textAlign: 'center', lineHeight: 1.6 }}>
              {checkoutState === 'loading' ? 'Création de la session de paiement...' : 'En attente de confirmation du paiement...\nCette fenêtre se mettra à jour automatiquement.'}
            </p>
          </div>
        )}

        {checkoutState === 'success' && (
          <div
            className="flex flex-col items-center gap-2 rounded-xl p-4"
            style={{ background: 'rgba(74,222,128,0.05)', border: '1px solid rgba(74,222,128,0.2)' }}
          >
            <p style={{ fontSize: 12, color: 'rgba(255,255,255,0.55)', textAlign: 'center', lineHeight: 1.6 }}>
              Votre abonnement <span style={{ color: planMeta.color, fontWeight: 700 }}>{planMeta.name}</span> est maintenant actif. Cette fenêtre va se fermer automatiquement.
            </p>
          </div>
        )}

        {checkoutState === 'timeout' && (
          <div
            className="flex flex-col gap-2 rounded-xl p-4"
            style={{ background: 'rgba(245,158,11,0.06)', border: '1px solid rgba(245,158,11,0.2)' }}
          >
            <div className="flex items-center gap-2">
              <svg viewBox="0 0 24 24" fill="#f59e0b" width={14} height={14} style={{ flexShrink: 0 }}>
                <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
              </svg>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'rgba(255,255,255,0.7)' }}>Paiement non détecté</span>
            </div>
            <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', lineHeight: 1.6 }}>
              Si vous avez finalisé le paiement, cliquez sur <span style={{ color: 'rgba(255,255,255,0.65)', fontWeight: 600 }}>Rafraîchir mon plan</span> pour vérifier manuellement.
            </p>
          </div>
        )}

        {checkoutState === 'error' && (
          <div
            className="flex flex-col gap-2 rounded-xl p-4"
            style={{ background: 'rgba(200,50,50,0.08)', border: '1px solid rgba(200,50,50,0.2)' }}
          >
            <span style={{ fontSize: 12, fontWeight: 700, color: 'rgb(248,113,113)' }}>Erreur</span>
            <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.4)', lineHeight: 1.6, wordBreak: 'break-word' }}>
              {checkoutError ?? 'Une erreur inattendue est survenue.'}
            </p>
          </div>
        )}

        {/* Action buttons */}
        {checkoutState === 'idle' && (
          <button
            onClick={onCheckout}
            className="flex items-center justify-center gap-2 w-full rounded-xl font-bold transition-all duration-150 active:scale-95"
            style={{ height: 44, fontSize: 13, background: accentColor, color: accentText, boxShadow: `0 4px 20px ${planMeta.glowColor}` }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.filter = 'brightness(1.1)' }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.filter = '' }}
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
              <path d="M19 19H5V8h14m-3-7v2H8V1H6v2H5c-1.11 0-2 .89-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5a2 2 0 00-2-2h-1V1m-1 11h-5v5h5v-5z" />
            </svg>
            Procéder au paiement
          </button>
        )}

        {(checkoutState === 'timeout' || checkoutState === 'error') && (
          <div className="flex flex-col gap-2">
            <button
              onClick={onRefresh}
              disabled={refreshing}
              className="flex items-center justify-center gap-2 w-full rounded-xl font-bold transition-all duration-150 active:scale-95"
              style={{
                height: 40, fontSize: 12,
                background: refreshing ? 'rgba(40,38,65,0.7)' : accentColor,
                color: refreshing ? 'rgba(255,255,255,0.3)' : accentText,
                cursor: refreshing ? 'not-allowed' : 'pointer',
              }}
            >
              {refreshing
                ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.1)', borderTopColor: 'rgba(255,255,255,0.5)' }} />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
              }
              {refreshing ? 'Vérification...' : 'Rafraîchir mon plan'}
            </button>
            {checkoutState === 'error' && (
              <button
                onClick={onCheckout}
                className="flex items-center justify-center gap-2 w-full rounded-xl font-semibold transition-all duration-150"
                style={{ height: 36, fontSize: 12, color: 'rgba(255,255,255,0.4)', background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}
              >
                Réessayer
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
