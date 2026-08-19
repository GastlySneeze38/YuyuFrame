import { useState } from 'react'
import { api } from '@/api/client'
import { showError } from '@/stores/useErrorToast'
import type { JvmConfigPreview, JvmVendor } from '@/types'

const VENDORS: { id: JvmVendor; label: string }[] = [
  { id: 'temurin', label: 'Temurin' },
  { id: 'openj9', label: 'OpenJ9' },
  { id: 'graal', label: 'GraalVM' },
  { id: 'custom', label: 'Personnalisé' },
]

const HOTSPOT_GC_OPTIONS = [
  { id: 'auto', label: 'Auto' },
  { id: 'g1', label: 'G1GC' },
  { id: 'zgc', label: 'ZGC (Java 21+, ≥6 Go)' },
]

const OPENJ9_GC_OPTIONS = [
  { id: 'auto', label: 'Auto (gencon)' },
  { id: 'gencon', label: 'gencon' },
  { id: 'optthruput', label: 'optthruput' },
  { id: 'optavgpause', label: 'optavgpause' },
  { id: 'balanced', label: 'balanced' },
  { id: 'metronome', label: 'metronome' },
]

/**
 * Section repliable "JVM avancé" (P1-6, audit launcher, Phase 6) — vendeur
 * (Temurin/OpenJ9/GraalVM/custom), policy GC dépendante du vendeur choisi
 * (Auto par défaut), et bouton "Voir la configuration appliquée" quand une
 * instance existe déjà (`preview` fourni — pas disponible à la création
 * d'une instance vierge, il n'y a encore rien à prévisualiser).
 */
export function JvmAdvancedSection({
  vendor, onVendorChange,
  customPath, onCustomPathChange,
  gcPolicy, onGcPolicyChange,
  preview,
}: {
  vendor: JvmVendor
  onVendorChange: (v: JvmVendor) => void
  customPath: string
  onCustomPathChange: (v: string) => void
  gcPolicy: string
  onGcPolicyChange: (v: string) => void
  preview?: { instanceId: string; mcVersion: string; ramMb: number }
}) {
  const [expanded, setExpanded] = useState(false)
  const [loadingPreview, setLoadingPreview] = useState(false)
  const [previewResult, setPreviewResult] = useState<JvmConfigPreview | null>(null)

  const gcOptions = vendor === 'openj9' ? OPENJ9_GC_OPTIONS : HOTSPOT_GC_OPTIONS

  const handlePreview = async () => {
    if (!preview) return
    setLoadingPreview(true)
    setPreviewResult(null)
    try {
      const result = await api.instances.previewJvmConfig(
        preview.instanceId, preview.mcVersion, preview.ramMb, vendor, customPath || undefined, gcPolicy,
      )
      setPreviewResult(result)
    } catch (e) {
      showError(e)
    } finally {
      setLoadingPreview(false)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <button
        onClick={() => setExpanded((v) => !v)}
        className="flex items-center gap-1.5 self-start text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-colors hover:text-[rgba(255,255,255,0.7)]"
      >
        <svg viewBox="0 0 10 6" fill="currentColor" width={8} height={5} className={`flex-shrink-0 transition-transform duration-150 ${expanded ? 'rotate-0' : '-rotate-90'}`}>
          <path d="M0 0l5 6 5-6z" />
        </svg>
        JVM avancé
      </button>

      {expanded && (
        <div className="flex flex-col gap-3 rounded-xl p-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]">
          <div>
            <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">Vendeur JVM</label>
            <div className="flex flex-wrap gap-1.5 mt-1">
              {VENDORS.map((v) => (
                <button
                  key={v.id}
                  onClick={() => onVendorChange(v.id)}
                  className={`rounded-lg text-[11px] font-semibold transition-all duration-150 h-[28px] px-2.5 border ${
                    vendor === v.id
                      ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(75,63,207,0.7)] text-white'
                      : 'bg-[rgba(0,0,0,0.35)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.45)] hover:border-white/25'
                  }`}
                >
                  {v.label}
                </button>
              ))}
            </div>
            {vendor === 'graal' && (
              <p className="text-[10px] text-[rgba(240,180,90,0.6)] mt-1.5">
                ⚠ Gains JIT inconsistants sur Minecraft, warmup souvent plus long qu'avec Temurin — option manuelle, pas un défaut recommandé.
              </p>
            )}
            {vendor === 'openj9' && (
              <p className="text-[10px] text-[rgba(255,255,255,0.35)] mt-1.5">
                Empreinte mémoire de base plus faible que Temurin — pertinent surtout sur les petites configs (~2 Go).
              </p>
            )}
          </div>

          {(vendor === 'custom' || vendor === 'graal') && (
            <div>
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                Chemin java.exe {vendor === 'custom' ? '(requis)' : '(optionnel)'}
              </label>
              <input
                type="text"
                placeholder="C:\...\bin\java.exe"
                value={customPath}
                onChange={(e) => onCustomPathChange(e.target.value)}
                className="w-full rounded-xl px-3 text-sm text-white outline-none h-[36px] mt-1 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)] focus:border-[rgba(75,63,207,0.6)]"
              />
            </div>
          )}

          <div>
            <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">Ramasse-miettes (GC)</label>
            <div className="flex flex-wrap gap-1.5 mt-1">
              {gcOptions.map((g) => (
                <button
                  key={g.id}
                  onClick={() => onGcPolicyChange(g.id)}
                  className={`rounded-lg text-[11px] font-semibold transition-all duration-150 h-[28px] px-2.5 border ${
                    gcPolicy === g.id
                      ? 'bg-[rgba(75,63,207,0.35)] border-[rgba(75,63,207,0.7)] text-white'
                      : 'bg-[rgba(0,0,0,0.35)] border-[rgba(255,255,255,0.08)] text-[rgba(255,255,255,0.45)] hover:border-white/25'
                  }`}
                >
                  {g.label}
                </button>
              ))}
            </div>
          </div>

          {preview && (
            <div className="flex flex-col gap-2">
              <button
                onClick={handlePreview}
                disabled={loadingPreview}
                className="self-start rounded-lg text-[11px] font-semibold px-3 h-[28px] bg-[rgba(255,255,255,0.06)] border border-[rgba(255,255,255,0.12)] text-[rgba(255,255,255,0.7)] transition-colors hover:border-white/25 disabled:opacity-50"
              >
                {loadingPreview ? 'Résolution...' : 'Voir la configuration appliquée'}
              </button>
              {previewResult && (
                <div className="rounded-xl p-3 bg-[rgba(0,0,0,0.3)] border border-[rgba(255,255,255,0.08)]">
                  <p className="text-[11px] text-[rgba(255,255,255,0.6)] font-semibold mb-1">
                    Java {previewResult.java_major} — {previewResult.java_path}
                  </p>
                  <pre className="text-[10px] text-[rgba(255,255,255,0.4)] whitespace-pre-wrap break-all font-mono leading-relaxed">
                    {previewResult.jvm_args.join('\n')}
                  </pre>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
