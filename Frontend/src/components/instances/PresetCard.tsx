import type { InstancePreset } from '@/data/presets'
import { loaderColor } from '@/lib/loader'

export function PresetCard({ preset, selected, onSelect }: { preset: InstancePreset; selected: boolean; onSelect: () => void }) {
  return (
    <button
      onClick={onSelect}
      className={`flex flex-col gap-1 rounded-xl px-3 py-2.5 text-left transition-all duration-150 border ${
        selected ? 'bg-[rgba(75,63,207,0.22)] border-[rgba(75,63,207,0.7)]' : 'bg-[rgba(0,0,0,0.35)] border-[rgba(255,255,255,0.08)]'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="text-[16px]">{preset.emoji}</span>
        <span className={`font-bold text-[12px] ${selected ? 'text-white' : 'text-[rgba(255,255,255,0.85)]'}`}>{preset.name}</span>
      </div>
      <p className="text-[10.5px] text-[rgba(255,255,255,0.35)]">{preset.description}</p>
      <div className="flex items-center gap-1.5 mt-0.5">
        <span className="text-[9.5px] text-[rgba(255,255,255,0.25)]">{preset.mcVersion}</span>
        <span className="text-[9.5px] font-semibold" style={{ color: loaderColor(preset.loader) }}>{preset.loader}</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.2)]">{preset.mods.length} mods</span>
      </div>
    </button>
  )
}
