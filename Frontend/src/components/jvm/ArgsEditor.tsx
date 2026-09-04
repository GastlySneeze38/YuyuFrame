import { useEffect, useRef, useState } from 'react'
import { jvmArgKey, parseArgEntries, serializeArgEntries, type JvmArgEntry } from '@/lib/jvmFlags'
import { flagDoc } from '@/lib/jvmCatalog'

/**
 * Liste des drapeaux d'une catégorie.
 *
 * Le nom d'un drapeau ne se tape jamais ici : il arrive du catalogue
 * (`FlagPicker`), et une fois posé il n'est plus modifiable — seule sa valeur
 * l'est. Une faute de frappe dans un nom empêche la JVM de démarrer sans dire
 * lequel des vingt drapeaux est fautif, alors qu'une valeur erronée n'est au
 * pire qu'un mauvais réglage. Or c'est précisément la valeur qu'on fait varier
 * d'un test à l'autre.
 *
 * Chaque ligne rappelle ce que fait le drapeau et sa valeur par défaut : sans
 * ça, relire sa propre config trois jours plus tard demande de retourner
 * chercher la documentation de chaque ligne.
 *
 * Le stockage reste le texte d'avant (un drapeau par ligne) : le backend n'a
 * pas changé.
 */
export function ArgsEditor({ value, onChange }: {
  value: string
  onChange: (v: string) => void
}) {
  // Les entrées vivent en état local plutôt que d'être redérivées du texte à
  // chaque frappe : vider le champ valeur d'un `-Xmx` produirait le texte
  // `-Xmx`, qui se relit comme un drapeau SANS valeur — le champ de saisie
  // disparaîtrait sous les doigts. On ne resynchronise donc que sur un
  // changement venu de l'extérieur (ajout depuis le catalogue, jeu appliqué).
  const [entries, setEntries] = useState<JvmArgEntry[]>(() => parseArgEntries(value))
  const lastSerialized = useRef(value)

  useEffect(() => {
    if (value !== lastSerialized.current) {
      setEntries(parseArgEntries(value))
      lastSerialized.current = value
    }
  }, [value])

  const commit = (next: JvmArgEntry[]) => {
    setEntries(next)
    const text = serializeArgEntries(next)
    lastSerialized.current = text
    onChange(text)
  }

  const update = (id: number, patch: Partial<JvmArgEntry>) =>
    commit(entries.map((e) => (e.id === id ? { ...e, ...patch } : e)))

  const remove = (id: number) => commit(entries.filter((e) => e.id !== id))

  // Un même réglage posé deux fois : le dernier gagne, mais c'est presque
  // toujours une coquille. Signalé sur la ligne, là où on peut la corriger.
  const keyCounts = new Map<string, number>()
  for (const e of entries) {
    if (e.kind === 'comment') continue
    const k = jvmArgKey(serializeArgEntries([e]))
    keyCounts.set(k, (keyCounts.get(k) ?? 0) + 1)
  }

  return (
    <div className="flex flex-col gap-2">
      {entries.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-[rgba(255,255,255,0.1)] px-4 py-6 text-center">
          <p className="text-[11px] text-[rgba(255,255,255,0.28)]">Aucun drapeau.</p>
        </div>
      ) : (
        <div className="flex flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.09)]">
          {entries.map((e) => {
            if (e.kind === 'comment') {
              return (
                <Row key={e.id} onRemove={() => remove(e.id)}>
                  <span className="flex-1 truncate font-mono text-[11px] italic text-[rgba(255,255,255,0.3)]">
                    {e.value}
                  </span>
                </Row>
              )
            }
            const doc = flagDoc(e.name)
            const duplicated = (keyCounts.get(jvmArgKey(serializeArgEntries([e]))) ?? 0) > 1
            const off = e.kind === 'boolean' && e.value === '-'
            return (
              <Row key={e.id} onRemove={() => remove(e.id)} warn={duplicated}>
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <div className="flex items-center gap-2">
                    {e.kind === 'boolean' && (
                      <div className="flex flex-shrink-0 overflow-hidden rounded-md border border-[rgba(255,255,255,0.12)]">
                        {(['+', '-'] as const).map((sign) => (
                          <button
                            key={sign}
                            onClick={() => update(e.id, { value: sign })}
                            title={sign === '+' ? 'Activé' : 'Désactivé'}
                            className={`h-[22px] w-[24px] text-[12px] font-bold leading-none transition-colors ${
                              e.value === sign
                                ? sign === '+'
                                  ? 'bg-[rgba(75,63,207,0.5)] text-white'
                                  : 'bg-[rgba(220,90,90,0.35)] text-white'
                                : 'bg-[rgba(0,0,0,0.3)] text-[rgba(255,255,255,0.3)] hover:text-[rgba(255,255,255,0.6)]'
                            }`}
                          >
                            {sign}
                          </button>
                        ))}
                      </div>
                    )}

                    <span
                      className={`min-w-0 truncate font-mono text-[12px] ${
                        off ? 'text-[rgba(255,255,255,0.35)] line-through' : 'text-[rgba(255,255,255,0.85)]'
                      }`}
                      title={e.name}
                    >
                      {e.name}
                    </span>

                    {e.kind === 'value' && (
                      <>
                        <span className="flex-shrink-0 font-mono text-[11px] text-[rgba(255,255,255,0.25)]">
                          {e.sep || '·'}
                        </span>
                        <input
                          value={e.value}
                          onChange={(ev) => update(e.id, { value: ev.target.value })}
                          spellCheck={false}
                          className="h-[24px] w-[130px] flex-shrink-0 rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 font-mono text-[11px] text-[rgba(190,183,255,0.95)] outline-none focus:border-[rgba(75,63,207,0.7)]"
                        />
                        {doc && (
                          <span className="flex-shrink-0 text-[10px] text-[rgba(255,255,255,0.28)]">
                            défaut {doc.default}
                            {doc.range && <span className="text-[rgba(255,255,255,0.2)]"> · utile {doc.range}</span>}
                          </span>
                        )}
                      </>
                    )}

                    {e.kind === 'boolean' && doc && (
                      <span className="flex-shrink-0 text-[10px] text-[rgba(255,255,255,0.28)]">
                        défaut {doc.default}
                      </span>
                    )}

                    {duplicated && (
                      <span
                        className="flex-shrink-0 text-[10px] font-semibold text-[rgba(240,180,90,0.85)]"
                        title="Ce réglage est défini plusieurs fois — le dernier gagne."
                      >
                        en double
                      </span>
                    )}
                  </div>

                  <p className="truncate text-[10px] leading-relaxed text-[rgba(255,255,255,0.32)]" title={doc?.hint}>
                    {doc?.hint ?? 'Drapeau saisi à la main — pas dans le catalogue, non documenté ici.'}
                  </p>
                </div>
              </Row>
            )
          })}
        </div>
      )}
    </div>
  )
}

function Row({ children, onRemove, warn }: {
  children: React.ReactNode
  onRemove: () => void
  warn?: boolean
}) {
  return (
    <div
      className={`flex items-center gap-2 border-b border-[rgba(255,255,255,0.05)] px-3 py-2 last:border-b-0 ${
        warn ? 'bg-[rgba(240,180,90,0.05)]' : 'hover:bg-[rgba(255,255,255,0.02)]'
      }`}
    >
      {children}
      <button
        onClick={onRemove}
        title="Retirer"
        className="flex h-[22px] w-[22px] flex-shrink-0 items-center justify-center rounded-md text-[rgba(255,255,255,0.25)] transition-colors hover:bg-[rgba(220,90,90,0.18)] hover:text-[rgba(255,150,150,0.95)]"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}>
          <path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
        </svg>
      </button>
    </div>
  )
}
