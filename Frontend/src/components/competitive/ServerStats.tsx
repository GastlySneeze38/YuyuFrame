import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useT } from '@/i18n'
import { useStore } from '@/stores/useStore'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { Button } from '@/components/ui/Button'
import { CloseButton } from '@/components/ui/CloseButton'
import { TopoBackdrop } from './TopoBackdrop'
import { scoreColor, type ServerStats as Stats } from '@/lib/competitive'

/** Combien de serveurs la colonne montre : sans défilement, il n'en tient pas plus. */
const SHOWN = 3

/**
 * L'icône d'un serveur : celle qu'il envoie lui-même dans la réponse au ping
 * (la même que dans la liste des serveurs du jeu). Un ping par adresse et par
 * session, partagé entre tous ceux qui la demandent.
 */
const icons = new Map<string, Promise<string | null>>()

function useServerIcon(address: string): string | null {
  const [icon, setIcon] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    let pending = icons.get(address)
    if (!pending) {
      pending = api.launch
        .pingServer(address)
        .then((info) => info.favicon)
        .catch(() => null)
      icons.set(address, pending)
    }
    void pending.then((found) => alive && setIcon(found))
    return () => {
      alive = false
    }
  }, [address])
  return icon
}

/** Une teinte stable par adresse, pour l'icône de repli. */
function hue(server: string): number {
  let h = 0
  for (const c of server) h = (h * 31 + c.charCodeAt(0)) % 360
  return h
}

/** La lettre de l'icône de repli, sans le préfixe habituel (play., mc.…). */
function initial(server: string): string {
  return server.replace(/^(play|pvp|mc|eu|na)\./, '')[0] ?? '?'
}

/** L'icône seule d'un serveur, à la taille du conteneur (lettre sur un dégradé à défaut). */
export function ServerAvatar({ server }: { server: string }) {
  const icon = useServerIcon(server)
  const h = hue(server)
  return (
    <div className="relative h-full w-full overflow-hidden rounded-[16%]">
      {icon ? (
        <img src={icon} alt="" draggable={false} className="absolute inset-0 h-full w-full [image-rendering:auto]" />
      ) : (
        <span
          className="absolute inset-0 flex items-center justify-center text-[24px] font-black uppercase text-white/30"
          style={{ background: `linear-gradient(135deg, hsl(${h} 55% 40%), hsl(${(h + 40) % 360} 55% 24%))` }}
        >
          {initial(server)}
        </span>
      )}
    </div>
  )
}

/**
 * Les trois meilleurs serveurs du joueur (meilleure note moyenne d'abord) :
 * rien que leur icône, en grand, avec la note moyenne dessus dans sa couleur.
 * Le détail s'ouvre dans une fenêtre au clic, d'où l'on peut filtrer
 * l'historique sur ce serveur.
 *
 * `servers === null` : pas encore de route pour ça.
 */
export function ServerStats({
  servers,
  selected,
  onSelect,
}: {
  servers: Stats[] | null
  selected: string | null
  onSelect: (server: string | null) => void
}) {
  const t = useT()
  const [open, setOpen] = useState<Stats | null>(null)
  const best = servers ? [...servers].sort((a, b) => b.avgScore - a.avgScore).slice(0, SHOWN) : null

  if (best === null || best.length === 0) {
    return (
      <div className="flex h-full items-center justify-center px-2">
        <p className="text-center text-[12px] leading-relaxed text-txt-muted">
          {best === null ? t('competitive.servers.soon') : t('competitive.history.empty')}
        </p>
      </div>
    )
  }

  return (
    <div className="relative flex h-full min-h-0 flex-col">
      {/* Trois serveurs au plus, qui se partagent la hauteur : rien ne défile. */}
      <div className="flex min-h-0 flex-1 flex-col items-center justify-around">
        {best.map((s, i) => (
          <ServerIcon
            key={s.server}
            stats={s}
            index={i}
            active={selected === s.server}
            dimmed={selected !== null && selected !== s.server}
            onClick={() => setOpen(s)}
          />
        ))}
      </div>

      {selected && (
        <button
          onClick={() => onSelect(null)}
          className="shrink-0 pt-2 text-center text-[10.5px] font-semibold text-accent-hover hover:underline"
        >
          {t('competitive.servers.all')}
        </button>
      )}

      <AnimatePresence>
        {open && (
          <ServerModal
            stats={open}
            filtered={selected === open.server}
            onClose={() => setOpen(null)}
            onFilter={() => {
              onSelect(selected === open.server ? null : open.server)
              setOpen(null)
            }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

function ServerIcon({
  stats: s,
  index,
  active,
  dimmed,
  onClick,
}: {
  stats: Stats
  index: number
  active: boolean
  dimmed: boolean
  onClick: () => void
}) {
  const icon = useServerIcon(s.server)
  const color = scoreColor(s.avgScore)
  const h = hue(s.server)
  return (
    <motion.button
      title={s.server}
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: dimmed ? 0.35 : 1, scale: 1 }}
      transition={{ delay: index * 0.06, type: 'spring', stiffness: 260, damping: 20 }}
      whileHover={{ scale: 1.06 }}
      whileTap={{ scale: 0.97 }}
      onClick={onClick}
      className="relative pb-3"
    >
      {/* Le cadre : un fond discret, un liseré, et une lueur de la couleur de la note. */}
      <div
        className={`aspect-square h-[clamp(72px,15vh,120px)] rounded-[22%] border p-[9%] transition-colors ${
          active ? 'border-white/60 bg-white/[0.08]' : 'border-white/[0.09] bg-white/[0.03]'
        }`}
        style={{ boxShadow: `0 0 26px rgb(${color} / 0.16)` }}
      >
        <div className="relative h-full w-full overflow-hidden rounded-[16%]">
          {icon ? (
            // L'icône d'un serveur fait 64 × 64 : agrandie, on la lisse plutôt
            // que de garder ses pixels (ce sont des logos, pas des textures).
            <img src={icon} alt="" draggable={false} className="absolute inset-0 h-full w-full [image-rendering:auto]" />
          ) : (
            // Pas d'icône (serveur muet, hors ligne) : une lettre sur un dégradé.
            <span
              className="absolute inset-0 flex items-center justify-center text-[48px] font-black uppercase text-white/25"
              style={{ background: `linear-gradient(135deg, hsl(${h} 55% 40%), hsl(${(h + 40) % 360} 55% 24%))` }}
            >
              {initial(s.server)}
            </span>
          )}
        </div>
      </div>
      {/* La note dans une pastille sombre à cheval sur le bas du cadre : hors
          de l'icône, elle se lit quelle que soit l'image. */}
      <motion.span
        className="absolute inset-x-0 bottom-0 mx-auto w-fit rounded-full border bg-[rgb(14_13_20)] px-3 py-[3px] text-[clamp(14px,2.4vh,18px)] font-black tabular-nums leading-none"
        style={{ color: `rgb(${color})`, borderColor: `rgb(${color} / 0.45)` }}
        animate={{
          textShadow: [`0 0 6px rgb(${color} / 0.4)`, `0 0 14px rgb(${color} / 0.9)`, `0 0 6px rgb(${color} / 0.4)`],
          boxShadow: [`0 0 8px rgb(${color} / 0.15)`, `0 0 16px rgb(${color} / 0.4)`, `0 0 8px rgb(${color} / 0.15)`],
        }}
        transition={{ duration: 2.4, repeat: Infinity, ease: 'easeInOut', delay: index * 0.4 }}
      >
        {s.avgScore}
      </motion.span>
    </motion.button>
  )
}

/** Le détail d'un serveur. */
function ServerModal({
  stats: s,
  filtered,
  onClose,
  onFilter,
}: {
  stats: Stats
  filtered: boolean
  onClose: () => void
  onFilter: () => void
}) {
  const t = useT()
  const language = useStore((st) => st.language)
  const played = s.wins + s.losses
  const winRate = played ? Math.round((s.wins / played) * 100) : 0
  const color = scoreColor(s.avgScore)
  const last = new Date(s.lastPlayed).toLocaleString(language === 'sga' ? 'fr' : language, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
  const icon = useServerIcon(s.server)
  const h = hue(s.server)
  const rrTone = s.rr > 0 ? 'rgb(var(--success))' : s.rr < 0 ? 'rgb(var(--danger))' : 'rgb(var(--txt-secondary))'

  return (
    <ModalShell
      onClose={onClose}
      maxWidth="max-w-[460px]"
      // `isolate` : le fond animé se glisse sous le contenu sans passer sous la carte.
      cardStyle={{ position: 'relative', overflow: 'hidden', isolation: 'isolate', borderColor: `rgb(${color} / 0.35)` }}
    >
      {/* Le fond : le même relief topographique que la carte du rang, dans la
          couleur de la note, et une lueur derrière le chiffre. */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <TopoBackdrop color={color} />
        <div
          className="absolute inset-0"
          style={{ background: `radial-gradient(20rem 12rem at 50% 38%, rgb(${color} / 0.2), transparent 70%)` }}
        />
      </div>

      {/* En-tête : l'icône du serveur, son adresse, et de quoi fermer. */}
      <div className="flex items-center gap-3.5">
        <div className="h-14 w-14 shrink-0 overflow-hidden rounded-[22%] border border-white/10 bg-white/[0.04] p-1.5">
          <div className="relative h-full w-full overflow-hidden rounded-[16%]">
            {icon ? (
              <img src={icon} alt="" draggable={false} className="absolute inset-0 h-full w-full" />
            ) : (
              <span
                className="absolute inset-0 flex items-center justify-center text-[22px] font-black uppercase text-white/30"
                style={{ background: `linear-gradient(135deg, hsl(${h} 55% 40%), hsl(${(h + 40) % 360} 55% 24%))` }}
              >
                {initial(s.server)}
              </span>
            )}
          </div>
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[17px] font-black">{s.server}</p>
          <p className="mt-0.5 text-[11.5px] text-txt-muted">
            {t('competitive.servers.duels', { count: s.duels })} · {t('competitive.servers.last')} {last}
          </p>
        </div>
        <CloseButton onClick={onClose} />
      </div>

      {/* La note moyenne, en très grand : c'est le chiffre du serveur. */}
      <div className="flex flex-col items-center gap-3 py-2">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.1em] text-txt-muted">{t('competitive.servers.avgScore')}</span>
        <div className="flex items-baseline gap-1.5">
          <motion.span
            className="text-[64px] font-black leading-none tabular-nums"
            style={{ color: `rgb(${color})` }}
            initial={{ scale: 0.8, opacity: 0 }}
            animate={{
              scale: 1,
              opacity: 1,
              textShadow: [`0 0 18px rgb(${color} / 0.45)`, `0 0 34px rgb(${color} / 0.8)`, `0 0 18px rgb(${color} / 0.45)`],
            }}
            transition={{ scale: { type: 'spring', stiffness: 260, damping: 18 }, textShadow: { duration: 2.6, repeat: Infinity } }}
          >
            {s.avgScore}
          </motion.span>
          <span className="text-[18px] font-bold text-txt-muted">/ 500</span>
        </div>
        <div className="h-2 w-full max-w-[300px] overflow-hidden rounded-full bg-white/[0.07]">
          <motion.div
            className="h-full rounded-full"
            style={{ background: `linear-gradient(90deg, rgb(${color} / 0.5), rgb(${color}))` }}
            initial={{ width: 0 }}
            animate={{ width: `${Math.min(100, (s.avgScore / 500) * 100)}%` }}
            transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1], delay: 0.1 }}
          />
        </div>
      </div>

      {/* Les trois autres chiffres, gros et sur un verre sombre. */}
      <div className="grid grid-cols-3 gap-2.5">
        <BigFigure label={t('competitive.servers.winRate')} value={`${winRate} %`}>
          {/* Victoires et défaites côte à côte, en proportion. */}
          <div className="mt-2 flex h-1.5 w-full overflow-hidden rounded-full bg-white/[0.07]">
            <div className="h-full bg-success" style={{ width: `${played ? (s.wins / played) * 100 : 0}%` }} />
            <div className="h-full bg-danger" style={{ width: `${played ? (s.losses / played) * 100 : 0}%` }} />
          </div>
        </BigFigure>
        <BigFigure label={t('competitive.servers.record')} value={t('competitive.stats.record', { wins: s.wins, losses: s.losses })} />
        <BigFigure label="RR" value={`${s.rr > 0 ? '+' : ''}${s.rr}`} tone={rrTone} />
      </div>

      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onClose}>
          {t('common.close')}
        </Button>
        <Button size="sm" variant="primary" onClick={onFilter}>
          {filtered ? t('competitive.servers.all') : t('competitive.servers.filter')}
        </Button>
      </div>
    </ModalShell>
  )
}

function BigFigure({ label, value, tone, children }: { label: string; value: string; tone?: string; children?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-white/[0.08] bg-black/35 px-2 py-3.5 text-center backdrop-blur-sm">
      <span className="text-[9.5px] font-bold uppercase tracking-[0.08em] text-txt-muted">{label}</span>
      <span className="mt-1.5 text-[22px] font-black leading-none tabular-nums" style={tone ? { color: tone } : undefined}>
        {value}
      </span>
      {children}
    </div>
  )
}
