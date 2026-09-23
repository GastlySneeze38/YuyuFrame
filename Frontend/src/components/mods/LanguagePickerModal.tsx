import { useMemo, useState } from 'react'
import { ModalShell } from '@/components/ui/ModalShell'
import { Flag } from '@/components/ui/Flag'
import { SearchIcon } from '@/components/ui/icons/SearchIcon'
import { useT } from '@/i18n'
import { GAME_LANGUAGES, LANGUAGE_BY_CODE } from './gameLanguages'
import { normalize } from './optionSearch'

/**
 * Choix de la langue du jeu.
 *
 * Une saisie libre marchait, à condition de connaître par cœur le code exact
 * — `pt_br` et non `pt_BR`, `zh_cn` et non `zh_CN`. Une faute ne prévient
 * pas : Minecraft repart simplement en anglais au lancement suivant, sans
 * qu'on sache pourquoi.
 *
 * La recherche accepte le nom natif, le nom anglais et le code, pour la même
 * raison que l'écran des réglages : on dit « german » en ayant l'interface
 * en français.
 *
 * Une valeur hors de la liste (une des cent langues qu'on ne propose pas,
 * ou un code déjà présent dans le fichier) n'est jamais écrasée : elle
 * apparaît en tête, marquée comme telle, et reste sélectionnée tant qu'on n'a
 * pas choisi autre chose.
 */

export function LanguagePickerModal({ current, onPick, onClose }: {
  current: string
  onPick: (code: string) => void
  onClose: () => void
}) {
  const t = useT()
  const [query, setQuery] = useState('')

  const results = useMemo(() => {
    const q = normalize(query)
    if (!q) return GAME_LANGUAGES
    return GAME_LANGUAGES.filter((l) =>
      normalize(l.native).includes(q) || normalize(l.english).includes(q) || normalize(l.code).includes(q),
    )
  }, [query])

  const unknown = current && !LANGUAGE_BY_CODE.has(current) ? current : null

  return (
    <ModalShell title={t('options.langPickerTitle')} onClose={onClose} maxWidth="max-w-2xl">
      <div className="flex flex-col gap-3">
        <div className="relative">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2">
            <SearchIcon size={14} color="rgba(255,255,255,0.3)" />
          </span>
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('options.langPickerSearch')}
            className="h-9 w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.45)] pl-9 pr-3 text-[13px] text-white outline-none transition-colors duration-150 focus:border-[rgba(75,63,207,0.5)]"
          />
        </div>

        {unknown && !query && (
          <button
            onClick={() => { onPick(unknown); onClose() }}
            className="flex items-center gap-3 rounded-xl border border-[rgba(75,63,207,0.5)] bg-[rgba(75,63,207,0.2)] px-3 py-2.5 text-left"
          >
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-[12.5px] font-semibold text-white">{unknown}</span>
              <span className="text-[10.5px] text-[rgba(255,255,255,0.4)]">{t('options.langUnknown')}</span>
            </div>
          </button>
        )}

        <div className="grid max-h-[50vh] grid-cols-[repeat(auto-fill,minmax(190px,1fr))] gap-1.5 overflow-y-auto pr-1">
          {results.map((lang) => {
            const active = lang.code === current
            return (
              <button
                key={lang.code}
                onClick={() => { onPick(lang.code); onClose() }}
                className={`flex items-center gap-2.5 rounded-xl border px-2.5 py-2 text-left transition-colors duration-150 ${
                  active
                    ? 'border-[rgba(75,63,207,0.6)] bg-[rgba(75,63,207,0.28)]'
                    : 'border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.02)] hover:border-[rgba(75,63,207,0.35)] hover:bg-[rgba(75,63,207,0.1)]'
                }`}
              >
                <Flag spec={lang.flag} size={20} />
                <div className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-[12px] font-semibold text-[rgba(255,255,255,0.88)]">
                    {lang.native}
                  </span>
                  <span className="truncate font-mono text-[10px] text-[rgba(255,255,255,0.3)]">{lang.code}</span>
                </div>
                {active && (
                  <svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13} className="flex-shrink-0 text-[rgba(180,170,255,0.9)]">
                    <path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" />
                  </svg>
                )}
              </button>
            )
          })}
        </div>

        {results.length === 0 && (
          <p className="py-6 text-center text-[12px] text-[rgba(255,255,255,0.3)]">
            {t('options.langPickerNothing')}
          </p>
        )}
      </div>
    </ModalShell>
  )
}
