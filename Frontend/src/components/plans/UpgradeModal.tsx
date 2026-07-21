import { PLANS } from '@/data/plans'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { CloseButton } from '@/components/ui/CloseButton'
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
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(0,0,0,0.7)] backdrop-blur-[6px]"
      onClick={busy ? undefined : onClose}
    >
      <div
        className="flex flex-col gap-6 rounded-2xl p-8 w-full max-w-sm relative bg-[#111118]"
        style={{
          border: `1px solid ${planMeta.borderColor}`,
          boxShadow: `0 0 60px ${planMeta.glowColor}, 0 24px 48px rgba(0,0,0,0.6)`,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Close */}
        {!busy && (
          <CloseButton
            onClick={onClose}
            className="absolute right-4 top-4"
            idleColor="rgba(255,255,255,0.25)"
            idleBg="rgba(255,255,255,0.04)"
            hoverBg="rgba(255,255,255,0.08)"
          />
        )}

        {/* Icon + title */}
        <div className="flex flex-col items-center gap-3 text-center">
          <div
            className="flex items-center justify-center rounded-2xl w-[56px] h-[56px]"
            style={{ background: planMeta.badgeBg, border: `1px solid ${planMeta.borderColor}` }}
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
            <h2 className="font-black text-white text-[20px] tracking-[-0.01em]">
              {checkoutState === 'success' ? 'Plan activé !' : `Plan ${planMeta.name}`}
            </h2>
            <p
              className={`text-[13px] font-bold mt-0.5 ${checkoutState === 'success' ? 'text-[#4ade80]' : ''}`}
              style={checkoutState === 'success' ? undefined : { color: planMeta.color }}
            >
              {checkoutState === 'success' ? `Bienvenue sur ${planMeta.name}` : `${planMeta.price}€ / mois`}
            </p>
          </div>
        </div>

        {/* State-dependent body */}
        {checkoutState === 'idle' && (
          <div className="flex flex-col gap-2 rounded-xl p-4 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]">
            <div className="flex items-center gap-2">
              <svg viewBox="0 0 24 24" fill={planMeta.color} width={14} height={14} className="shrink-0">
                <path d="M20 4H4c-1.11 0-2 .89-2 2v12c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z" />
              </svg>
              <span className="text-[12px] font-bold text-[rgba(255,255,255,0.7)]">
                Paiement sécurisé via Lemon Squeezy
              </span>
            </div>
            <p className="text-[11px] text-[rgba(255,255,255,0.4)] leading-[1.6]">
              Vous allez être redirigé vers la page de paiement dans votre navigateur. Votre plan sera activé <span className="text-[rgba(255,255,255,0.65)] font-semibold">automatiquement</span> après confirmation du paiement.
            </p>
          </div>
        )}

        {(checkoutState === 'waiting' || checkoutState === 'loading') && (
          <div className="flex flex-col items-center gap-3 rounded-xl p-5 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]">
            <ButtonSpinner size={32} color={planMeta.color} trackColor="rgba(255,255,255,0.08)" />
            <p className="text-[12px] text-[rgba(255,255,255,0.5)] text-center leading-[1.6]">
              {checkoutState === 'loading' ? 'Création de la session de paiement...' : 'En attente de confirmation du paiement...\nCette fenêtre se mettra à jour automatiquement.'}
            </p>
          </div>
        )}

        {checkoutState === 'success' && (
          <div className="flex flex-col items-center gap-2 rounded-xl p-4 bg-[rgba(74,222,128,0.05)] border border-[rgba(74,222,128,0.2)]">
            <p className="text-[12px] text-[rgba(255,255,255,0.55)] text-center leading-[1.6]">
              Votre abonnement <span className="font-bold" style={{ color: planMeta.color }}>{planMeta.name}</span> est maintenant actif. Cette fenêtre va se fermer automatiquement.
            </p>
          </div>
        )}

        {checkoutState === 'timeout' && (
          <div className="flex flex-col gap-2 rounded-xl p-4 bg-[rgba(245,158,11,0.06)] border border-[rgba(245,158,11,0.2)]">
            <div className="flex items-center gap-2">
              <svg viewBox="0 0 24 24" fill="#f59e0b" width={14} height={14} className="shrink-0">
                <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
              </svg>
              <span className="text-[12px] font-bold text-[rgba(255,255,255,0.7)]">Paiement non détecté</span>
            </div>
            <p className="text-[11px] text-[rgba(255,255,255,0.4)] leading-[1.6]">
              Si vous avez finalisé le paiement, cliquez sur <span className="text-[rgba(255,255,255,0.65)] font-semibold">Rafraîchir mon plan</span> pour vérifier manuellement.
            </p>
          </div>
        )}

        {checkoutState === 'error' && (
          <div className="flex flex-col gap-2 rounded-xl p-4 bg-[rgba(200,50,50,0.08)] border border-[rgba(200,50,50,0.2)]">
            <span className="text-[12px] font-bold text-[rgb(248,113,113)]">Erreur</span>
            <p className="text-[11px] text-[rgba(255,255,255,0.4)] leading-[1.6] break-words">
              {checkoutError ?? 'Une erreur inattendue est survenue.'}
            </p>
          </div>
        )}

        {/* Action buttons */}
        {checkoutState === 'idle' && (
          <button
            onClick={onCheckout}
            className="flex items-center justify-center gap-2 w-full rounded-xl font-bold transition-all duration-150 active:scale-95 h-11 text-[13px] hover:brightness-110"
            style={{ background: accentColor, color: accentText, boxShadow: `0 4px 20px ${planMeta.glowColor}` }}
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
              className={`flex items-center justify-center gap-2 w-full rounded-xl font-bold transition-all duration-150 active:scale-95 h-10 text-[12px] ${refreshing ? 'bg-[rgba(40,38,65,0.7)] text-[rgba(255,255,255,0.3)] cursor-not-allowed' : 'cursor-pointer'}`}
              style={refreshing ? undefined : { background: accentColor, color: accentText }}
            >
              {refreshing
                ? <ButtonSpinner size={14} color="rgba(255,255,255,0.5)" trackColor="rgba(255,255,255,0.1)" />
                : <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
              }
              {refreshing ? 'Vérification...' : 'Rafraîchir mon plan'}
            </button>
            {checkoutState === 'error' && (
              <button
                onClick={onCheckout}
                className="flex items-center justify-center gap-2 w-full rounded-xl font-semibold transition-all duration-150 h-9 text-[12px] text-[rgba(255,255,255,0.4)] bg-[rgba(255,255,255,0.04)] border border-[rgba(255,255,255,0.08)]"
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
