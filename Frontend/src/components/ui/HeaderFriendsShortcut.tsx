import { useNavigate } from 'react-router-dom'
import { useStore } from '@/stores/useStore'
import { FriendsButton } from '@/components/friends/FriendsButton'
import { useT } from '@/i18n'

/**
 * À droite de `PageHeader` sur toutes les pages sauf l'accueil : le raccourci
 * vers le panneau Amis. Il a pris la place de l'ancien bouton de compte, qui
 * vit désormais dans l'en-tête du panneau (`account/AccountMenu.tsx`).
 * Sans compte, « Se connecter » à la place : sinon rien ne mènerait à la
 * connexion depuis ces pages.
 */
export function HeaderFriendsShortcut() {
  const t = useT()
  const navigate = useNavigate()
  const signedIn = useStore((s) => !!s.yuyuUsername)

  if (!signedIn) {
    return (
      <button
        onClick={() => navigate('/yuyu')}
        className="press ml-auto rounded-lg px-3 py-1.5 text-[11px] font-semibold transition-colors duration-150 bg-[rgb(var(--accent)/0.18)] border border-[rgb(var(--accent)/0.35)] text-[rgb(var(--accent-text)/0.9)] hover:bg-[rgb(var(--accent)/0.3)] max-sm:flex max-sm:h-11 max-sm:shrink-0 max-sm:items-center max-sm:rounded-full max-sm:px-4 max-sm:text-[15px]"
      >
        {t('plans.loginCta')}
      </button>
    )
  }
  return (
    <div className="ml-auto flex-shrink-0">
      <FriendsButton compact />
    </div>
  )
}
