import { useEffect, useRef, useState } from 'react'
import {
  attachArgValue, jvmArgKey, parseArgEntries, parseArgEntry, serializeArgEntries,
  type JvmArgEntry,
} from '@/lib/jvmFlags'

/**
 * Éditeur de drapeaux JVM en liste, à la place d'un champ de texte libre.
 *
 * Le nom d'un drapeau se saisit UNE fois, à l'ajout ; ensuite il n'est plus
 * modifiable, seule sa valeur l'est. C'est le point : une faute de frappe dans
 * un nom (`-XX:MaxGCPauseMilis`) empêche la JVM de démarrer, et le message
 * d'erreur ne dit pas lequel des vingt drapeaux est fautif — alors qu'une
 * valeur erronée reste au pire un mauvais réglage. Or c'est précisément la
 * valeur qu'on fait varier d'un test à l'autre.
 *
 * Le stockage reste le même texte qu'avant (un drapeau par ligne) : le backend
 * n'a pas changé, et un jeu de drapeaux collé depuis une source communautaire
 * se décompose tout seul à l'arrivée.
 */
export function ArgsEditor({ value, onChange, suggestions, listId }: {
  value: string
  onChange: (v: string) => void
  /** Noms proposés en autocomplétion — voir `suggestionsFor`. */
  suggestions: string[]
  /** Identifiant du `<datalist>` : il doit être unique par catégorie affichée. */
  listId: string
}) {
  // Les entrées vivent en état local plutôt que d'être redérivées du texte à
  // chaque frappe : vider le champ valeur d'un `-Xmx` produirait le texte
  // `-Xmx`, qui se relit comme un drapeau SANS valeur — le champ de saisie
  // disparaîtrait sous les doigts. On ne resynchronise donc que sur un
  // changement venu de l'extérieur (clic sur un jeu de drapeaux).
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

  const [newName, setNewName] = useState('')
  const [newValue, setNewValue] = useState('')

  const add = () => {
    const raw = newName.trim()
    if (!raw) return
    // Coller plusieurs drapeaux d'un coup doit marcher : c'est la façon dont on
    // récupère un jeu trouvé ailleurs, et les retaper un par un serait absurde.
    const tokens = raw.split(/\s+/).filter(Boolean)
    const added = tokens.length > 1
      ? tokens.map(parseArgEntry)
      : [parseArgEntry(attachArgValue(raw, newValue.trim()))]
    commit([...entries, ...added])
    setNewName('')
    setNewValue('')
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

  const flagCount = entries.filter((e) => e.kind !== 'comment').length

  return (
    <div className="flex flex-col gap-2">

      {/* ── Ajout ──────────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-1.5 rounded-2xl border border-[rgba(255,255,255,0.09)] bg-[rgba(255,255,255,0.03)] p-2">
        <input
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') add() }}
          list={listId}
          spellCheck={false}
          placeholder="Nom du drapeau — ex : -XX:MaxGCPauseMillis"
          className="h-[32px] min-w-[260px] flex-1 rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 font-mono text-[12px] text-white outline-none placeholder:font-sans placeholder:text-[rgba(255,255,255,0.25)] focus:border-[rgba(75,63,207,0.6)]"
        />
        <datalist id={listId}>
          {suggestions.map((s) => <option key={s} value={s} />)}
        </datalist>
        <input
          value={newValue}
          onChange={(e) => setNewValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') add() }}
          spellCheck={false}
          placeholder="Valeur (si le drapeau en prend une)"
          className="h-[32px] w-[210px] rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-3 font-mono text-[12px] text-white outline-none placeholder:font-sans placeholder:text-[rgba(255,255,255,0.25)] focus:border-[rgba(75,63,207,0.6)]"
        />
        <button
          onClick={add}
          disabled={!newName.trim()}
          className="h-[32px] rounded-xl border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-3.5 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)] disabled:opacity-35 disabled:hover:bg-[rgba(75,63,207,0.4)]"
        >
          Ajouter
        </button>
      </div>
      <p className="-mt-1 text-[10px] leading-relaxed text-[rgba(255,255,255,0.28)]">
        Un drapeau booléen s'écrit avec son signe (<span className="font-mono">-XX:+UseNUMA</span>) et se bascule ensuite
        depuis la liste. Coller plusieurs drapeaux d'un coup fonctionne.
      </p>

      {/* ── Liste ──────────────────────────────────────────────────────────── */}
      {entries.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-[rgba(255,255,255,0.1)] px-4 py-8 text-center">
          <p className="text-[12px] text-[rgba(255,255,255,0.3)]">Aucun drapeau dans cette catégorie.</p>
          <p className="mt-1 text-[10px] text-[rgba(255,255,255,0.22)]">
            Le launcher appliquera ce qu'il génère lui-même.
          </p>
        </div>
      ) : (
        <div className="flex flex-col overflow-hidden rounded-2xl border border-[rgba(255,255,255,0.09)]">
          <div className="flex items-center justify-between gap-2 border-b border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.03)] px-3 py-1.5">
            <span className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.35)]">
              {flagCount} drapeau{flagCount > 1 ? 'x' : ''}
            </span>
            <button
              onClick={() => commit([])}
              className="text-[10px] font-semibold text-[rgba(255,255,255,0.3)] transition-colors hover:text-[rgba(255,150,150,0.9)]"
            >
              Tout retirer
            </button>
          </div>

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
            const duplicated = (keyCounts.get(jvmArgKey(serializeArgEntries([e]))) ?? 0) > 1
            return (
              <Row key={e.id} onRemove={() => remove(e.id)} warn={duplicated}>
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
                  className={`min-w-0 flex-1 truncate font-mono text-[12px] ${
                    e.kind === 'boolean' && e.value === '-'
                      ? 'text-[rgba(255,255,255,0.35)] line-through'
                      : 'text-[rgba(255,255,255,0.85)]'
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
                      className="h-[26px] w-[230px] flex-shrink-0 rounded-lg border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] px-2 font-mono text-[11px] text-[rgba(190,183,255,0.95)] outline-none focus:border-[rgba(75,63,207,0.7)]"
                    />
                  </>
                )}

                {duplicated && (
                  <span className="flex-shrink-0 text-[10px] font-semibold text-[rgba(240,180,90,0.8)]" title="Ce réglage est défini plusieurs fois — le dernier gagne.">
                    en double
                  </span>
                )}
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
      className={`flex items-center gap-2 border-b border-[rgba(255,255,255,0.05)] px-3 py-1.5 last:border-b-0 ${
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
