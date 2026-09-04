import { useMemo, useState } from 'react'
import { CATEGORY_META } from '@/lib/jvmFlags'
import { analyzeImport } from '@/lib/jvmCatalog'
import { JvmModal } from './Modal'
import type { JvmFlagCategory } from '@/types'

const CATEGORIES: JvmFlagCategory[] = ['jvm', 'gc', 'jit']

const PLACEHOLDER = `java -Xmx6G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \\
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -jar server.jar nogui`

/**
 * Import d'un jeu de drapeaux collé depuis l'extérieur.
 *
 * Les jeux qui circulent (forum, wiki, README d'un hébergeur) se présentent
 * presque toujours comme une **ligne de commande entière** :
 * `java -Xmx4G -XX:… -jar server.jar nogui`. On l'accepte telle quelle et on
 * fait le tri — sinon il faudrait la nettoyer à la main avant de coller, ce que
 * personne ne fait correctement du premier coup.
 *
 * L'analyse est montrée AVANT l'import : ce qui part dans quelle catégorie, ce
 * qui est écarté et pourquoi. Après coup, retrouver lequel des vingt drapeaux
 * pose problème coûte bien plus cher que de le dire tout de suite.
 */
export function ImportModal({ onImport, onClose }: {
  onImport: (byCategory: Record<JvmFlagCategory, string[]>, mode: 'append' | 'replace') => void
  onClose: () => void
}) {
  const [raw, setRaw] = useState('')
  const [mode, setMode] = useState<'append' | 'replace'>('append')
  const analysis = useMemo(() => analyzeImport(raw), [raw])

  return (
    <JvmModal
      title="Importer un jeu de drapeaux"
      sub="Colle ce que tu as trouvé — une ligne de commande complète fait l'affaire, le tri est fait ici."
      onClose={onClose}
      width={660}
      footer={
        <>
          <div className="mr-auto flex gap-1">
            {(['append', 'replace'] as const).map((m) => (
              <button
                key={m}
                onClick={() => setMode(m)}
                className={`h-[28px] rounded-lg border px-2.5 text-[10px] font-semibold transition-colors ${
                  mode === m
                    ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.3)] text-white'
                    : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.42)] hover:border-white/25'
                }`}
              >
                {m === 'append' ? 'Ajouter à la config' : 'Remplacer tout'}
              </button>
            ))}
          </div>
          <button
            onClick={() => { onImport(analysis.byCategory, mode); onClose() }}
            disabled={analysis.count === 0}
            className="h-[30px] rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-4 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)] disabled:opacity-35 disabled:hover:bg-[rgba(75,63,207,0.4)]"
          >
            {analysis.count > 0 ? `Importer ${analysis.count} drapeau${analysis.count > 1 ? 'x' : ''}` : 'Importer'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3 px-5 py-4">
        <textarea
          autoFocus
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
          spellCheck={false}
          placeholder={PLACEHOLDER}
          className="min-h-[130px] w-full resize-y rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] p-3 font-mono text-[11px] leading-relaxed text-[rgba(255,255,255,0.85)] outline-none placeholder:text-[rgba(255,255,255,0.2)] focus:border-[rgba(75,63,207,0.6)]"
        />

        {raw.trim() && analysis.count === 0 && (
          <p className="text-[11px] text-[rgba(240,180,90,0.8)]">
            Aucun drapeau JVM reconnu là-dedans.
          </p>
        )}

        {analysis.count > 0 && (
          <div className="flex flex-col gap-2">
            {CATEGORIES.map((cat) => {
              const flags = analysis.byCategory[cat]
              if (flags.length === 0) return null
              return (
                <div key={cat} className="rounded-xl border border-[rgba(255,255,255,0.09)] bg-[rgba(0,0,0,0.25)] p-2.5">
                  <p className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(180,172,255,0.75)]">
                    {CATEGORY_META[cat].label} — {flags.length}
                  </p>
                  <p className="mt-1 break-all font-mono text-[10px] leading-relaxed text-[rgba(255,255,255,0.45)]">
                    {flags.join('  ')}
                  </p>
                </div>
              )
            })}

            {(analysis.memory.length > 0 || analysis.ignored.length > 0 || analysis.unknown.length > 0) && (
              <div className="flex flex-col gap-1 rounded-xl border border-dashed border-[rgba(255,255,255,0.1)] p-2.5">
                {analysis.memory.length > 0 && (
                  <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                    <span className="font-mono text-[rgba(255,255,255,0.5)]">{analysis.memory.join(' ')}</span> — écarté :
                    la mémoire se règle dans les réglages JVM, le launcher la calcule depuis la RAM de l'instance.
                  </p>
                )}
                {analysis.ignored.length > 0 && (
                  <p className="text-[10px] leading-relaxed text-[rgba(255,255,255,0.35)]">
                    <span className="font-mono text-[rgba(255,255,255,0.5)]">{analysis.ignored.join(' ')}</span> — écarté :
                    ligne de commande, pas un réglage de JVM.
                  </p>
                )}
                {analysis.unknown.length > 0 && (
                  <p className="text-[10px] leading-relaxed text-[rgba(240,180,90,0.75)]">
                    Importés sans fiche (absents du catalogue, à vérifier qu'ils existent encore sur votre JVM) :{' '}
                    <span className="font-mono">{analysis.unknown.join(' ')}</span>
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </JvmModal>
  )
}
