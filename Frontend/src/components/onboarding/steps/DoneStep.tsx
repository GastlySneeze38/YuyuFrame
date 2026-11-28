import { motion } from 'framer-motion'
import { useT } from '@/i18n'
import { Item, Primary, Stagger } from '../ui'

/**
 * La fin : un cercle qui se trace, la coche qui se pose, une onde qui part
 * une fois. Puis l'entrée dans le launcher.
 */
export function DoneStep({ onEnter, summary }: { onEnter: () => void; summary: string[] }) {
  const t = useT()
  return (
    <Stagger className="flex flex-col items-center gap-5 py-2 text-center">
      <Item className="relative flex h-28 w-28 items-center justify-center">
        <motion.span
          className="absolute inset-0 rounded-full border-2 border-[rgb(var(--accent))]"
          initial={{ scale: 0.7, opacity: 0.6 }}
          animate={{ scale: 1.9, opacity: 0 }}
          transition={{ duration: 1.3, delay: 0.9, ease: [0.16, 1, 0.3, 1] }}
        />
        <span className="absolute inset-3 rounded-full bg-[rgb(var(--accent)/0.22)] blur-xl" />
        <svg viewBox="0 0 100 100" className="relative h-28 w-28 -rotate-90">
          <circle cx="50" cy="50" r="44" fill="none" stroke="rgb(255 255 255 / 0.08)" strokeWidth="5" />
          <circle
            cx="50"
            cy="50"
            r="44"
            fill="none"
            stroke="rgb(var(--accent))"
            strokeWidth="5"
            strokeLinecap="round"
            strokeDasharray="277"
            strokeDashoffset="277"
            className="ob-draw"
          />
        </svg>
        <svg viewBox="0 0 24 24" className="absolute h-11 w-11" fill="none" stroke="white" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 12.5l4.5 4.5L19 7.5" strokeDasharray="24" strokeDashoffset="24" className="ob-draw-late" />
        </svg>
      </Item>

      {summary.length > 0 && (
        <Item className="flex flex-wrap justify-center gap-1.5">
          {summary.map((s) => (
            <span key={s} className="rounded-full border border-white/10 bg-white/[0.06] px-3 py-1 text-[12px] font-semibold text-white/75">
              ✓ {s}
            </span>
          ))}
        </Item>
      )}

      <Item className="w-full">
        <Primary onClick={onEnter}>{t('onboarding.done.enter')}</Primary>
      </Item>
    </Stagger>
  )
}
