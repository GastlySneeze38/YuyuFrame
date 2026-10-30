import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { open } from '@tauri-apps/plugin-shell'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { JoinServerModal } from '@/components/servers/JoinServerModal'
import { Button } from '@/components/ui/Button'
import { ModalShell } from '@/components/ui/ModalShell'
import { STATUS_COLORS, dateLocale, liveCustom, relativeTime, skinOf } from '@/lib/friends'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { Friend } from '@/types/friends'
import { FriendAvatar, GameTile, activityText } from './FriendBits'

/**
 * Le profil d'un ami, dans le panneau.
 *
 * Rien ne s'y dit deux fois : la pastille donne le statut (pas de « Ne pas
 * déranger » écrit dessous), la vignette et une ligne donnent l'activité. En
 * haut, ce qu'on vient y faire — le rejoindre, voir son onglet compétitif ;
 * les gestes rares et définitifs (retirer, bloquer) sont rangés dans le menu
 * « … » et se confirment sur l'élément lui-même.
 *
 * Le bandeau est à la couleur du statut pour l'instant : c'est la place de la
 * bannière que chacun pourra choisir pour son profil.
 */
export function FriendProfile({ friend, onDone }: { friend: Friend; onDone: () => void }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const [joining, setJoining] = useState(false)
  const p = friend.presence
  const custom = liveCustom(p)
  const color = STATUS_COLORS[p.state]
  const game = p.activity?.kind === 'game' ? p.activity : null
  // Rejoindre : il faut qu'il soit sur un serveur ET qu'il me le montre.
  const joinBlocked = !game ? t('friends.profile.joinNotPlaying') : !game.server ? t('friends.profile.joinHidden') : null

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="flex flex-col">
      {/* Le menu « … » vit hors du bandeau : celui-ci rogne ce qui dépasse,
          et le menu déroulé dépasse forcément. Il passe au-dessus de tout,
          bulle de statut comprise. */}
      <div className="relative shrink-0">
        <div className="relative h-[96px] overflow-hidden" style={{ background: `linear-gradient(135deg, ${color}66, ${color}14 75%)` }}>
          <div className="absolute inset-0 bg-[radial-gradient(70%_100%_at_85%_0%,rgba(255,255,255,0.14),transparent)]" />
        </div>
        <div className="absolute right-2 top-2 z-30">
          <MoreMenu friend={friend} onDone={onDone} />
        </div>
      </div>

      <div className="relative z-10 -mt-10 flex items-end gap-1 px-4">
        <div className="shrink-0 rounded-[30%] bg-[#111116] p-1.5">
          <FriendAvatar skin={skinOf(friend)} state={p.state} size={72} />
        </div>
        {/* Le statut personnalisé en bulle de pensée, accrochée à la tête par
            deux petits ronds — il « pense » ce qu'il a écrit. */}
        {custom && (
          <motion.div
            initial={{ opacity: 0, scale: 0.8, x: -6 }}
            animate={{ opacity: 1, scale: 1, x: 0 }}
            transition={{ type: 'spring', stiffness: 420, damping: 26, delay: 0.08 }}
            style={{ transformOrigin: 'left center' }}
            // Elle occupe toute la place à droite de la tête, sous le bandeau :
            // c'est là que la fiche avait un vide.
            className="relative mb-1.5 ml-7 min-w-0 flex-1"
          >
            {/* Deux ronds qui grossissent en montant de la tête vers la bulle. */}
            <span className="absolute -left-[26px] bottom-0.5 h-3 w-3 rounded-full border border-line bg-[#1d1d26] shadow-[0_4px_10px_rgba(0,0,0,0.35)]" />
            <span className="absolute -left-[15px] bottom-3.5 h-[18px] w-[18px] rounded-full border border-line bg-[#1d1d26] shadow-[0_4px_10px_rgba(0,0,0,0.35)]" />
            <div className="relative flex min-h-[54px] w-full items-center rounded-[22px] border border-line bg-[#1d1d26] px-4 py-3 text-[15.5px] font-bold leading-snug text-txt-primary shadow-[0_8px_22px_rgba(0,0,0,0.4)] [overflow-wrap:anywhere]">
              {custom}
            </div>
          </motion.div>
        )}
      </div>

      <p className="truncate px-4 pt-2.5 text-[20px] font-black leading-tight">{friend.username}</p>

      {/* Ce qu'on vient faire ici : le rejoindre, voir son onglet compétitif. */}
      <div className="grid grid-cols-2 gap-2 px-4 pt-3.5">
        <ActionButton
          primary
          disabled={!!joinBlocked}
          title={joinBlocked ?? undefined}
          onClick={() => setJoining(true)}
          label={t('friends.profile.join')}
          path="M7 4.5v15l12.5-7.5L7 4.5z"
          filled
        />
        <ActionButton disabled label={t('friends.profile.competitive')} badge={t('friends.profile.soon')} path="M8 21h8M12 17v4M7 4h10v5a5 5 0 01-10 0V4zM7 6H4v2a3 3 0 003 3M17 6h3v2a3 3 0 01-3 3" />
      </div>

      {/* Ce qu'il fait, puis depuis quand vous êtes amis : deux cartes de
          même facture. */}
      <div className="flex flex-col gap-2 px-4 pb-4 pt-3">
        <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
          {game ? (
            <>
              <GameTile activity={game} size={40} />
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-[13px] font-bold text-[#5fd38d]">{activityText(game, t)}</span>
                {/* En duel, la version passe ici (la ligne du dessus dit le duel). */}
                <span className="truncate text-[11.5px] text-txt-muted">
                  {[game.duel ? t('friends.activity.game', { version: game.mc_version }) : null, game.server ?? t('friends.profile.noServer')]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
              </div>
            </>
          ) : (
            <span className="text-[12px] text-txt-muted">
              {p.state === 'offline'
                ? p.last_seen
                  ? t('friends.lastSeen', { when: relativeTime(p.last_seen, language) })
                  : t('friends.profile.offline')
                : p.activity?.kind === 'launcher'
                  ? t('friends.activity.launcher')
                  : t('friends.profile.noActivity')}
            </span>
          )}
        </div>
        {friend.minecraft.length > 0 && <MinecraftAccounts accounts={friend.minecraft} />}
        <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] border border-accent/30 bg-accent/10 text-accent-hover">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
              <path d="M12 20.5s-7.5-4.4-7.5-10A4.3 4.3 0 0112 8a4.3 4.3 0 017.5 2.5c0 5.6-7.5 10-7.5 10z" />
            </svg>
          </span>
          <span className="text-[12.5px] font-medium text-txt-secondary">
            {t('friends.profile.friendsSince', { date: new Date(friend.since).toLocaleDateString(dateLocale(language), { day: 'numeric', month: 'long', year: 'numeric' }) })}
          </span>
        </div>
      </div>

      {/* La fenêtre de lancement, hors du panneau : il est transformé par son
          animation, et un `fixed` à l'intérieur s'y collerait. */}
      {joining &&
        game?.server &&
        createPortal(
          <JoinServerModal request={{ ip: game.server, mcVersion: game.mc_version, loader: game.loader }} onClose={() => setJoining(false)} />,
          document.body,
        )}
    </motion.div>
  )
}

function ActionButton({ label, path, onClick, primary = false, disabled = false, filled = false, title, badge }: {
  label: string
  path: string
  onClick?: () => void
  primary?: boolean
  disabled?: boolean
  filled?: boolean
  /** Pourquoi il est grisé, au survol. */
  title?: string
  badge?: string
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={`relative flex items-center justify-center gap-2 rounded-xl px-3 py-2.5 text-[12.5px] font-bold transition-colors disabled:cursor-not-allowed ${
        primary
          ? 'bg-[#3ba55d] text-white shadow-[0_6px_18px_rgba(59,165,93,0.3)] hover:bg-[#349852] disabled:bg-white/[0.06] disabled:text-txt-muted disabled:shadow-none'
          : 'border border-line bg-surface-1 text-txt-secondary hover:text-white disabled:opacity-60 disabled:hover:text-txt-secondary'
      }`}
    >
      <svg viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={filled ? 1.2 : 2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4 shrink-0">
        <path d={path} />
      </svg>
      <span className="truncate">{label}</span>
      {badge && <span className="absolute -right-1.5 -top-2 rounded-full bg-accent px-1.5 py-px text-[9px] font-black uppercase tracking-wide text-white">{badge}</span>}
    </button>
  )
}

/**
 * Ses comptes Minecraft : c'est sous ces noms qu'on le croise en jeu. Pour
 * chacun, son buste, son pseudo, et de quoi s'en servir — copier le pseudo
 * (pour l'inviter, le /msg en jeu), ouvrir sa page NameMC (skins, anciens
 * pseudos).
 */
function MinecraftAccounts({ accounts }: { accounts: Friend['minecraft'] }) {
  const t = useT()
  const [copied, setCopied] = useState<string | null>(null)

  async function copy(name: string) {
    try {
      await navigator.clipboard.writeText(name)
      setCopied(name)
      setTimeout(() => setCopied((c) => (c === name ? null : c)), 1500)
    } catch {
      // Presse-papiers refusé : rien à montrer de plus.
    }
  }

  return (
    <div className="rounded-xl border border-line bg-surface-1 px-3 py-2.5">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('friends.profile.minecraft')}</span>
        <span className="text-[10.5px] font-bold text-txt-muted">{accounts.length}</span>
      </div>
      <div className="flex flex-col gap-1">
        {accounts.map((m) => (
          <div key={m.uuid} className="group flex items-center gap-3 rounded-lg px-1.5 py-1.5 transition-colors hover:bg-white/[0.04]">
            <img
              src={`https://mc-heads.net/body/${m.uuid}/60`}
              alt=""
              draggable={false}
              className="h-[46px] w-auto shrink-0 select-none [image-rendering:pixelated]"
            />
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[13.5px] font-bold">{m.name}</span>
              <span className="truncate text-[11px] text-txt-muted">{t('friends.profile.verified')}</span>
            </div>
            <SmallButton title={copied === m.name ? t('friends.profile.copied') : t('friends.profile.copyName')} onClick={() => void copy(m.name)}>
              {copied === m.name ? (
                <path d="M5 12.5l4.5 4.5L19 7.5" />
              ) : (
                <>
                  <rect x="8" y="8" width="12" height="12" rx="2.5" />
                  <path d="M16 8V6a2 2 0 00-2-2H6a2 2 0 00-2 2v8a2 2 0 002 2h2" />
                </>
              )}
            </SmallButton>
            <SmallButton title={t('friends.profile.nameMc')} onClick={() => void open(`https://namemc.com/profile/${m.uuid}`).catch(() => {})}>
              <path d="M14 4h6v6M20 4l-9 9M18 14v4a2 2 0 01-2 2H6a2 2 0 01-2-2V8a2 2 0 012-2h4" />
            </SmallButton>
          </div>
        ))}
      </div>
    </div>
  )
}

function SmallButton({ title, onClick, children }: { title: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-label={title}
      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-line bg-black/20 text-txt-muted transition-colors hover:border-line-strong hover:text-white"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
        {children}
      </svg>
    </button>
  )
}

/** Le menu « … » du bandeau : montrer mon serveur (ami par ami), retirer, bloquer. */
function MoreMenu({ friend, onDone }: { friend: Friend; onDone: () => void }) {
  const t = useT()
  const serverVisibility = useFriends((s) => s.list?.settings.server_visibility)
  const refresh = useFriends((s) => s.refresh)
  const [open, setOpen] = useState(false)
  const [confirm, setConfirm] = useState<'remove' | 'block' | null>(null)
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLDivElement>(null)

  const ask = (what: 'remove' | 'block') => {
    setOpen(false)
    setConfirm(what)
  }

  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', away)
    return () => window.removeEventListener('mousedown', away)
  }, [open])

  async function act(run: () => Promise<unknown>, leave: boolean) {
    setBusy(true)
    try {
      await run()
      await refresh()
      if (leave) onDone()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
      setConfirm(null)
    }
  }

  return (
    <div ref={box} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        title={t('friends.profile.more')}
        aria-label={t('friends.profile.more')}
        className="flex h-8 w-8 items-center justify-center rounded-lg bg-black/35 text-white/80 backdrop-blur transition-colors hover:bg-black/55 hover:text-white"
      >
        <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
          <circle cx="5" cy="12" r="2" />
          <circle cx="12" cy="12" r="2" />
          <circle cx="19" cy="12" r="2" />
        </svg>
      </button>
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -4, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.97 }}
            transition={{ duration: 0.14 }}
            className="absolute right-0 top-full z-10 mt-1.5 flex w-56 flex-col rounded-xl border border-line bg-[#18181f] p-1.5 shadow-[0_18px_50px_rgba(0,0,0,0.6)]"
          >
            {serverVisibility === 'some' && (
              <MenuItem disabled={busy} onClick={() => void act(() => api.friends.setServerShare(friend.id, !friend.server_shared), false)}>
                <span className="flex-1">{t('friends.profile.shareServer')}</span>
                <span className={`flex h-4 w-4 items-center justify-center rounded border ${friend.server_shared ? 'border-accent bg-accent' : 'border-line-strong'}`}>
                  {friend.server_shared && (
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" className="h-2.5 w-2.5 text-white">
                      <path d="M5 12.5l4.5 4.5L19 7.5" />
                    </svg>
                  )}
                </span>
              </MenuItem>
            )}
            <MenuItem danger disabled={busy} onClick={() => ask('remove')}>
              {t('friends.profile.remove')}
            </MenuItem>
            <MenuItem danger disabled={busy} onClick={() => ask('block')}>
              {t('friends.profile.block')}
            </MenuItem>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Retirer et bloquer ne se défont pas d'un clic : une vraie fenêtre
          d'avertissement, hors du panneau (un `fixed` s'y collerait). */}
      {createPortal(
        <AnimatePresence>
          {confirm && (
            <ModalShell title={t(confirm === 'remove' ? 'friends.profile.confirmRemove' : 'friends.profile.confirmBlock', { name: friend.username })} onClose={() => !busy && setConfirm(null)}>
              <p className="text-[13px] leading-relaxed text-txt-secondary">
                {t(confirm === 'remove' ? 'friends.profile.removeText' : 'friends.profile.blockText', { name: friend.username })}
              </p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" disabled={busy} onClick={() => setConfirm(null)}>
                  {t('common.cancel')}
                </Button>
                <Button
                  variant="danger"
                  loading={busy}
                  onClick={() =>
                    void act(() => (confirm === 'remove' ? api.friends.remove(friend.id) : api.friends.block(friend.id)), true)
                  }
                >
                  {t(confirm === 'remove' ? 'friends.profile.remove' : 'friends.profile.block')}
                </Button>
              </div>
            </ModalShell>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </div>
  )
}

function MenuItem({ children, onClick, disabled, danger = false, armed = false }: {
  children: React.ReactNode
  onClick: () => void
  disabled: boolean
  danger?: boolean
  armed?: boolean
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`flex items-center gap-2 rounded-lg px-2.5 py-2 text-left text-[12.5px] font-semibold transition-colors disabled:opacity-50 ${
        armed ? 'bg-danger/20 text-danger' : danger ? 'text-danger/90 hover:bg-danger/10' : 'text-txt-secondary hover:bg-white/[0.06] hover:text-white'
      }`}
    >
      {children}
    </button>
  )
}
