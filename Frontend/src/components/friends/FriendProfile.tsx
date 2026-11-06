import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { open } from '@tauri-apps/plugin-shell'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { JoinServerModal } from '@/components/servers/JoinServerModal'
import { Button } from '@/components/ui/Button'
import { ModalShell } from '@/components/ui/ModalShell'
import { STATUS_COLORS, dateLocale, liveCustom, relativeTime, skinOf } from '@/lib/friends'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import { demoProfile } from '@/lib/friendsDemo'
import { isDemo } from '@/stores/useDevFlags'
import type { Friend, PublicProfile, ReportReason } from '@/types/friends'
import { FriendAvatar, GameTile, activityText } from './FriendBits'
import { ProfileLayout } from './ProfileLayout'
import { ProfileShowcase } from './ProfileShowcase'
import { DiscordMark } from './FriendSettingsView'
import { RemoteImage } from '@/components/ui/RemoteImage'

/**
 * Le profil d'un ami, dans le panneau.
 *
 * Rien ne s'y dit deux fois : la pastille donne le statut (pas de « Ne pas
 * déranger » écrit dessous), la vignette et une ligne donnent l'activité. En
 * haut, ce qu'on vient y faire — le rejoindre, voir son onglet compétitif ;
 * les gestes rares et définitifs (retirer, bloquer) sont rangés dans le menu
 * « … » et se confirment sur l'élément lui-même.
 *
 * Le bandeau est la bannière choisie par l'ami (sinon la couleur de son
 * statut) ; sa bio, sa vitrine (rang, titre) suivent son pseudo, quand il les
 * montre à ses amis.
 */
export function FriendProfile({ friend, onDone }: { friend: Friend; onDone: () => void }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const gameRunning = useStore((s) => s.gameRunning)
  const onPublicServer = useOnPublicServer(gameRunning)
  const [joining, setJoining] = useState(false)
  const navigate = useNavigate()
  const [profile, setProfile] = useState<PublicProfile | null>(null)
  const p = friend.presence
  const custom = liveCustom(p)
  const color = STATUS_COLORS[p.state]
  const game = p.activity?.kind === 'game' ? p.activity : null
  // Rejoindre : il faut qu'il soit sur un serveur ET qu'il me le montre.
  const joinBlocked = !game ? t('friends.profile.joinNotPlaying') : !game.server ? t('friends.profile.joinHidden') : null

  // Son profil se charge à l'ouverture ; la fiche s'affiche sans l'attendre.
  useEffect(() => {
    let alive = true
    const load = isDemo() ? Promise.resolve(demoProfile(friend.id)) : api.friends.profile(friend.id)
    load.then((p) => alive && setProfile(p)).catch(() => {})
    return () => {
      alive = false
    }
  }, [friend.id])

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="h-full">
      <ProfileLayout
        banner={profile?.banner ?? null}
        accent={profile?.accent ?? null}
        color={color}
        corner={<MoreMenu friend={friend} onDone={onDone} />}
        avatar={<FriendAvatar skin={skinOf(friend)} state={p.state} size={72} />}
        bubble={custom ? <ThoughtBubble accent={profile?.accent ?? null}>{custom}</ThoughtBubble> : null}
        name={friend.username}
        nameExtra={
          profile?.discord ? (
            <span title={t('friends.discord.badge')} className="flex items-center text-[#7983f5]">
              <DiscordMark className="h-4 w-4" />
            </span>
          ) : null
        }
      >
      {profile?.bio && <p className="whitespace-pre-line px-4 pt-1.5 text-[12.5px] leading-relaxed text-txt-secondary [overflow-wrap:anywhere]">{profile.bio}</p>}

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
        {/* Le même onglet Compétitif que le mien, ouvert sur lui. */}
        <ActionButton onClick={() => navigate(`/competitive?friend=${friend.id}`)} label={t('friends.profile.competitive')} path="M8 21h8M12 17v4M7 4h10v5a5 5 0 01-10 0V4zM7 6H4v2a3 3 0 003 3M17 6h3v2a3 3 0 01-3 3" />
      </div>

      {/* En jeu sans me montrer son serveur : on dit pourquoi « Rejoindre » est
          grisé, et on peut le lui demander (il reçoit une notification). */}
      {game && !game.server && <AskServer friendId={friend.id} />}

      {/* Je joue et il est là : l'inviter sur mon serveur. */}
      {onPublicServer && p.state !== 'offline' && <InviteFriend friendId={friend.id} />}

      {/* Ce qu'il fait, sa vitrine, ses comptes, depuis quand vous êtes amis. */}
      <div className="flex flex-col gap-2 px-4 pb-4 pt-3">
        {profile && <ProfileShowcase items={profile.showcase} />}
        {/* Son compte Discord lié ; le pseudo seulement s'il le montre. */}
        {profile?.discord && (
          <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-[#5865F2]/15 text-[#7983f5]">
              <DiscordMark className="h-5 w-5" />
            </span>
            <div className="flex min-w-0 flex-col">
              <span className="truncate text-[12.5px] font-bold">{profile.discord.username ?? t('friends.discord.badge')}</span>
              {profile.discord.username && <span className="text-[11px] text-txt-muted">{t('friends.discord.badge')}</span>}
            </div>
          </div>
        )}
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
      </ProfileLayout>

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

/**
 * Le statut personnalisé en bulle de pensée, accrochée à la tête par deux
 * petits ronds — il « pense » ce qu'il a écrit. Elle occupe la place à droite
 * de la tête, sous le bandeau.
 */
export function ThoughtBubble({ accent, children }: { accent: string | null; children: React.ReactNode }) {
  const border = accent ? `${accent}66` : undefined
  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.8, x: -6 }}
      animate={{ opacity: 1, scale: 1, x: 0 }}
      transition={{ type: 'spring', stiffness: 420, damping: 26, delay: 0.08 }}
      style={{ transformOrigin: 'left center' }}
      className="relative mb-1.5 ml-7 min-w-0 flex-1"
    >
      {/* Deux ronds qui grossissent en montant de la tête vers la bulle. */}
      <span className="absolute -left-[26px] bottom-0.5 h-3 w-3 rounded-full border border-line bg-[rgb(var(--bg-raised))] shadow-[0_4px_10px_rgba(0,0,0,0.35)]" style={{ borderColor: border }} />
      <span className="absolute -left-[15px] bottom-3.5 h-[18px] w-[18px] rounded-full border border-line bg-[rgb(var(--bg-raised))] shadow-[0_4px_10px_rgba(0,0,0,0.35)]" style={{ borderColor: border }} />
      <div
        className="relative flex min-h-[54px] w-full items-center rounded-[22px] border border-line bg-[rgb(var(--bg-raised))] px-4 py-3 text-[15.5px] font-bold leading-snug text-txt-primary shadow-[0_8px_22px_rgba(0,0,0,0.4)] [overflow-wrap:anywhere]"
        style={{ borderColor: border }}
      >
        {children}
      </div>
    </motion.div>
  )
}

/** « Il ne te montre pas son serveur » et de quoi le lui demander. Une fois
 *  envoyée, la demande ne se renvoie pas (le serveur en accepte une toutes les
 *  10 minutes). */
function AskServer({ friendId }: { friendId: string }) {
  const t = useT()
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle')
  async function ask() {
    setState('sending')
    try {
      if (!isDemo()) await api.friends.requestServer(friendId)
      setState('sent')
    } catch (e) {
      showError(e)
      setState('idle')
    }
  }
  return (
    <div className="mx-4 mt-2 flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2">
      <p className="min-w-0 flex-1 text-[11.5px] leading-snug text-txt-muted">{t('friends.profile.serverHiddenWhy')}</p>
      <button
        onClick={() => void ask()}
        disabled={state !== 'idle'}
        className="shrink-0 rounded-lg border border-line px-2.5 py-1 text-[11.5px] font-semibold text-txt-secondary transition-colors hover:text-white disabled:cursor-default disabled:opacity-60 disabled:hover:text-txt-secondary"
      >
        {state === 'sent' ? t('friends.profile.serverAsked') : t('friends.profile.askServer')}
      </button>
    </div>
  )
}

/**
 * Inviter cet ami sur le serveur où je joue. Le serveur à rejoindre est lu
 * côté serveur dans ma présence : en solo ou sur un serveur privé, il refuse
 * et l'erreur le dit. Le bouton redevient utilisable au bout d'une minute,
 * le délai du serveur entre deux invitations au même ami.
 */
function InviteFriend({ friendId }: { friendId: string }) {
  const t = useT()
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle')
  useEffect(() => {
    if (state !== 'sent') return
    const timer = window.setTimeout(() => setState('idle'), 60_000)
    return () => window.clearTimeout(timer)
  }, [state])
  async function invite() {
    setState('sending')
    try {
      if (!isDemo()) await api.friends.invite(friendId)
      setState('sent')
    } catch (e) {
      showError(e)
      setState('idle')
    }
  }
  return (
    <div className="mx-4 mt-2 flex items-center gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2">
      <p className="min-w-0 flex-1 text-[11.5px] leading-snug text-txt-muted">{t('friends.invites.sendWhy')}</p>
      <button
        onClick={() => void invite()}
        disabled={state !== 'idle'}
        className="shrink-0 rounded-lg bg-[#3ba55d] px-2.5 py-1 text-[11.5px] font-bold text-white transition-colors hover:bg-[#349852] disabled:cursor-default disabled:bg-white/[0.06] disabled:text-txt-muted"
      >
        {state === 'sent' ? t('friends.invites.sent') : t('friends.invites.send')}
      </button>
    </div>
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
            <RemoteImage
              src={`https://mc-heads.net/body/${m.uuid}/60`}
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
  const [reporting, setReporting] = useState(false)
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
            className="absolute right-0 top-full z-10 mt-1.5 flex w-56 flex-col rounded-xl border border-line bg-[rgb(var(--bg-raised))] p-1.5 shadow-[0_18px_50px_rgba(0,0,0,0.6)]"
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
            <MenuItem
              disabled={busy}
              onClick={() => {
                setOpen(false)
                setReporting(true)
              }}
            >
              {t('friends.report.action')}
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
      {createPortal(
        <AnimatePresence>{reporting && <ReportModal friend={friend} onClose={() => setReporting(false)} />}</AnimatePresence>,
        document.body,
      )}
    </div>
  )
}

const REPORT_REASONS: ReportReason[] = ['username', 'bio', 'status', 'other']

/**
 * Signaler un joueur : ce qui pose problème (pseudo, bio, statut, autre) et,
 * si on veut, quelques mots. L'équipe voit ce qu'il montrait à ce moment-là ;
 * lui ne sait pas qui l'a signalé.
 */
function ReportModal({ friend, onClose }: { friend: Friend; onClose: () => void }) {
  const t = useT()
  const [reason, setReason] = useState<ReportReason | null>(null)
  const [details, setDetails] = useState('')
  const [sending, setSending] = useState(false)

  async function send() {
    if (!reason) return
    setSending(true)
    try {
      await api.friends.report(friend.id, reason, details.trim())
      showNotice(t('friends.report.sent'))
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setSending(false)
    }
  }

  return (
    <ModalShell title={t('friends.report.title', { name: friend.username })} onClose={() => !sending && onClose()}>
      <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t('friends.report.intro')}</p>
      <div className="grid grid-cols-2 gap-2">
        {REPORT_REASONS.map((r) => (
          <button
            key={r}
            onClick={() => setReason(r)}
            className={`rounded-xl border px-3 py-2.5 text-left text-[12.5px] font-semibold transition-colors ${
              reason === r ? 'border-danger/60 bg-danger/15 text-white' : 'border-line bg-surface-1 text-txt-secondary hover:text-white'
            }`}
          >
            {t(`friends.report.reasons.${r}`)}
          </button>
        ))}
      </div>
      <textarea
        value={details}
        maxLength={500}
        rows={3}
        onChange={(e) => setDetails(e.target.value)}
        placeholder={t('friends.report.details')}
        className="w-full resize-none rounded-xl border border-line bg-surface-2 px-3 py-2 text-[12.5px] outline-none placeholder:text-txt-muted focus:border-accent/60"
      />
      <div className="flex justify-end gap-2">
        <Button variant="ghost" disabled={sending} onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant="danger" loading={sending} disabled={!reason} onClick={() => void send()}>
          {t('friends.report.send')}
        </Button>
      </div>
    </ModalShell>
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

/**
 * Suis-je sur un serveur public, celui qu'une invitation enverrait ? Le jeu
 * l'annonce au launcher (`friends_my_server`) ; relu toutes les 2 s tant
 * qu'une partie tourne. En solo, au menu ou sur un serveur privé : non.
 */
function useOnPublicServer(gameRunning: boolean): boolean {
  const [server, setServer] = useState<string | null>(null)
  useEffect(() => {
    if (!gameRunning) { setServer(null); return }
    if (isDemo()) { setServer('demo'); return }
    let alive = true
    const read = () => api.friends.myServer().then((s) => { if (alive) setServer(s) }).catch(() => {})
    read()
    const timer = window.setInterval(read, 2000)
    return () => { alive = false; window.clearInterval(timer) }
  }, [gameRunning])
  return server !== null
}
