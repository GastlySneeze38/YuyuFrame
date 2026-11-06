import { useNavigate } from 'react-router-dom'
import { YuyuAccountPanel } from '@/components/account/YuyuAccountPanel'
import { useT } from '@/i18n'

/**
 * Le compte YuyuFrame sur téléphone : abonnement, e-mail, mot de passe,
 * double authentification, appareils, déconnexion. Sur PC il partage l'écran
 * Compte avec les comptes Minecraft, en deux colonnes qui ne tiennent pas
 * dans 375 px ; ici il a son écran, ouvert depuis l'en-tête du menu.
 *
 * Écran poussé : retour à gauche, titre centré (grille 48/1fr/48, comme le
 * contenu d'une instance). Le panneau est celui du PC, sans copie : un
 * réglage ajouté au compte apparaît aux deux endroits.
 */
export default function MobileYuyuAccount() {
  const t = useT()
  const navigate = useNavigate()
  return (
    <div className="flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))]">
      <header className="grid shrink-0 grid-cols-[48px_1fr_48px] items-center border-b border-white/[0.06] px-1 pb-2 pt-[max(8px,env(safe-area-inset-top))]">
        <button
          onClick={() => navigate(-1)}
          aria-label={t('yuyuLogin.back')}
          className="press flex h-11 w-11 items-center justify-center rounded-full text-[rgb(var(--accent-text))]"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
            <path d="M15 6l-6 6 6 6" />
          </svg>
        </button>
        <h1 className="truncate text-center text-[17px] font-bold text-white">{t('account.yuyuAccount')}</h1>
        <span />
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(24px,env(safe-area-inset-bottom))] pt-4">
        <YuyuAccountPanel />
      </div>
    </div>
  )
}
