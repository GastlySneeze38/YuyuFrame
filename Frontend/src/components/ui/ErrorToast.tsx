import { useErrorToast } from '@/stores/useErrorToast'
import { CloseButton } from './CloseButton'

/** Popup d'erreur global — monté une fois dans App.tsx, piloté par useErrorToast.
 * Remplace les ~20 banners d'erreur inline dispersés dans les pages/modales. */
export function ErrorToast() {
  const message = useErrorToast((s) => s.message)
  const hide = useErrorToast((s) => s.hide)

  if (!message) return null

  return (
    <div
      className="fixed z-[100] flex items-start gap-2.5 rounded-2xl px-4 py-3 top-4 left-1/2 -translate-x-1/2 max-w-[480px] min-w-[280px] bg-[rgba(35,10,12,0.97)] border border-[rgba(248,113,113,0.35)] shadow-[0_16px_48px_rgba(0,0,0,0.55),0_0_0_1px_rgba(200,50,50,0.12)] backdrop-blur-[10px] animate-[errorToastIn_0.28s_cubic-bezier(0.16,1,0.3,1)]"
    >
      <svg viewBox="0 0 24 24" fill="rgb(248,113,113)" width={18} height={18} className="flex-shrink-0 mt-px">
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
      </svg>
      <p className="text-[12.5px] text-[rgba(255,255,255,0.92)] font-medium leading-[1.45] flex-1 [word-break:break-word]">
        {message}
      </p>
      <CloseButton
        onClick={hide}
        size={22}
        iconSize={11}
        idleColor="rgba(255,255,255,0.35)"
        idleBg="transparent"
        hoverColor="rgba(255,255,255,0.8)"
        hoverBg="rgba(255,255,255,0.08)"
      />
    </div>
  )
}
