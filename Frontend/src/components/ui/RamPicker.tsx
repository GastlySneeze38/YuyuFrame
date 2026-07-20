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
      <label style={{ fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.1em', textTransform: 'uppercase', fontWeight: 600 }}>RAM</label>
      <div className="flex gap-1.5 mt-1">
        {RAM_OPTIONS.map((r) => {
          const risky = maxSafeMb !== null && r > maxSafeMb
          return (
            <button
              key={r}
              onClick={() => onChange(r)}
              title={risky ? `Dépasse la RAM disponible recommandée sur cette machine (~${formatRam(maxSafeMb!)} conseillé)` : undefined}
              className="rounded-xl text-xs font-semibold transition-all duration-150"
              style={{
                height: 34, padding: '0 10px',
                background: value === r ? 'rgba(75,63,207,0.35)' : 'rgba(0,0,0,0.35)',
                border: `1px solid ${value === r ? 'rgba(75,63,207,0.7)' : risky ? 'rgba(220,140,40,0.5)' : 'rgba(255,255,255,0.08)'}`,
                color: value === r ? 'rgba(255,255,255,0.95)' : risky ? 'rgba(240,180,90,0.6)' : 'rgba(255,255,255,0.35)',
              }}
            >
              {formatRam(r)}
            </button>
          )
        })}
      </div>
      {maxSafeMb !== null && value > maxSafeMb && (
        <p style={{ fontSize: 10, color: 'rgba(240,180,90,0.75)', marginTop: 4 }}>
          ⚠ Dépasse la RAM dispo recommandée pour cette machine (~{formatRam(maxSafeMb)} conseillé) — le jeu risque de mettre du temps à démarrer ou de ralentir tout le système.
        </p>
      )}
    </div>
  )
}
