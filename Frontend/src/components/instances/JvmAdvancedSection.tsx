import { useState } from 'react'
import type { JvmVendor } from '@/types'

const VENDORS: { id: JvmVendor; label: string }[] = [
  { id: 'auto', label: 'Auto' },
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
  // `metronome` volontairement absent : la JVM OpenJ9 de Windows le refuse au
  // démarrage (JVMJ9VM007E), il n'existe que sur les builds temps réel — le
  // proposer ne produisait qu'une instance impossible à lancer.
]

/** Même règle que `resolve_auto_vendor` côté Rust (jvm_args.rs) — dupliquée
 * ici uniquement pour l'affichage (quel vendeur "Auto" choisirait), la
 * décision réelle reste toujours prise côté backend au lancement. */
function autoVendorFor(ramMb: number): 'openj9' | 'temurin' {
  return ramMb < 3072 ? 'openj9' : 'temurin'
}

/**
 * Section repliable "JVM avancé" (P1-6, audit launcher, Phase 6) — "Auto"
 * (recommandé, par défaut) couvre TOUTE la config, vendeur ET GC, choisis
 * selon la RAM allouée (voir grille jvm-config) : ~2 Go → OpenJ9/gencon,
 * au-delà → Temurin/G1 ou ZGC. Un choix manuel (vendeur et/ou GC) s'écarte
 * de cette grille testée — un avertissement le rend explicite plutôt que de
 * laisser l'utilisateur changer une config sensible sans y réfléchir.
 *
 * Le chemin custom n'est plus réservé à Graal/Custom : n'importe quel
 * vendeur peut épingler une install précise (voir `ensure_java` côté Rust) —
 * seul "Auto" n'en propose pas (il n'y a rien à épingler, la résolution est
 * entièrement automatique par définition).
 *
 * Ne sert plus qu'à la CRÉATION d'instance : une fois l'instance créée, tout
 * se règle dans l'écran plein `/jvm/:instanceId` (pages/JvmConfig), qui ajoute
 * les arguments JVM manuels et l'aperçu de la ligne de commande — deux choses
 * qui ont besoin d'une instance déjà existante.
 */
export function JvmAdvancedSection({
  vendor, onVendorChange,
  customPath, onCustomPathChange,
  gcPolicy, onGcPolicyChange,
  ramMb,
}: {
  vendor: JvmVendor
  onVendorChange: (v: JvmVendor) => void
  customPath: string
  onCustomPathChange: (v: string) => void
  gcPolicy: string
  onGcPolicyChange: (v: string) => void
  /** RAM actuellement choisie pour l'instance — sert uniquement à afficher
   * ce que "Auto" choisirait et à détecter un écart avec la recommandation. */
  ramMb: number
}) {
  const [expanded, setExpanded] = useState(false)

  const gcOptions = vendor === 'openj9' ? OPENJ9_GC_OPTIONS : HOTSPOT_GC_OPTIONS
  const autoVendor = autoVendorFor(ramMb)
  const isAuto = vendor === 'auto'
  // On n'atteint la droite du `||` que si `vendor === autoVendor`, donc si le
  // vendeur est temurin ou openj9 : les garde-fous « ni custom ni graal » qui
  // s'y trouvaient ne pouvaient jamais être faux. Retirés — ils laissaient
  // croire à une condition qui n'existait pas.
  const isManualDeviation = !isAuto && (vendor !== autoVendor || gcPolicy !== 'auto')

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
        {isManualDeviation && <span className="h-1.5 w-1.5 rounded-full bg-[rgba(240,180,90,0.8)]" />}
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
            {isAuto ? (
              <p className="text-[10px] text-[rgba(255,255,255,0.35)] mt-1.5">
                Choisira {autoVendor === 'openj9' ? 'OpenJ9 (gencon)' : 'Temurin (G1GC ou ZGC selon la RAM)'} pour {ramMb >= 1024 ? `${ramMb / 1024} Go` : `${ramMb} Mo`}.
              </p>
            ) : (
              <p className="text-[11px] text-[rgba(240,180,90,0.7)] mt-1.5">
                ⚠ S'écarte de la configuration recommandée (Auto choisirait {autoVendor === 'openj9' ? 'OpenJ9' : 'Temurin'} pour cette RAM) — à changer seulement en connaissance de cause.
              </p>
            )}
            {vendor === 'graal' && (
              <p className="text-[10px] text-[rgba(240,180,90,0.6)] mt-1.5">
                ⚠ Gains JIT inconsistants sur Minecraft, warmup souvent plus long qu'avec Temurin — option manuelle, pas un défaut recommandé.
              </p>
            )}
          </div>

          {!isAuto && (
            <div>
              <label className="text-[10px] text-[rgba(255,255,255,0.4)] tracking-[0.1em] uppercase font-semibold">
                Chemin java.exe {vendor === 'custom' ? '(requis)' : '(optionnel — épingle cette install précise)'}
              </label>
              <input
                type="text"
                placeholder="C:\...\bin\java.exe"
                value={customPath}
                onChange={(e) => onCustomPathChange(e.target.value)}
                className="w-full rounded-xl px-3 text-sm text-white outline-none h-[36px] mt-1 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.1)] focus:border-[rgba(75,63,207,0.6)]"
              />
              {customPath.trim() && (
                <p className="text-[10px] text-[rgba(240,180,90,0.6)] mt-1">
                  ⚠ Configuration manuelle — le launcher ne peut plus garantir que cette JVM est compatible/stable.
                </p>
              )}
            </div>
          )}

          {!isAuto && (
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
              {gcPolicy === 'zgc' && ramMb < 6144 && (
                <p className="text-[10px] text-[rgba(240,180,90,0.6)] mt-1.5">
                  ⚠ ZGC exige au moins 6 Go et Java 21+ — sera automatiquement remplacé par G1GC tant que ces conditions ne sont pas réunies.
                </p>
              )}
            </div>
          )}

          <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.3)]">
            Arguments JVM, jeux de drapeaux et aperçu de la ligne de commande : dans "Configuration JVM", une fois
            l'instance créée.
          </p>
        </div>
      )}
    </div>
  )
}
