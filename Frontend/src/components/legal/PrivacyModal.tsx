import { AnimatePresence } from 'framer-motion'
import privacyMd from '@/assets/legal/PRIVACY.md?raw'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { LegalMarkdown } from './LegalMarkdown'
import { useT } from '@/i18n'

/** La politique de confidentialité, lue sans quitter le formulaire
 * d'inscription : y aller par la page Mentions légales ferait perdre ce qui
 * vient d'être saisi. Même fichier et même rendu que cette page.
 *
 * « J'accepte » coche la case du formulaire ; fermer par la croix ne coche
 * rien — lire n'est pas accepter. */
export function PrivacyModal({ open, onClose, onAccept }: { open: boolean; onClose: () => void; onAccept: () => void }) {
  const t = useT()
  return (
    <AnimatePresence>
      {open && (
        <ModalShell title={t('legal.privacyTab')} onClose={onClose} maxWidth="max-w-2xl">
          <div className="flex flex-col gap-4">
            <div className="flex max-h-[55vh] flex-col gap-4 overflow-y-auto pr-2">
              <LegalMarkdown source={privacyMd} />
            </div>
            <div className="flex items-center justify-end gap-2.5">
              <Button variant="ghost" onClick={onClose}>
                {t('yuyuLogin.privacyClose')}
              </Button>
              <Button variant="primary" onClick={onAccept}>
                {t('yuyuLogin.privacyAcceptButton')}
              </Button>
            </div>
          </div>
        </ModalShell>
      )}
    </AnimatePresence>
  )
}
