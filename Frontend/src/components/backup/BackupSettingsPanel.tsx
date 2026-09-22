import { motion } from 'framer-motion'
import { Toggle } from '@/components/ui/Toggle'
import { press } from '@/lib/motion'
import { useT } from '@/i18n'
import type { BackupSettings } from '@/types/backup'

/**
 * Réglages de sauvegarde, généraux ou propres à une instance.
 *
 * Les mêmes champs aux deux niveaux : une instance suit le réglage général
 * tant qu'on n'y touche pas, et le moment où elle s'en détache est un clic
 * explicite. Sans ça, régler une instance figerait silencieusement toutes les
 * valeurs du jour, et changer le général plus tard n'aurait aucun effet
 * dessus — exactement le genre de surprise qu'on découvre le jour où la
 * sauvegarde manque.
 */

function Line({
  label,
  hint,
  checked,
  onChange,
  disabled,
}: {
  label: string
  hint?: string
  checked: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
}) {
  return (
    <div className={`flex items-center justify-between gap-4 ${disabled ? 'opacity-45' : ''}`}>
      <div className="min-w-0">
        <p className="text-[12.5px] font-medium text-txt-primary">{label}</p>
        {hint && <p className="mt-0.5 text-[11px] leading-relaxed text-txt-muted">{hint}</p>}
      </div>
      <Toggle checked={checked} onChange={() => !disabled && onChange(!checked)} />
    </div>
  )
}

export function BackupSettingsPanel({
  settings,
  onChange,
  custom,
  onReset,
  scope,
}: {
  settings: BackupSettings
  onChange: (next: BackupSettings) => void
  /** Pour une instance : a-t-elle son propre réglage ? */
  custom?: boolean
  onReset?: () => void
  scope: 'global' | 'instance'
}) {
  const t = useT()
  const set = (patch: Partial<BackupSettings>) => onChange({ ...settings, ...patch })
  const off = !settings.enabled

  return (
    <div className="flex flex-col gap-4 rounded-2xl border border-line bg-surface-1 p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-[13px] font-semibold">{t(`backup.settings.${scope}Title`)}</p>
          <p className="mt-0.5 text-[11px] leading-relaxed text-txt-muted">{t(`backup.settings.${scope}Text`)}</p>
        </div>
        {scope === 'instance' && custom && onReset && (
          <motion.button
            {...press}
            onClick={onReset}
            className="shrink-0 rounded-lg border border-line px-2.5 py-1 text-[11px] font-semibold text-txt-secondary transition-colors duration-150 hover:border-accent/45 hover:text-txt-primary"
          >
            {t('backup.settings.followGlobal')}
          </motion.button>
        )}
      </div>

      <Line label={t('backup.settings.enabled')} hint={t('backup.settings.enabledHint')} checked={settings.enabled} onChange={(v) => set({ enabled: v })} />

      <div className="h-px bg-line-soft" />

      <p className="text-[11px] font-bold uppercase tracking-[0.07em] text-txt-muted">{t('backup.settings.whatSection')}</p>
      <Line
        label={t('backup.settings.worlds')}
        hint={t('backup.settings.worldsHint')}
        checked={settings.include_worlds}
        onChange={(v) => set({ include_worlds: v })}
        disabled={off}
      />
      <Line label={t('backup.settings.configs')} hint={t('backup.settings.configsHint')} checked={settings.include_configs} onChange={(v) => set({ include_configs: v })} disabled={off} />
      <Line label={t('backup.settings.mods')} hint={t('backup.settings.modsHint')} checked={settings.include_mods} onChange={(v) => set({ include_mods: v })} disabled={off} />

      <div className="h-px bg-line-soft" />

      <p className="text-[11px] font-bold uppercase tracking-[0.07em] text-txt-muted">{t('backup.settings.whenSection')}</p>
      <Line label={t('backup.settings.onLaunch')} hint={t('backup.settings.onLaunchHint')} checked={settings.on_launch} onChange={(v) => set({ on_launch: v })} disabled={off} />
      <Line label={t('backup.settings.daily')} hint={t('backup.settings.dailyHint')} checked={settings.daily} onChange={(v) => set({ daily: v })} disabled={off} />

      <div className="h-px bg-line-soft" />

      <div className={`flex items-center justify-between gap-4 ${off ? 'opacity-45' : ''}`}>
        <div className="min-w-0">
          <p className="text-[12.5px] font-medium text-txt-primary">{t('backup.settings.keep')}</p>
          <p className="mt-0.5 text-[11px] leading-relaxed text-txt-muted">
            {settings.keep === 0 ? t('backup.settings.keepUnlimited') : t('backup.settings.keepHint')}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {[5, 10, 25, 0].map((n) => (
            <motion.button
              key={n}
              {...press}
              onClick={() => !off && set({ keep: n })}
              className={`h-7 rounded-lg border px-2.5 text-[11px] font-semibold transition-colors duration-150 ${
                settings.keep === n ? 'border-accent/45 bg-accent/15 text-txt-primary' : 'border-line text-txt-secondary hover:text-txt-primary'
              }`}
            >
              {n === 0 ? t('backup.settings.keepAll') : n}
            </motion.button>
          ))}
        </div>
      </div>
    </div>
  )
}
