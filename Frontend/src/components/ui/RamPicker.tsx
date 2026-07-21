import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { RAM_OPTIONS, formatRam } from '@/lib/format'

export function RamPicker({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  // Aucune détection matérielle n'existait avant — le launcher proposait les
  // mêmes paliers RAM à tout le monde sans regarder la machine, faisant
  // "galérer" au lancement les PC modestes (RAM dispo insuffisante pour
  // l'OS + le reste une fois le tas Java alloué). On avertit visuellement
  // sans bloquer, certains utilisateurs ferment volontairement d'autres
  // apps avant de jouer.
  const [maxSafeMb, setMaxSafeMb] = useState<number | null>(null)

  useEffect(() => {
    api.system.memoryInfo()
      .then((info) => setMaxSafeMb(Math.max(1024, info.available_mb - 1536)))
      .catch(() => {})
  }, [])

  return (
    <div>
      <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">RAM</label>
      <div className="flex gap-1.5 mt-1">
        {RAM_OPTIONS.map((r) => {
          const risky = maxSafeMb !== null && r > maxSafeMb
          return (
            <button
              key={r}
              onClick={() => onChange(r)}
              title={risky ? `Dépasse la RAM disponible recommandée sur cette machine (~${formatRam(maxSafeMb!)} conseillé)` : undefined}
              className={`rounded-xl text-xs font-semibold transition-all duration-150 h-[34px] px-2.5 ${
                value === r
                  ? 'bg-[rgba(75,63,207,0.35)] border border-[rgba(75,63,207,0.7)] text-[rgba(255,255,255,0.95)]'
                  : risky
                    ? 'bg-[rgba(0,0,0,0.35)] border border-[rgba(220,140,40,0.5)] text-[rgba(240,180,90,0.6)]'
                    : 'bg-[rgba(0,0,0,0.35)] border border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.35)]'
              }`}
            >
              {formatRam(r)}
            </button>
          )
        })}
      </div>
      {maxSafeMb !== null && value > maxSafeMb && (
        <p className="text-[10px] text-[rgba(240,180,90,0.75)] mt-1">
          ⚠ Dépasse la RAM dispo recommandée pour cette machine (~{formatRam(maxSafeMb)} conseillé) — le jeu risque de mettre du temps à démarrer ou de ralentir tout le système.
        </p>
      )}
    </div>
  )
}
