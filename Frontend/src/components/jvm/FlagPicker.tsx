import { useEffect, useMemo, useState } from 'react'
import { CATEGORY_META, type JvmFamily } from '@/lib/jvmFlags'
import { searchDocs, type JvmFlagDoc } from '@/lib/jvmCatalog'
import type { JvmFlagCategory } from '@/types'

const CATEGORY_FILTERS: { id: JvmFlagCategory | 'all'; label: string }[] = [
  { id: 'all', label: 'Tout' },
  { id: 'jvm', label: CATEGORY_META.jvm.label },
  { id: 'gc', label: CATEGORY_META.gc.label },
  { id: 'jit', label: CATEGORY_META.jit.label },
]

/**
 * Catalogue de drapeaux, ouvert par "Ajouter un drapeau".
 *
 * Chaque entrée porte son explication, sa valeur par défaut et sa plage utile :
 * c'est le seul endroit de l'écran où l'on apprend ce qu'on est en train de
 * régler. Sans lui, ajouter un drapeau supposait de connaître son nom exact ET
 * de savoir ce qu'il fait — deux choses à la fois, dont aucune n'est évidente.
 *
 * La recherche porte aussi sur l'explication, pour qu'on trouve "pause" sans
 * connaître `MaxGCPauseMillis`. Le drapeau part ensuite dans SA catégorie, pas
 * dans celle qu'on regardait (voir `categoryOf`).
 */
export function FlagPicker({ family, category, present, onAdd, onClose }: {
  family: JvmFamily
  /** Catégorie affichée à l'ouverture — simple filtre de départ. */
  category: JvmFlagCategory
  /** Clés déjà présentes dans la config, toutes catégories confondues. */
  present: Set<string>
  onAdd: (token: string) => void
  onClose: () => void
}) {
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<JvmFlagCategory | 'all'>(category)
  const [values, setValues] = useState<Record<string, string>>({})

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const results = useMemo(() => {
    const docs = searchDocs(query, family)
    return filter === 'all' ? docs : docs.filter((d) => d.category === filter)
  }, [query, family, filter])

  // Le catalogue ne peut pas être exhaustif : un drapeau inconnu doit rester
  // saisissable, sinon l'écran devient une prison dès qu'on veut tester
  // quelque chose de pointu.
  const manual = query.trim()
  const manualLooksLikeFlag = manual.startsWith('-')

  const addDoc = (d: JvmFlagDoc, sign?: '+' | '-') => {
    if (d.kind === 'boolean') {
      onAdd(`-XX:${sign ?? '+'}${d.name.slice(4)}`)
      return
    }
    const v = (values[d.name] ?? '').trim()
    if (!v) return
    if (d.name.startsWith('-XX:')) onAdd(`${d.name}=${v}`)
    else if (d.name.startsWith('-Xgcpolicy')) onAdd(`${d.name}:${v}`)
    else onAdd(`${d.name}${v}`)
    setValues((s) => ({ ...s, [d.name]: '' }))
  }

  return (
    <div className="absolute inset-0 z-30 flex items-start justify-center bg-[rgba(5,5,10,0.72)] p-8" onClick={onClose}>
      <div
        className="flex max-h-full w-full max-w-[720px] flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.12)] bg-[#111018] shadow-[0_20px_60px_rgba(0,0,0,0.6)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-col gap-2.5 border-b border-[rgba(255,255,255,0.07)] p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-[14px] font-black tracking-[-0.01em] text-white">Ajouter un drapeau</h2>
            <button
              onClick={onClose}
              className="flex h-[26px] w-[26px] items-center justify-center rounded-lg text-[rgba(255,255,255,0.35)] transition-colors hover:bg-[rgba(255,255,255,0.07)] hover:text-white"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" width={12} height={12}>
                <path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
              </svg>
            </button>
          </div>
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Chercher — un nom, ou ce que ça fait (« pause », « code cache »)"
            className="h-[34px] w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 text-[12px] text-white outline-none placeholder:text-[rgba(255,255,255,0.25)] focus:border-[rgba(75,63,207,0.6)]"
          />
          <div className="flex flex-wrap gap-1">
            {CATEGORY_FILTERS.map((c) => (
              <button
                key={c.id}
                onClick={() => setFilter(c.id)}
                className={`h-[24px] rounded-lg border px-2.5 text-[10px] font-semibold transition-colors ${
                  filter === c.id
                    ? 'border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.3)] text-white'
                    : 'border-[rgba(255,255,255,0.08)] bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.42)] hover:border-white/25'
                }`}
              >
                {c.label}
              </button>
            ))}
            <span className="ml-auto self-center text-[10px] text-[rgba(255,255,255,0.28)]">
              Le drapeau part dans sa catégorie, pas dans celle affichée.
            </span>
          </div>
        </div>

        <div className="flex-1 overflow-auto">
          {results.length === 0 && (
            <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
              <p className="text-[12px] text-[rgba(255,255,255,0.35)]">Aucun drapeau documenté ne correspond.</p>
              {manualLooksLikeFlag && (
                <button
                  onClick={() => { onAdd(manual); setQuery('') }}
                  className="h-[30px] rounded-xl border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-3.5 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)]"
                >
                  Ajouter <span className="font-mono">{manual}</span> quand même
                </button>
              )}
              {!manualLooksLikeFlag && (
                <p className="max-w-sm text-[10px] leading-relaxed text-[rgba(255,255,255,0.25)]">
                  Le catalogue ne couvre pas tout : tape le drapeau complet (en commençant par un tiret) pour l'ajouter
                  tel quel.
                </p>
              )}
            </div>
          )}

          {results.map((d) => {
            const already = present.has(d.name)
            return (
              <div
                key={d.name}
                className="flex flex-col gap-1.5 border-b border-[rgba(255,255,255,0.05)] px-4 py-3 last:border-b-0 hover:bg-[rgba(255,255,255,0.02)]"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-[12px] font-semibold text-[rgba(255,255,255,0.9)]">{d.name}</span>
                  <span className="rounded-md bg-[rgba(255,255,255,0.05)] px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-[0.08em] text-[rgba(255,255,255,0.35)]">
                    {CATEGORY_META[d.category].label}
                  </span>
                  {d.experimental && (
                    <span
                      className="rounded-md bg-[rgba(240,180,90,0.14)] px-1.5 py-0.5 text-[9px] font-semibold text-[rgba(240,180,90,0.85)]"
                      title="Exige -XX:+UnlockExperimentalVMOptions dans la config, sinon la JVM refuse de démarrer."
                    >
                      expérimental
                    </span>
                  )}
                  {already && (
                    <span className="rounded-md bg-[rgba(75,63,207,0.22)] px-1.5 py-0.5 text-[9px] font-semibold text-[rgba(180,172,255,0.9)]">
                      déjà dans la config
                    </span>
                  )}
                  <span className="ml-auto text-[10px] text-[rgba(255,255,255,0.3)]">défaut&nbsp;: {d.default}</span>
                </div>

                <p className="text-[11px] leading-relaxed text-[rgba(255,255,255,0.45)]">{d.hint}</p>

                <div className="flex flex-wrap items-center gap-1.5">
                  {d.range && (
                    <span className="text-[10px] text-[rgba(255,255,255,0.3)]">
                      Utile&nbsp;: <span className="font-mono text-[rgba(150,140,240,0.8)]">{d.range}</span>
                    </span>
                  )}
                  <div className="ml-auto flex items-center gap-1.5">
                    {d.kind === 'value' ? (
                      <>
                        <input
                          value={values[d.name] ?? ''}
                          onChange={(e) => setValues((s) => ({ ...s, [d.name]: e.target.value }))}
                          onKeyDown={(e) => { if (e.key === 'Enter') addDoc(d) }}
                          spellCheck={false}
                          placeholder="valeur"
                          className="h-[26px] w-[120px] rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 font-mono text-[11px] text-white outline-none placeholder:font-sans placeholder:text-[rgba(255,255,255,0.22)] focus:border-[rgba(75,63,207,0.7)]"
                        />
                        <button
                          onClick={() => addDoc(d)}
                          disabled={!(values[d.name] ?? '').trim()}
                          className="h-[26px] rounded-lg border border-[rgba(75,63,207,0.6)] bg-[rgba(75,63,207,0.35)] px-2.5 text-[10px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.5)] disabled:opacity-30"
                        >
                          Ajouter
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          onClick={() => addDoc(d, '+')}
                          className="h-[26px] rounded-lg border border-[rgba(75,63,207,0.6)] bg-[rgba(75,63,207,0.35)] px-2.5 text-[10px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.5)]"
                        >
                          Activer
                        </button>
                        <button
                          onClick={() => addDoc(d, '-')}
                          className="h-[26px] rounded-lg border border-[rgba(255,255,255,0.12)] bg-[rgba(255,255,255,0.05)] px-2.5 text-[10px] font-bold text-[rgba(255,255,255,0.5)] transition-colors hover:border-white/25"
                        >
                          Désactiver
                        </button>
                      </>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
