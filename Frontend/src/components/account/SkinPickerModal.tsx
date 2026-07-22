import { useState } from 'react'
import { ModalShell } from '@/components/ui/ModalShell'
import { showError } from '@/stores/useErrorToast'
import { SkinSourceInput, applySkinSource, type SkinSource } from '@/components/account/SkinSourceInput'

/** Changement de skin sur un compte hors ligne déjà créé (voir le bouton
 * dédié dans AccountRow, Login.tsx) — applique immédiatement, contrairement
 * à OfflineAccountModal où le skin choisi n'est envoyé qu'après la création
 * du compte. */
export function SkinPickerModal({
  uuid,
  onClose,
  onApplied,
}: {
  uuid: string
  onClose: () => void
  onApplied: (dataUri: string) => void
}) {
  const [source, setSource] = useState<SkinSource | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleApply = async () => {
    if (!source || submitting) return
    setSubmitting(true)
    try {
      const dataUri = await applySkinSource(uuid, source)
      onApplied(dataUri)
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <ModalShell title="Changer de skin" onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <SkinSourceInput value={source} onChange={setSource} />

        <button
          onClick={handleApply}
          disabled={!source || submitting}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8] disabled:cursor-not-allowed disabled:opacity-40"
        >
          {submitting ? 'Application...' : 'Appliquer'}
        </button>
      </div>
    </ModalShell>
  )
}
