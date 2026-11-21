import { useState } from 'react'
import { useT } from '@/i18n'
import { useStorePick } from '@/stores/useStore'
import { HORIZON_NIGHTLY, horizonVersionName } from '@/lib/horizonVersion'
import type { LoaderVersion } from '@/types'

/**
 * Le choix de la version d'Horizon, dans l'onglet Installation.
 *
 * Horizon ne publie plus que des Nightly : une liste de builds bruts
 * (`nightly-2026-11-21-f037744`) ne dit rien à personne. On propose donc
 * d'abord le **canal** — Stable (une version par semaine) ou Nightly (la
 * dernière) —, chacun avec ce qu'il implique, et seulement ensuite, replié,
 * une version précise pour qui veut figer son instance.
 */
export function HorizonVersionPicker({ value, onChange, versions }: {
  /** `''` Stable, `'nightly'`, ou un build précis. */
  value: string
  onChange: (value: string) => void
  versions: LoaderVersion[]
}) {
  const t = useT()
  const { language } = useStorePick('language')
  const name = (v: string) => horizonVersionName(v, language, t)
  const stableNow = versions.find((v) => v.recommended)?.version ?? null
  const nightlyNow = versions.find((v) => /^nightly-/.test(v.version))?.version ?? null
  const exact = value !== '' && value !== HORIZON_NIGHTLY
  const [showExact, setShowExact] = useState(exact)

  return (
    <div className="flex flex-col gap-2">
      <div className="grid gap-2 sm:grid-cols-2">
        <ChannelCard
          selected={value === ''}
          onSelect={() => { onChange(''); setShowExact(false) }}
          title={t('instancesPage.horizon.stable')}
          tag={t('instancesPage.horizon.recommendedTag')}
          hint={t('instancesPage.horizon.stableHint')}
          now={stableNow ? t('instancesPage.horizon.currently', { version: name(stableNow) }) : null}
        />
        <ChannelCard
          selected={value === HORIZON_NIGHTLY}
          onSelect={() => { onChange(HORIZON_NIGHTLY); setShowExact(false) }}
          title={t('instancesPage.horizon.nightly')}
          hint={t('instancesPage.horizon.nightlyHint')}
          now={nightlyNow ? t('instancesPage.horizon.currently', { version: name(nightlyNow) }) : null}
        />
      </div>

      {/* Une version figée : repliée, c'est un choix de connaisseur. */}
      {!showExact ? (
        <button
          type="button"
          onClick={() => setShowExact(true)}
          className="w-fit rounded-lg px-1 py-0.5 text-[11.5px] font-semibold text-txt-muted transition-colors hover:text-txt-primary"
        >
          {t('instancesPage.horizon.chooseExact')} →
        </button>
      ) : (
        <div className={`flex flex-col gap-1.5 rounded-xl border p-3 transition-colors ${exact ? 'border-accent/60 bg-accent/10' : 'border-line bg-surface-1'}`}>
          <p className="text-[12px] font-semibold text-txt-primary">{t('instancesPage.horizon.exact')}</p>
          <select
            value={exact ? value : ''}
            onChange={(e) => onChange(e.target.value)}
            className="h-9 rounded-lg border border-line bg-surface-1 px-2.5 text-[12.5px] text-txt-primary outline-none focus:border-accent/60"
          >
            <option value="" disabled>{t('instancesPage.horizon.pickOne')}</option>
            {versions.map((v) => (
              <option key={v.version} value={v.version}>{name(v.version)}</option>
            ))}
          </select>
          <p className="text-[11px] leading-relaxed text-txt-muted">{t('instancesPage.horizon.exactHint')}</p>
        </div>
      )}
    </div>
  )
}

function ChannelCard({ selected, onSelect, title, tag, hint, now }: {
  selected: boolean
  onSelect: () => void
  title: string
  tag?: string
  hint: string
  now: string | null
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={`flex flex-col gap-1 rounded-xl border p-3 text-left transition-[background-color,border-color,transform] duration-150 active:scale-[0.98] ${
        selected ? 'border-accent/70 bg-accent/15' : 'border-line bg-surface-1 hover:border-line-strong'
      }`}
    >
      <span className="flex items-center gap-2">
        <span className={`flex h-4 w-4 flex-shrink-0 items-center justify-center rounded-full border-2 transition-colors ${selected ? 'border-accent' : 'border-line-strong'}`}>
          <span className={`h-2 w-2 rounded-full bg-accent transition-transform duration-150 ${selected ? 'scale-100' : 'scale-0'}`} />
        </span>
        <span className="text-[13px] font-semibold text-txt-primary">{title}</span>
        {tag && <span className="rounded-full bg-accent/20 px-2 py-px text-[10px] font-bold text-[rgb(var(--accent-text))]">{tag}</span>}
      </span>
      <span className="text-[11.5px] leading-relaxed text-txt-secondary">{hint}</span>
      {now && <span className="text-[11px] text-txt-muted">{now}</span>}
    </button>
  )
}

/** Ce que l'onglet affiche en lecture pour la valeur enregistrée. */
export function horizonChoiceLabel(
  value: string,
  versions: LoaderVersion[] | null,
  language: string,
  t: ReturnType<typeof useT>,
): string {
  if (value === HORIZON_NIGHTLY) return t('instancesPage.horizon.nightlyChosen')
  if (value) return horizonVersionName(value, language, t)
  const stableNow = versions?.find((v) => v.recommended)?.version
  return stableNow
    ? t('instancesPage.horizon.stableChosenWith', { version: horizonVersionName(stableNow, language, t) })
    : t('instancesPage.horizon.stable')
}
