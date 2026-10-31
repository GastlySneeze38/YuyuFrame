import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import { STATUS_COLORS, liveCustom } from '@/lib/friends'
import { demoMyProfile } from '@/lib/friendsDemo'
import { isDemo } from '@/stores/useDevFlags'
import { showError } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { MyProfile, ShowcaseView } from '@/types/friends'
import { FriendAvatar } from './FriendBits'
import { ThoughtBubble } from './FriendProfile'
import { ProfileEditor, showcasePreview } from './ProfileEditor'
import { ProfileLayout } from './ProfileLayout'
import { ProfileShowcase } from './ProfileShowcase'

/**
 * Mon profil, dans le panneau des amis : la fiche telle que mes amis la
 * voient — bannière, bio, vitrine —, rien d'autre. Tout se change dans la
 * grande fenêtre « Modifier le profil » (`ProfileEditor`), qui a la place de
 * tout montrer avec un aperçu.
 *
 * Une exception, parce qu'on y touche souvent : le statut personnalisé
 * s'écrit directement dans la bulle. Entrée (ou le bouton qui apparaît)
 * l'enregistre, Échap revient en arrière.
 */
export function MyProfileView() {
  const t = useT()
  const me = useFriends((s) => s.list?.me)
  const refresh = useFriends((s) => s.refresh)
  const username = useStore((s) => s.yuyuUsername)
  const [profile, setProfile] = useState<MyProfile | null>(null)
  const [served, setServed] = useState<ShowcaseView[]>([])
  const [editing, setEditing] = useState(false)
  const [status, setStatus] = useState('')
  const [savingStatus, setSavingStatus] = useState(false)
  const [copied, setCopied] = useState(false)

  const custom = me ? liveCustom(me) : null

  useEffect(() => {
    const load = isDemo() ? Promise.resolve(demoMyProfile()) : api.friends.myProfile()
    load.then(setProfile).catch(showError)
  }, [])

  // Ma vitrine telle que la voient mes amis : seul le serveur sait remplir
  // un rang, un titre ou une grille. Relue après chaque enregistrement.
  const loadServed = () => {
    if (isDemo() || !me) return
    api.friends
      .profile(me.id)
      .then((p) => setServed(p.showcase))
      .catch(() => {})
  }
  useEffect(loadServed, [me?.id])
  useEffect(() => setStatus(custom ?? ''), [custom])

  if (!me || !profile) return null
  const main = profile.accounts.find((a) => a.uuid === profile.main_mc_uuid) ?? profile.accounts[0] ?? null
  const color = STATUS_COLORS[me.status]
  const statusChanged = status.trim() !== (custom ?? '')
  const showcase = showcasePreview(profile.showcase, served, profile.library)

  async function saveStatus() {
    if (!me || !statusChanged) return
    setSavingStatus(true)
    try {
      if (!isDemo()) await api.friends.setStatus(me.status, status.trim() || null, null)
      await refresh()
    } catch (e) {
      showError(e)
    } finally {
      setSavingStatus(false)
    }
  }

  async function copyCode() {
    if (!me) return
    try {
      await navigator.clipboard.writeText(me.code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Presse-papiers refusé : le code reste lisible.
    }
  }

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="h-full">
      <ProfileLayout
        banner={profile.banner}
        accent={profile.accent}
        color={color}
        avatar={<FriendAvatar skin={`https://mc-heads.net/skin/${main?.uuid ?? 'MHF_Steve'}`} state={me.status} size={72} />}
        // Le statut personnalisé, lui, s'écrit dans la bulle même.
        bubble={
          <ThoughtBubble accent={profile.accent}>
            <span className="flex w-full items-center gap-2">
              <input
                value={status}
                maxLength={60}
                disabled={savingStatus}
                onChange={(e) => setStatus(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void saveStatus()
                  if (e.key === 'Escape') setStatus(custom ?? '')
                }}
                placeholder={t('friends.custom.placeholder')}
                className="w-full min-w-0 bg-transparent text-[15.5px] font-bold outline-none placeholder:font-semibold placeholder:text-txt-muted"
              />
              {statusChanged && (
                <button
                  onClick={() => void saveStatus()}
                  disabled={savingStatus}
                  title={t('friends.custom.save')}
                  className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-accent text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                    <path d="M5 12.5l4.5 4.5L19 7.5" />
                  </svg>
                </button>
              )}
            </span>
          </ThoughtBubble>
        }
        name={username ?? '—'}
        nameExtra={
          <button onClick={() => void copyCode()} title={t('friends.copyMyCode')} className="shrink-0 rounded-md bg-white/[0.06] px-2 py-1 font-mono text-[11.5px] font-bold text-accent-hover hover:bg-white/10">
            {copied ? t('friends.profile.copied') : `#${me.code.split('#')[1] ?? ''}`}
          </button>
        }
      >
        <div className="flex flex-col gap-2.5 px-4 pb-4 pt-1.5">
          {profile.bio_hidden && <p className="text-[11.5px] text-warning">{t('friends.me.bioHidden')}</p>}
          {profile.bio ? (
            <p className="whitespace-pre-line text-[12.5px] leading-relaxed text-txt-secondary [overflow-wrap:anywhere]">{profile.bio}</p>
          ) : (
            <p className="text-[12px] italic text-txt-muted">{t('friends.me.noBio')}</p>
          )}

          <button
            onClick={() => setEditing(true)}
            className="flex items-center justify-center gap-2 rounded-xl border border-line bg-surface-1 px-3 py-2.5 text-[12.5px] font-bold text-txt-secondary transition-colors hover:border-accent/50 hover:text-white"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
              <path d="M4 20h4L18.5 9.5a2.1 2.1 0 00-3-3L5 17v3zM13.5 6.5l3 3" />
            </svg>
            {t('friends.me.edit')}
          </button>

          {showcase.length > 0 ? <ProfileShowcase items={showcase} /> : <p className="text-[12px] text-txt-muted">{t('friends.me.emptyShowcase')}</p>}
          <p className="text-[11px] text-txt-muted">{t('friends.me.asFriendsSee')}</p>
        </div>
      </ProfileLayout>

      {editing && (
        <ProfileEditor
          profile={profile}
          served={served}
          status={me.status}
          custom={custom}
          username={username ?? '—'}
          onSaved={(p) => {
            setProfile(p)
            loadServed()
          }}
          onClose={() => setEditing(false)}
        />
      )}
    </motion.div>
  )
}
