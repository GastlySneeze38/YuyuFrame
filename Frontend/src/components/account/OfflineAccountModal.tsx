import { useState } from 'react'
import { ModalShell } from '@/components/ui/ModalShell'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import { SkinSourceInput, applySkinSource, type SkinSource } from '@/components/account/SkinSourceInput'

const USERNAME_RE = /^[A-Za-z0-9_]{1,16}$/

/** Compte local sans authentification Microsoft (mode hors ligne, pour les
 * serveurs online-mode=false) — voir mc_add_offline côté Rust, qui génère un
 * UUID déterministe (convention vanilla `OfflinePlayer:<pseudo>`). Le skin
 * (optionnel) est purement cosmétique côté launcher — voir skin.rs. */
export function OfflineAccountModal({
  onClose,
  onAdded,
}: {
  onClose: () => void
  onAdded: (acc: { username: string; uuid: string }) => void
}) {
  const [username, setUsername] = useState('')
  const [skinSource, setSkinSource] = useState<SkinSource | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const valid = USERNAME_RE.test(username)

  const handleSubmit = async () => {
    if (!valid || submitting) return
    setSubmitting(true)
    try {
      const acc = await api.mc.addOffline(username)
      if (skinSource) {
        try {
          await applySkinSource(acc.mc_uuid, skinSource)
        } catch (e) {
          showError(e)
        }
      }
      onAdded({ username: acc.mc_username, uuid: acc.mc_uuid })
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <ModalShell title="Compte hors ligne" onClose={onClose} maxWidth="max-w-sm">
      <div className="flex flex-col gap-4">
        <p className="text-[11px] text-[rgba(255,255,255,0.4)] leading-relaxed">
          Compte local sans authentification Microsoft — utilisable uniquement sur les serveurs en mode hors ligne (<code className="text-[rgba(255,255,255,0.55)]">online-mode=false</code>).
        </p>

        <div className="flex flex-col gap-1.5">
          <label className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(255,255,255,0.4)]">
            Pseudo
          </label>
          <input
            autoFocus
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit() }}
            placeholder="Steve"
            maxLength={16}
            className="h-10 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-sm text-white outline-none transition-colors focus:border-[rgba(75,63,207,0.5)]"
          />
          {username.length > 0 && !valid && (
            <p className="text-[10px] text-red-300">1-16 caractères, lettres/chiffres/_ uniquement</p>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <label className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[rgba(255,255,255,0.4)]">
            Skin (optionnel)
          </label>
          <SkinSourceInput value={skinSource} onChange={setSkinSource} />
        </div>

        <button
          onClick={handleSubmit}
          disabled={!valid || submitting}
          className="h-10 rounded-xl text-[13px] font-semibold text-white transition-colors bg-[#4B3FCF] hover:bg-[#6155e8] disabled:cursor-not-allowed disabled:opacity-40"
        >
          {submitting ? 'Ajout...' : 'Ajouter le compte'}
        </button>
      </div>
    </ModalShell>
  )
}
