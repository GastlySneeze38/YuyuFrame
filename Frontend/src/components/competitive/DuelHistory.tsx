import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { SkinFace } from '@/components/ui/SkinFace'
import type { DuelRow } from '@/lib/competitive'

const RESULT_STYLE: Record<DuelRow['result'], string> = {
  win: 'bg-success/[0.14] text-success',
  loss: 'bg-danger/[0.14] text-danger',
  draw: 'bg-white/[0.06] text-txt-secondary',
}

function formatWhen(at: number, language: string): string {
  const locale = language === 'sga' ? 'fr' : language
  return new Date(at).toLocaleString(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export function formatLength(secs: number): string {
  return `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`
}

/**
 * L'historique des duels classés d'un mode, du plus récent au plus ancien.
 *
 * Le bouton « Revoir » est déjà là, grisé : revoir un duel façon replay est
 * prévu, et sa place dans la ligne aussi.
 *
 * `duels === null` : le serveur n'a pas encore de route pour ça.
 */
export function DuelHistory({ duels }: { duels: DuelRow[] | null }) {
  const t = useT()
  const language = useStore((s) => s.language)

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-5">
      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-txt-muted">{t('competitive.history.title')}</span>

      {duels === null ? (
        <p className="py-10 text-center text-[12px] leading-relaxed text-txt-muted">{t('competitive.history.soon')}</p>
      ) : duels.length === 0 ? (
        <p className="py-10 text-center text-[12px] text-txt-muted">{t('competitive.history.empty')}</p>
      ) : (
        // Seule la liste défile, jamais la page.
        <div className="-mx-1 flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-1">
          {duels.map((d, i) => (
            <motion.div
              key={d.id}
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: Math.min(i, 12) * 0.025 }}
              className="flex items-center gap-3 rounded-xl px-2.5 py-2 hover:bg-white/[0.03]"
            >
              <span className={`w-[74px] shrink-0 rounded-md py-1 text-center text-[10.5px] font-black uppercase tracking-[0.04em] ${RESULT_STYLE[d.result]}`}>
                {t(`competitive.history.${d.result}`)}
              </span>
              <SkinFace dataUri={`https://mc-heads.net/skin/${encodeURIComponent(d.opponent)}`} size={22} className="rounded-[4px]" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[12.5px] font-semibold">{t('competitive.history.vs', { name: d.opponent })}</p>
                <p className="truncate text-[10.5px] text-txt-muted">
                  {formatWhen(d.at, language)} · {formatLength(d.durationSecs)} · {d.server}
                </p>
              </div>
              <span className="w-[58px] shrink-0 text-right text-[11.5px] tabular-nums text-txt-secondary">
                {t('competitive.history.score', { score: d.score })}
              </span>
              <span
                className={`w-[78px] shrink-0 text-right text-[12.5px] font-black tabular-nums ${
                  d.placement ? 'text-[10px] uppercase tracking-[0.05em] text-accent-hover' : d.rr === null ? 'text-txt-muted' : d.rr > 0 ? 'text-success' : d.rr < 0 ? 'text-danger' : 'text-txt-secondary'
                }`}
              >
                {d.placement ? t('competitive.history.placement') : d.rr === null ? '—' : `${d.rr > 0 ? '+' : ''}${d.rr} RR`}
              </span>
              {/* Revoir un duel : prévu (façon replay), pas encore fait. La
                  bulle est sur l'enveloppe : un bouton désactivé ne reçoit
                  pas le survol. */}
              <span title={t('competitive.history.replaySoon')} className="shrink-0 cursor-not-allowed">
                <button
                  type="button"
                  disabled
                  className="pointer-events-none flex items-center gap-1 rounded-lg bg-white/[0.04] px-2 py-1.5 text-[11px] font-semibold text-white/25"
                >
                  <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
                  {t('competitive.history.replay')}
                </button>
              </span>
            </motion.div>
          ))}
        </div>
      )}
    </div>
  )
}
