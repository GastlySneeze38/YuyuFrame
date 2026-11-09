import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { SkinFace } from '@/components/ui/SkinFace'
import { playerSkinSrc } from '@/lib/heads'
import { formatDate } from '@/lib/format'
import type { DuelRow } from '@/lib/competitive'

const RESULT_STYLE: Record<DuelRow['result'], string> = {
  win: 'bg-success/[0.14] text-success',
  loss: 'bg-danger/[0.14] text-danger',
  draw: 'bg-white/[0.06] text-txt-secondary',
}

function formatWhen(at: number, language: string): string {
  const locale = language === 'sga' ? 'fr' : language
  return formatDate(at, locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
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
        // Seule la liste défile, jamais la page. La liste est un conteneur :
        // chaque ligne se règle sur SA largeur (cw-*), pas sur la fenêtre.
        // Étroite, elle garde l'essentiel (résultat, adversaire, RR) ; plus
        // elle s'élargit, plus elle ajoute : la note, puis « Revoir », puis
        // des colonnes plus aérées.
        <div className="-mx-1 flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto overflow-x-hidden px-1 [container-type:inline-size]">
          {duels.map((d, i) => (
            <motion.div
              key={d.id}
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: Math.min(i, 12) * 0.025 }}
              className="flex min-w-0 items-center gap-2 rounded-xl px-1.5 py-2 hover:bg-white/[0.03] cw-lg:gap-3 cw-lg:px-2.5"
            >
              <span
                className={`w-[58px] shrink-0 rounded-md py-1 text-center text-[9.5px] font-black uppercase tracking-[0.03em] cw-md:w-[66px] cw-md:text-[10.5px] cw-lg:w-[74px] ${RESULT_STYLE[d.result]}`}
              >
                {t(`competitive.history.${d.result}`)}
              </span>
              <SkinFace dataUri={playerSkinSrc(d.opponentUuid, d.opponent)} size={22} className="shrink-0 rounded-[4px]" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[12px] font-semibold cw-md:text-[12.5px]">{t('competitive.history.vs', { name: d.opponent })}</p>
                <p className="truncate text-[10.5px] text-txt-muted">
                  {formatWhen(d.at, language)} · {formatLength(d.durationSecs)} · {d.server}
                </p>
              </div>
              {/* La note : absente tout en étroit, le chiffre seul ensuite, « / 500 » quand il y a la place. */}
              <span className="hidden shrink-0 text-right text-[11.5px] tabular-nums text-txt-secondary cw-sm:inline cw-sm:w-[30px] cw-md:w-[58px]">
                <span className="cw-md:hidden">{d.score}</span>
                <span className="hidden cw-md:inline">{t('competitive.history.score', { score: d.score })}</span>
              </span>
              <span
                // Une largeur MINIMALE et non fixe : « PLACEMENT » (et ses
                // traductions) est plus large qu'un « +18 RR » et débordait
                // sur le bouton « Revoir ».
                className={`min-w-[50px] shrink-0 whitespace-nowrap text-right text-[12px] font-black tabular-nums cw-md:min-w-[62px] cw-md:text-[12.5px] cw-lg:min-w-[78px] ${
                  d.placement ? 'text-[10px] uppercase tracking-[0.05em] text-accent-hover' : d.rr === null ? 'text-txt-muted' : d.rr > 0 ? 'text-success' : d.rr < 0 ? 'text-danger' : 'text-txt-secondary'
                }`}
              >
                {d.placement ? t('competitive.history.placement') : d.rr === null ? '—' : `${d.rr > 0 ? '+' : ''}${d.rr} RR`}
              </span>
              {/* Revoir un duel : prévu (façon replay), pas encore fait. La
                  bulle est sur l'enveloppe : un bouton désactivé ne reçoit
                  pas le survol. */}
              <span title={t('competitive.history.replaySoon')} className="hidden shrink-0 cursor-not-allowed cw-md:inline">
                <button
                  type="button"
                  disabled
                  className="pointer-events-none flex items-center gap-1 rounded-lg bg-white/[0.04] px-2 py-1.5 text-[11px] font-semibold text-white/25"
                >
                  <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
                  <span className="hidden cw-lg:inline">{t('competitive.history.replay')}</span>
                </button>
              </span>
            </motion.div>
          ))}
        </div>
      )}
    </div>
  )
}
