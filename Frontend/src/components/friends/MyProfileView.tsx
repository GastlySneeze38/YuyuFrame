import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { api } from '@/api/client'
import { SkinFace } from '@/components/ui/SkinFace'
import { STATUS_COLORS, liveCustom } from '@/lib/friends'
import { demoMyProfile, demoMyShowcase } from '@/lib/friendsDemo'
import { ACCENTS, BANNER_IDS, BANNERS } from '@/lib/profileBanners'
import { isDemo } from '@/stores/useDevFlags'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useFriends } from '@/stores/useFriends'
import { useStore } from '@/stores/useStore'
import { useT } from '@/i18n'
import type { MyProfile, ShowcaseItem, ShowcaseView, Visibility3 } from '@/types/friends'
import { FriendAvatar } from './FriendBits'
import { ThoughtBubble } from './FriendProfile'
import { ProfileLayout } from './ProfileLayout'
import { ProfileShowcase } from './ProfileShowcase'

const BIO_MAX = 190
const BIO_LINES = 3
const SHOWCASE_CHOICES: { item: ShowcaseItem; key: string }[] = [
  { item: { kind: 'rank', mode: 'mace' }, key: 'rankMace' },
  { item: { kind: 'rank', mode: 'sword' }, key: 'rankSword' },
  { item: { kind: 'title' }, key: 'title' },
]

const same = (a: ShowcaseItem, b: ShowcaseItem) => a.kind === b.kind && (a.kind !== 'rank' || (b.kind === 'rank' && a.mode === b.mode))

/**
 * Mon profil, dans le panneau des amis, modifiable sur place : c'est la même
 * fiche que celle que voient mes amis, avec de quoi la changer. On tape son
 * statut dans la bulle, on choisit sa bannière depuis le bandeau, et chaque
 * partie (bannière, bio, vitrine) dit qui la voit.
 *
 * Rien ne part avant « Enregistrer », qui n'apparaît qu'une fois quelque chose
 * changé : le profil et le statut personnalisé partent ensemble.
 */
export function MyProfileView() {
  const t = useT()
  const me = useFriends((s) => s.list?.me)
  const refresh = useFriends((s) => s.refresh)
  const username = useStore((s) => s.yuyuUsername)
  const [saved, setSaved] = useState<MyProfile | null>(null)
  const [draft, setDraft] = useState<MyProfile | null>(null)
  const [status, setStatus] = useState('')
  const [picker, setPicker] = useState(false)
  const [saving, setSaving] = useState(false)
  const [copied, setCopied] = useState(false)

  const custom = me ? liveCustom(me) : null

  useEffect(() => {
    const load = isDemo() ? Promise.resolve(demoMyProfile()) : api.friends.myProfile()
    load
      .then((p) => {
        setSaved(p)
        setDraft(p)
      })
      .catch(showError)
  }, [])

  // Ma vitrine telle que mes amis la voient : le serveur seul sait remplir un
  // rang ou un titre. Relue après chaque enregistrement.
  const [served, setServed] = useState<ShowcaseView[]>([])
  const loadServed = () => {
    if (isDemo() || !me) return
    api.friends
      .profile(me.id)
      .then((p) => setServed(p.showcase))
      .catch(() => {})
  }
  useEffect(loadServed, [me?.id])
  useEffect(() => setStatus(custom ?? ''), [custom])

  const profileChanged = useMemo(() => !!saved && !!draft && JSON.stringify(saved) !== JSON.stringify(draft), [saved, draft])
  const statusChanged = status.trim() !== (custom ?? '')
  const dirty = profileChanged || statusChanged

  if (!me || !draft) return null
  const edit = (patch: Partial<MyProfile>) => setDraft({ ...draft, ...patch })
  const main = draft.accounts.find((a) => a.uuid === draft.main_mc_uuid) ?? draft.accounts[0] ?? null
  const color = STATUS_COLORS[me.status]

  async function save() {
    if (!draft || !me) return
    // Démonstration : rien ne part, on garde juste ce qu'on voit.
    if (isDemo()) {
      setSaved(draft)
      showNotice(t('friends.me.saved'))
      return
    }
    setSaving(true)
    try {
      if (profileChanged) {
        const next = await api.friends.setProfile(draft)
        setSaved(next)
        setDraft(next)
      }
      if (statusChanged) {
        // Le statut personnalisé garde ce qui lui restait à vivre.
        const end = me.custom?.expires_at ? Date.parse(me.custom.expires_at) : null
        const left = end ? Math.max(1, Math.ceil((end - Date.now()) / 60000)) : null
        await api.friends.setStatus(me.status, status.trim() || null, status.trim() ? left : null)
      }
      await refresh()
      loadServed()
      showNotice(t('friends.me.saved'))
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
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

  // Ce qui est coché, rempli avec ce que le serveur sait (ou la démo). Un
  // élément sans valeur — pas de titre, rang caché en jeu — n'apparaît pas.
  const preview: ShowcaseView[] = isDemo()
    ? demoMyShowcase(draft.showcase)
    : draft.showcase.flatMap((item) => served.filter((v) => (item.kind === 'title' ? v.kind === 'title' : v.kind === 'rank' && v.mode === item.mode)).slice(0, 1))

  const toggleShowcase = (item: ShowcaseItem) => {
    const has = draft.showcase.some((s) => same(s, item))
    edit({ showcase: has ? draft.showcase.filter((s) => !same(s, item)) : [...draft.showcase, item].slice(0, 3) })
  }

  return (
    <motion.div initial={{ opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 16 }} className="h-full">
      <ProfileLayout
        banner={draft.banner}
        accent={draft.accent}
        color={color}
        avatar={<FriendAvatar skin={`https://mc-heads.net/skin/${main?.uuid ?? 'MHF_Steve'}`} state={me.status} size={72} />}
        // Mon statut personnalisé s'écrit dans la bulle même.
        bubble={
          <ThoughtBubble accent={draft.accent}>
            <input
              value={status}
              maxLength={60}
              onChange={(e) => setStatus(e.target.value)}
              placeholder={t('friends.custom.placeholder')}
              className="w-full min-w-0 bg-transparent text-[15.5px] font-bold outline-none placeholder:font-semibold placeholder:text-txt-muted"
            />
          </ThoughtBubble>
        }
        name={username ?? '—'}
        nameExtra={
          <button onClick={() => void copyCode()} title={t('friends.copyMyCode')} className="shrink-0 rounded-md bg-white/[0.06] px-2 py-1 font-mono text-[11.5px] font-bold text-accent-hover hover:bg-white/10">
            {copied ? t('friends.profile.copied') : `#${me.code.split('#')[1] ?? ''}`}
          </button>
        }
        corner={
        <button
          onClick={() => setPicker((v) => !v)}
          className={`flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-[11.5px] font-bold backdrop-blur transition-colors ${
            picker ? 'bg-white text-black' : 'bg-black/40 text-white/85 hover:bg-black/60 hover:text-white'
          }`}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
            <path d="M12 20a8 8 0 110-16c4.4 0 8 3 8 6.5 0 2.5-2 3.5-4 3.5h-1.5a1.5 1.5 0 00-1 2.6A1.5 1.5 0 0112 20zM7.5 11h.01M10 7.5h.01M14.5 7.5h.01" />
          </svg>
          {t('friends.me.banner')}
        </button>
        }
        // Choisir sa bannière et sa couleur, et qui les voit : sous l'en-tête.
        beforeName={
      <AnimatePresence initial={false}>
        {picker && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
            <div className="flex flex-col gap-3 border-b border-line-soft bg-black/25 px-4 pb-4 pt-3">
              <div className="grid grid-cols-6 gap-1.5">
                <Swatch selected={!draft.banner} onClick={() => edit({ banner: null })} title={t('friends.me.noBanner')} style={{ background: `linear-gradient(135deg, ${color}66, ${color}14)` }} />
                {BANNER_IDS.map((id) => (
                  <Swatch key={id} selected={draft.banner === id} onClick={() => edit({ banner: id })} title={id} style={{ background: BANNERS[id] }} />
                ))}
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                <button
                  onClick={() => edit({ accent: null })}
                  title={t('friends.me.noAccent')}
                  className={`h-6 w-6 rounded-full border-2 bg-transparent ${!draft.accent ? 'border-white' : 'border-line-strong'}`}
                />
                {ACCENTS.map((c) => (
                  <button
                    key={c}
                    onClick={() => edit({ accent: c })}
                    title={c}
                    className={`h-6 w-6 rounded-full border-2 transition-transform hover:scale-110 ${draft.accent === c ? 'border-white' : 'border-transparent'}`}
                    style={{ background: c }}
                  />
                ))}
              </div>
              <VisibilityPicker value={draft.banner_visibility} onChange={(v) => edit({ banner_visibility: v })} />
            </div>
          </motion.div>
        )}
      </AnimatePresence>
        }
      >

      <div className="flex flex-col gap-2.5 px-4 pb-4 pt-3">
        {/* Bio */}
        <Section title={t('friends.me.bio')} visibility={draft.bio_visibility} onVisibility={(v) => edit({ bio_visibility: v })}>
          {draft.bio_hidden && <p className="mb-1.5 text-[11px] text-warning">{t('friends.me.bioHidden')}</p>}
          <textarea
            value={draft.bio ?? ''}
            rows={3}
            maxLength={BIO_MAX}
            onChange={(e) => edit({ bio: e.target.value.split('\n').slice(0, BIO_LINES).join('\n') })}
            placeholder={t('friends.me.bioPlaceholder')}
            className="w-full resize-none rounded-lg border border-line bg-black/30 px-2.5 py-2 text-[12.5px] leading-relaxed outline-none placeholder:text-txt-muted focus:border-accent/60"
          />
          <p className="mt-1 text-right text-[10.5px] tabular-nums text-txt-muted">
            {(draft.bio ?? '').length} / {BIO_MAX}
          </p>
        </Section>

        {/* Vitrine */}
        <Section title={t('friends.me.showcase')} visibility={draft.showcase_visibility} onVisibility={(v) => edit({ showcase_visibility: v })}>
          {/* L'aperçu suit les cases cochées, dans leur ordre. */}
          {preview.length > 0 && (
            <div className="mb-2.5">
              <ProfileShowcase items={preview} />
            </div>
          )}
          <div className="flex flex-wrap gap-1.5">
            {SHOWCASE_CHOICES.map(({ item, key }) => {
              const on = draft.showcase.some((s) => same(s, item))
              return (
                <button
                  key={key}
                  onClick={() => toggleShowcase(item)}
                  className={`rounded-lg border px-2.5 py-1.5 text-[12px] font-semibold transition-colors ${
                    on ? 'border-accent/60 bg-accent/20 text-white' : 'border-line text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  {t(`friends.me.showcaseItems.${key}`)}
                </button>
              )
            })}
          </div>
          <p className="mt-1.5 text-[11px] text-txt-muted">{t('friends.me.showcaseHint')}</p>
        </Section>

        {/* Compte principal */}
        <Section title={t('friends.me.mainAccount')}>
          {draft.accounts.length === 0 ? (
            <p className="text-[12px] text-txt-muted">{t('friends.me.noAccounts')}</p>
          ) : (
            <div className="flex flex-col gap-1">
              {draft.accounts.map((a) => {
                const on = (draft.main_mc_uuid ?? draft.accounts[0]?.uuid) === a.uuid
                return (
                  <button
                    key={a.uuid}
                    onClick={() => edit({ main_mc_uuid: a.uuid })}
                    className={`flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors ${on ? 'bg-accent/15' : 'hover:bg-white/[0.04]'}`}
                  >
                    <SkinFace dataUri={`https://mc-heads.net/skin/${a.uuid}`} size={22} className="rounded-[5px]" />
                    <span className="flex-1 truncate text-[12.5px] font-semibold">{a.name}</span>
                    <span className={`h-3.5 w-3.5 rounded-full border-2 ${on ? 'border-accent bg-accent' : 'border-line-strong'}`} />
                  </button>
                )
              })}
            </div>
          )}
        </Section>
      </div>

      {/* Enregistrer : seulement s'il y a quelque chose à enregistrer. */}
      <AnimatePresence>
        {dirty && (
          <motion.div
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 12 }}
            className="sticky bottom-0 mt-auto flex items-center justify-end gap-2 border-t border-line-soft bg-[#111116]/95 px-4 py-3 backdrop-blur"
          >
            <button
              disabled={saving}
              onClick={() => {
                setDraft(saved)
                setStatus(custom ?? '')
              }}
              className="rounded-lg px-3 py-2 text-[12px] font-semibold text-txt-muted hover:text-white"
            >
              {t('common.cancel')}
            </button>
            <button disabled={saving} onClick={() => void save()} className="rounded-lg bg-accent px-4 py-2 text-[12px] font-bold text-white disabled:opacity-50">
              {t('friends.custom.save')}
            </button>
          </motion.div>
        )}
      </AnimatePresence>
      </ProfileLayout>
    </motion.div>
  )
}

function Swatch({ selected, onClick, title, style }: { selected: boolean; onClick: () => void; title: string; style: React.CSSProperties }) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`h-9 rounded-lg border-2 transition-transform hover:scale-105 ${selected ? 'border-white' : 'border-transparent'}`}
      style={style}
    />
  )
}

/** Une partie du profil : son titre, qui la voit, puis son contenu. */
function Section({ title, visibility, onVisibility, children }: {
  title: string
  visibility?: Visibility3
  onVisibility?: (v: Visibility3) => void
  children: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-line bg-surface-1 px-3 py-2.5">
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-txt-muted">{title}</span>
        {visibility && onVisibility && <VisibilityPicker value={visibility} onChange={onVisibility} compact />}
      </div>
      {children}
    </div>
  )
}

/** Qui voit cette partie : tout le monde, mes amis, moi seul. */
function VisibilityPicker({ value, onChange, compact = false }: { value: Visibility3; onChange: (v: Visibility3) => void; compact?: boolean }) {
  const t = useT()
  return (
    <div className={`flex items-center gap-0.5 rounded-lg bg-black/30 p-0.5 ${compact ? '' : 'self-start'}`}>
      {(['everyone', 'friends', 'me'] as Visibility3[]).map((v) => (
        <button
          key={v}
          onClick={() => onChange(v)}
          title={t(`friends.me.visibility.${v}`)}
          className={`rounded-md px-2 py-0.5 text-[10.5px] font-semibold transition-colors ${value === v ? 'bg-white/[0.12] text-white' : 'text-txt-muted hover:text-txt-secondary'}`}
        >
          {t(`friends.me.visibility.${v}`)}
        </button>
      ))}
    </div>
  )
}
