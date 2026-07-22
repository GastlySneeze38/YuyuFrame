import { open } from '@tauri-apps/plugin-shell'
import { ModalShell } from '@/components/ui/ModalShell'

const PURCHASE_URL = 'https://www.minecraft.net/fr-fr/store/minecraft-java-bedrock-edition-pc'

/** Rappel affiché au lancement de l'app quand le compte actif est un compte
 * hors ligne (voir isOfflineAccount) — n'empêche rien, juste un rappel
 * respectueux du travail de Mojang, affiché une fois par lancement (voir
 * App.tsx, useEffect sans dépendances). */
export function OfflinePurchaseReminderModal({ onClose }: { onClose: () => void }) {
  return (
    <ModalShell title="Un petit rappel" onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <p className="text-[12px] leading-relaxed text-[rgba(255,255,255,0.6)]">
          Le compte actuellement utilisé est un compte hors ligne. Derrière Minecraft, il y a une équipe chez Mojang qui travaille dur pour continuer à faire évoluer le jeu — achète-le si tu en as les moyens, c'est le minimum de respect pour leur travail.
        </p>

        <button
          onClick={() => open(PURCHASE_URL)}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8]"
        >
          Acheter le jeu →
        </button>

        <button
          onClick={onClose}
          className="h-9 rounded-xl text-[12px] text-[rgba(255,255,255,0.4)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
        >
          Continuer sans acheter
        </button>
      </div>
    </ModalShell>
  )
}
