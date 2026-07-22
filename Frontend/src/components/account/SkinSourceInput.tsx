import { useState } from 'react'
import { open as openFileDialog } from '@tauri-apps/plugin-dialog'
import { api } from '@/api/client'

export type SkinSource = { type: 'file'; path: string } | { type: 'url'; url: string }

/** Envoie la source choisie au backend (fichier local ou URL distante — voir
 * set_account_skin / set_account_skin_from_url) et renvoie la data URI du
 * skin stocké. */
export function applySkinSource(uuid: string, source: SkinSource): Promise<string> {
  return source.type === 'file' ? api.mc.setSkin(uuid, source.path) : api.mc.setSkinFromUrl(uuid, source.url)
}

/** Bloc réutilisable fichier/URL pour choisir un skin — voir
 * OfflineAccountModal (appliqué à la création) et SkinPickerModal (appliqué
 * immédiatement sur un compte existant). Ne parle pas au backend lui-même,
 * se contente de remonter la source choisie via onChange. */
export function SkinSourceInput({
  value,
  onChange,
}: {
  value: SkinSource | null
  onChange: (source: SkinSource | null) => void
}) {
  const [mode, setMode] = useState<'file' | 'url'>(value?.type === 'url' ? 'url' : 'file')

  const pickFile = async () => {
    const picked = await openFileDialog({ filters: [{ name: 'Skin Minecraft', extensions: ['png'] }] })
    if (typeof picked === 'string') onChange({ type: 'file', path: picked })
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex gap-1 rounded-lg bg-[rgba(255,255,255,0.03)] p-0.5">
        {(['file', 'url'] as const).map((m) => (
          <button
            key={m}
            onClick={() => { setMode(m); onChange(null) }}
            className={`flex-1 rounded-md py-1 text-[11px] font-semibold transition-colors ${
              mode === m ? 'bg-[rgba(75,63,207,0.35)] text-white' : 'text-[rgba(255,255,255,0.35)] hover:text-[rgba(255,255,255,0.6)]'
            }`}
          >
            {m === 'file' ? 'Fichier' : 'URL'}
          </button>
        ))}
      </div>

      {mode === 'file' ? (
        <button
          onClick={pickFile}
          className="flex h-10 items-center gap-2 rounded-xl border border-dashed border-[rgba(255,255,255,0.12)] px-3 text-left text-[12px] text-[rgba(255,255,255,0.4)] transition-colors hover:border-[rgba(75,63,207,0.4)] hover:text-[rgba(255,255,255,0.7)]"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" width={14} height={14} className="flex-shrink-0">
            <path d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M4 6h16v12H4V6z" />
          </svg>
          <span className="truncate">
            {value?.type === 'file' ? value.path.split(/[\\/]/).pop() : 'Choisir un fichier PNG (64×64)'}
          </span>
        </button>
      ) : (
        <input
          value={value?.type === 'url' ? value.url : ''}
          onChange={(e) => onChange(e.target.value ? { type: 'url', url: e.target.value } : null)}
          placeholder="https://.../skin.png"
          className="h-10 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-sm text-white outline-none transition-colors focus:border-[rgba(75,63,207,0.5)]"
        />
      )}
    </div>
  )
}
