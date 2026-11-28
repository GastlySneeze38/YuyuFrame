import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showApiError } from '@/stores/useErrorToast'
import { ACCENTS, BANNER_IDS, bannerBackground } from '@/lib/profileBanners'
import { useT } from '@/i18n'
import type { MyProfile } from '@/types/friends'
import { EASE, Ghost, Item, Primary, Stagger } from '../ui'

const BIO_MAX = 190

/**
 * Le profil vu par mes amis, facultatif : bannière, couleur et bio. Seul ce
 * que je possède déjà se propose (la bibliothèque du serveur) ; le reste se
 * débloque plus tard et se change dans le panneau des amis. La fiche en haut
 * suit chaque choix.
 */
export function ProfileStep({ onNext }: { onNext: () => void }) {
  const t = useT()
  const username = useStore((s) => s.yuyuUsername)
  const uuid = useStore((s) => s.uuid)
  const [profile, setProfile] = useState<MyProfile | null>(null)
  const [draft, setDraft] = useState<MyProfile | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    api.friends.myProfile()
      .then((p) => { setProfile(p); setDraft(p) })
      .catch(() => setFailed(true))
  }, [])

  if (failed) {
    return (
      <Stagger className="flex flex-col items-center gap-3 py-4 text-center">
        <Item><p className="text-[13px] text-white/65">{t('common.serverUnreachable')}</p></Item>
        <Item><Primary onClick={onNext}>{t('onboarding.continue')}</Primary></Item>
      </Stagger>
    )
  }
  if (!draft || !profile) {
    return <div className="h-64 animate-pulse rounded-2xl bg-white/[0.04]" />
  }

  const owned = (items: { code: string; owned: boolean }[] | undefined, all: string[]) =>
    items ? items.filter((i) => i.owned).map((i) => i.code) : all
  const banners = owned(profile.library?.banners, BANNER_IDS)
  const accents = owned(profile.library?.accents, ACCENTS)
  const color = draft.accent ?? '#7b72e9'
  const changed = draft.banner !== profile.banner || draft.accent !== profile.accent || (draft.bio ?? '') !== (profile.bio ?? '')

  const save = async () => {
    if (!changed) return onNext()
    setBusy(true)
    try {
      await api.friends.setProfile(draft)
      onNext()
    } catch (e) {
      showApiError(e, t('common.serverUnreachable'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Stagger className="flex flex-col gap-4">
      {/* La fiche : le bandeau change en fondu, la couleur glisse. */}
      <Item>
        <div className="overflow-hidden rounded-2xl border border-white/10 bg-black/45">
          <div className="relative h-20">
            <motion.div
              key={draft.banner ?? color}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.45, ease: EASE }}
              className="absolute inset-0"
              style={{ background: bannerBackground(draft.banner, color) }}
            />
          </div>
          {/* Au-dessus de la bannière (positionnée, elle se peindrait sinon par-dessus) ;
              la tête mord sur elle, le pseudo reste dessous. */}
          <div className="relative -mt-8 flex items-start gap-3 px-4 pb-4">
            <div className="relative rounded-2xl p-[3px] transition-colors duration-300" style={{ background: color }}>
              <img
                src={`https://mc-heads.net/avatar/${uuid ?? username ?? 'MHF_Steve'}/64`}
                alt=""
                width={56}
                height={56}
                className="rounded-xl [image-rendering:pixelated]"
              />
            </div>
            <div className="min-w-0 pt-9">
              <p className="truncate text-[17px] font-black text-white">{username}</p>
              <p className="line-clamp-2 text-[12px] leading-snug text-white/55">{draft.bio || t('onboarding.profile.noBio')}</p>
            </div>
          </div>
        </div>
      </Item>

      <Item>
        <Label>{t('onboarding.profile.banner')}</Label>
        <div className="grid grid-cols-5 gap-1.5">
          {banners.map((b) => (
            <button
              key={b}
              type="button"
              onClick={() => setDraft({ ...draft, banner: b })}
              className={`relative h-10 overflow-hidden rounded-lg transition-transform duration-150 active:scale-[0.95] ${draft.banner === b ? '' : 'opacity-75 hover:opacity-100'}`}
              style={{ background: bannerBackground(b, color) }}
            >
              {draft.banner === b && (
                <motion.span layoutId="ob-banner-ring" className="absolute inset-0 rounded-lg border-2 border-white" transition={{ type: 'spring', duration: 0.4, bounce: 0.15 }} />
              )}
            </button>
          ))}
        </div>
      </Item>

      <Item>
        <Label>{t('onboarding.profile.color')}</Label>
        <div className="flex flex-wrap gap-2">
          {accents.map((a) => (
            <button
              key={a}
              type="button"
              onClick={() => setDraft({ ...draft, accent: a })}
              aria-label={a}
              className="relative h-8 w-8 rounded-full transition-transform duration-150 active:scale-[0.9] max-sm:h-10 max-sm:w-10"
              style={{ background: a }}
            >
              {draft.accent === a && (
                <motion.span layoutId="ob-accent-ring" className="absolute -inset-[3px] rounded-full border-2 border-white" transition={{ type: 'spring', duration: 0.4, bounce: 0.2 }} />
              )}
            </button>
          ))}
        </div>
      </Item>

      <Item>
        <Label>{t('onboarding.profile.bio')}</Label>
        <textarea
          value={draft.bio ?? ''}
          rows={2}
          maxLength={BIO_MAX}
          onChange={(e) => setDraft({ ...draft, bio: e.target.value.split('\n').slice(0, 3).join('\n') })}
          placeholder={t('friends.me.bioPlaceholder')}
          className="w-full resize-none rounded-xl border border-white/10 bg-black/35 px-3.5 py-2.5 text-[14px] text-white outline-none placeholder:text-white/25 focus:border-[rgb(var(--accent)/0.7)] max-sm:text-[16px]"
        />
      </Item>

      <Item className="flex items-center gap-2">
        <div className="flex-1">
          <Primary onClick={() => void save()} loading={busy}>
            {changed ? t('common.save') : t('onboarding.continue')}
          </Primary>
        </div>
        {!changed && <Ghost onClick={onNext}>{t('onboarding.skip')}</Ghost>}
      </Item>
    </Stagger>
  )
}

function Label({ children }: { children: React.ReactNode }) {
  return <span className="mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-white/45 max-sm:text-[12px]">{children}</span>
}
