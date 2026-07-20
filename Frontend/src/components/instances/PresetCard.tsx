import type { InstancePreset } from '@/data/presets'
import { loaderColor } from '@/lib/loader'

export function PresetCard({ preset, selected, onSelect }: { preset: InstancePreset; selected: boolean; onSelect: () => void }) {
  return (
    <button
      onClick={onSelect}
      className="flex flex-col gap-1 rounded-xl px-3 py-2.5 text-left transition-all duration-150"
      style={{
        background: selected ? 'rgba(75,63,207,0.22)' : 'rgba(0,0,0,0.35)',
        border: `1px solid ${selected ? 'rgba(75,63,207,0.7)' : 'rgba(255,255,255,0.08)'}`,
      }}
    >
      <div className="flex items-center gap-2">
        <span style={{ fontSize: 16 }}>{preset.emoji}</span>
        <span className="font-bold" style={{ fontSize: 12, color: selected ? 'white' : 'rgba(255,255,255,0.85)' }}>{preset.name}</span>
      </div>
      <p style={{ fontSize: 10.5, color: 'rgba(255,255,255,0.35)' }}>{preset.description}</p>
      <div className="flex items-center gap-1.5 mt-0.5">
        <span style={{ fontSize: 9.5, color: 'rgba(255,255,255,0.25)' }}>{preset.mcVersion}</span>
        <span style={{ fontSize: 9.5, color: loaderColor(preset.loader), fontWeight: 600 }}>{preset.loader}</span>
        <span style={{ fontSize: 9.5, color: 'rgba(255,255,255,0.2)' }}>{preset.mods.length} mods</span>
      </div>
    </button>
  )
}
