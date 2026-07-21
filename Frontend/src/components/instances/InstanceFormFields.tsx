import type { Loader } from '@/types'
import { LOADERS } from '@/lib/loader'

export function NameInput({ value, onChange, onEnter }: { value: string; onChange: (v: string) => void; onEnter?: () => void }) {
  return (
    <input
      type="text"
      placeholder="Nom de l'instance..."
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => e.key === 'Enter' && onEnter?.()}
      className="w-full rounded-xl px-3 text-sm text-white outline-none h-[40px] bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)] focus:border-[rgba(75,63,207,0.6)]"
      autoFocus
    />
  )
}

export function DescriptionInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div>
      <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
        Description <span className="normal-case tracking-normal font-normal text-[rgba(255,255,255,0.25)]">(optionnel)</span>
      </label>
      <textarea
        placeholder="Ex : modpack survie 1.20, save perso..."
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={2}
        maxLength={140}
        className="w-full resize-none rounded-xl px-3 py-2 text-sm text-white outline-none mt-1 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)] focus:border-[rgba(75,63,207,0.6)]"
      />
    </div>
  )
}

export function SubmitButton({ loading, label, loadingLabel, onClick }: { loading: boolean; label: string; loadingLabel: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      disabled={loading}
      className={`w-full font-bold text-white transition-all duration-200 active:scale-95 h-[42px] rounded-xl text-[13px] ${
        loading
          ? 'bg-[rgba(40,38,65,0.7)] shadow-none cursor-not-allowed'
          : 'bg-[#4B3FCF] shadow-[0_4px_20px_rgba(75,63,207,0.35)] cursor-pointer hover:bg-[#6155e8]'
      }`}
    >
      {loading ? loadingLabel : label}
    </button>
  )
}

export function VersionSelect({ versions, value, onChange, className = '' }: { versions: string[]; value: string; onChange: (v: string) => void; className?: string }) {
  return (
    <div className={className}>
      <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">Version MC</label>
      <div className="relative mt-1">
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full appearance-none rounded-xl px-3 pr-7 text-sm font-medium text-white outline-none h-[40px] bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)]"
        >
          {versions.map((v) => (
            <option key={v} value={v} className="bg-[#111118]">{v}</option>
          ))}
        </select>
        <div className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2">
          <svg viewBox="0 0 10 6" fill="white" width={10} height={6} className="opacity-40">
            <path d="M0 0l5 6 5-6z" />
          </svg>
        </div>
      </div>
    </div>
  )
}

export function LoaderPicker({ value, onChange }: { value: Loader; onChange: (l: Loader) => void }) {
  return (
    <div>
      <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">Loader</label>
      <div className="flex gap-1 mt-1">
        {LOADERS.map((l) => (
          <button
            key={l}
            onClick={() => onChange(l)}
            className={`rounded-xl text-xs font-semibold transition-all duration-150 h-[40px] px-3 border ${
              value === l
                ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(75,63,207,0.7)] text-[rgba(255,255,255,0.95)]'
                : 'bg-[rgba(0,0,0,0.35)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.35)]'
            }`}
          >
            {l.charAt(0).toUpperCase() + l.slice(1)}
          </button>
        ))}
      </div>
    </div>
  )
}
