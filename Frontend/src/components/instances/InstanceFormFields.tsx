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
      className="w-full rounded-xl px-3 text-sm text-white outline-none"
      style={{ height: 40, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
      onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(75,63,207,0.6)' }}
      onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)' }}
      autoFocus
    />
  )
}

export function DescriptionInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div>
      <label style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.1em', textTransform: 'uppercase', fontWeight: 600 }}>
        Description <span style={{ textTransform: 'none', letterSpacing: 0, fontWeight: 400, color: 'rgba(255,255,255,0.25)' }}>(optionnel)</span>
      </label>
      <textarea
        placeholder="Ex : modpack survie 1.20, save perso..."
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={2}
        maxLength={140}
        className="w-full resize-none rounded-xl px-3 py-2 text-sm text-white outline-none mt-1"
        style={{ background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
        onFocus={(e) => { e.currentTarget.style.borderColor = 'rgba(75,63,207,0.6)' }}
        onBlur={(e) => { e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)' }}
      />
    </div>
  )
}

export function SubmitButton({ loading, label, loadingLabel, onClick }: { loading: boolean; label: string; loadingLabel: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      disabled={loading}
      className="w-full font-bold text-white transition-all duration-200 active:scale-95"
      style={{
        height: 42, borderRadius: 12, fontSize: 13,
        background: loading ? 'rgba(40,38,65,0.7)' : '#4B3FCF',
        boxShadow: loading ? 'none' : '0 4px 20px rgba(75,63,207,0.35)',
        cursor: loading ? 'not-allowed' : 'pointer',
      }}
      onMouseEnter={(e) => { if (!loading) e.currentTarget.style.background = '#6155e8' }}
      onMouseLeave={(e) => { if (!loading) e.currentTarget.style.background = '#4B3FCF' }}
    >
      {loading ? loadingLabel : label}
    </button>
  )
}

export function VersionSelect({ versions, value, onChange, className = '' }: { versions: string[]; value: string; onChange: (v: string) => void; className?: string }) {
  return (
    <div className={className}>
      <label style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.1em', textTransform: 'uppercase', fontWeight: 600 }}>Version MC</label>
      <div className="relative mt-1">
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full appearance-none rounded-xl px-3 pr-7 text-sm font-medium text-white outline-none"
          style={{ height: 40, background: 'rgba(0,0,0,0.4)', border: '1px solid rgba(255,255,255,0.1)' }}
        >
          {versions.map((v) => (
            <option key={v} value={v} style={{ background: '#111118' }}>{v}</option>
          ))}
        </select>
        <div className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2">
          <svg viewBox="0 0 10 6" fill="white" width={10} height={6} style={{ opacity: 0.4 }}>
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
      <label style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.1em', textTransform: 'uppercase', fontWeight: 600 }}>Loader</label>
      <div className="flex gap-1 mt-1">
        {LOADERS.map((l) => (
          <button
            key={l}
            onClick={() => onChange(l)}
            className="rounded-xl text-xs font-semibold transition-all duration-150"
            style={{
              height: 40, padding: '0 12px',
              background: value === l ? 'rgba(75,63,207,0.35)' : 'rgba(0,0,0,0.35)',
              border: `1px solid ${value === l ? 'rgba(75,63,207,0.7)' : 'rgba(255,255,255,0.08)'}`,
              color: value === l ? 'rgba(255,255,255,0.95)' : 'rgba(255,255,255,0.35)',
            }}
          >
            {l.charAt(0).toUpperCase() + l.slice(1)}
          </button>
        ))}
      </div>
    </div>
  )
}
