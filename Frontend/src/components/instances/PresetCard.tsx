import type { InstancePreset } from '@/data/presets'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { useT } from '@/i18n'

function modLabel(entry: InstancePreset['mods'][number]): string {
  return typeof entry === 'string' ? entry : entry.filename.replace(/\.jar$/, '')
}

export function PresetCard({ preset, selected, onSelect }: { preset: InstancePreset; selected: boolean; onSelect: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onSelect}
      className={`flex flex-col gap-2 rounded-xl px-3 py-2.5 text-left transition-all duration-150 border ${
        selected ? 'bg-[rgba(75,63,207,0.22)] border-[rgba(75,63,207,0.7)]' : 'bg-[rgba(0,0,0,0.35)] border-[rgba(255,255,255,0.08)]'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className="text-[16px]">{preset.emoji}</span>
        <span className={`font-bold text-[12px] ${selected ? 'text-white' : 'text-[rgba(255,255,255,0.85)]'}`}>{preset.name}</span>
      </div>
      <p className="text-[10.5px] text-[rgba(255,255,255,0.35)]">{preset.description}</p>

      <div className="flex items-center gap-1.5 flex-wrap">
        <span className="text-[9.5px] text-[rgba(255,255,255,0.25)]">{preset.mcVersion}</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.15)]">·</span>
        <span className="text-[9.5px] font-semibold" style={{ color: loaderColor(preset.loader) }}>{preset.loader}</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.15)]">·</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.25)]">{formatRam(preset.ramMb)}</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.15)]">·</span>
        <span className="text-[9.5px] text-[rgba(255,255,255,0.2)]">{t('instancesPage.modsCount', { count: preset.mods.length })}</span>
      </div>

      {preset.mods.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {preset.mods.map((m, i) => (
            <span
              key={i}
              className="rounded-md px-1.5 py-0.5 text-[9px] font-medium bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.45)]"
            >
              {modLabel(m)}
            </span>
          ))}
        </div>
      )}
    </button>
  )
}
