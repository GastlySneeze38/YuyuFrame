import { useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { api } from '@/api/client'
import { ModalShell } from '@/components/ui/ModalShell'
import { SkinFace } from '@/components/ui/SkinFace'
import { SHOWCASE_MAX, STATUS_COLORS, sameShowcase } from '@/lib/friends'
import { demoMyShowcase } from '@/lib/friendsDemo'
import { useContentAsset } from '@/lib/contentAsset'
import { ACCENTS, BANNER_IDS, BANNERS } from '@/lib/profileBanners'
import { isDemo } from '@/stores/useDevFlags'
import { showError, showNotice } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import type { LibraryItem, MyProfile, MyStatusKind, ProfileLibrary, ShowcaseItem, ShowcaseView, Visibility3 } from '@/types/friends'
import { FriendAvatar } from './FriendBits'
import { ThoughtBubble } from './FriendProfile'
import { ProfileLayout } from './ProfileLayout'
import { ProfileShowcase } from './ProfileShowcase'

const BIO_MAX = 190
const BIO_LINES = 3

/** Les parties qu'on modifie, une à la fois. */
type Part = 'banner' | 'accent' | 'bio' | 'showcase' | 'account'
const PARTS: Part[] = ['banner', 'accent', 'bio', 'showcase', 'account']

/** Ma vitrine telle que la verraient mes amis, pour ces objets. Un objet sans
 *  valeur (rang caché en jeu, titre échu) n'apparaît pas. */
export function showcasePreview(items: ShowcaseItem[], served: ShowcaseView[], library?: ProfileLibrary): ShowcaseView[] {
  if (isDemo()) return demoMyShowcase(items)
  return items.flatMap((item): ShowcaseView[] => {
    // Un titre choisi se montre d'après ma bibliothèque : le serveur ne le
    // renverra qu'une fois enregistré.
    if (item.kind === 'title' && item.code && library) {
      const title = library.titles.find((t) => t.code === item.code)
      return title ? [{ kind: 'title', code: title.code, label: title.label, asset: title.asset }] : []
    }
    return served
      .filter((v) => v.kind === item.kind && (item.kind === 'title' ? !item.code || (v.kind === 'title' && v.code === item.code) : 'mode' in v && 'mode' in item && v.mode === item.mode))
      .slice(0, 1)
  })
}

/** Sans bibliothèque (API plus ancienne) : tout est à disposition, comme avant. */
function fallbackLibrary(): ProfileLibrary {
  const all = (codes: string[]): LibraryItem[] => codes.map((code) => ({ code, owned: true, unlock: null }))
  return { banners: all(BANNER_IDS), accents: all(ACCENTS), titles: [] }
}

/**
 * La modification de mon profil. On choisit d'abord CE qu'on modifie (à
 * gauche), et seule cette partie s'affiche, sous forme de bibliothèque : ce
 * que je possède, et ce qui reste à débloquer (et où). À droite, la fiche
 * telle que mes amis la verront, qui suit chaque choix. Rien ne part avant
 * « Enregistrer ».
 *
 * Le statut personnalisé n'est pas ici : il se change dans la bulle de ma
 * fiche, d'un geste.
 */
export function ProfileEditor({
  profile,
  served,
  status,
  custom,
  username,
  onSaved,
  onClose,
}: {
  profile: MyProfile
  served: ShowcaseView[]
  status: MyStatusKind
  custom: string | null
  username: string
  onSaved: (p: MyProfile) => void
  onClose: () => void
}) {
  const t = useT()
  const [draft, setDraft] = useState<MyProfile>(profile)
  const [part, setPart] = useState<Part>('banner')
  const [saving, setSaving] = useState(false)
  const library = profile.library ?? fallbackLibrary()
  const dirty = useMemo(() => {
    const strip = (p: MyProfile) => JSON.stringify({ ...p, library: undefined, accounts: undefined })
    return strip(draft) !== strip(profile)
  }, [draft, profile])
  const edit = (patch: Partial<MyProfile>) => setDraft((d) => ({ ...d, ...patch }))
  const main = draft.accounts.find((a) => a.uuid === draft.main_mc_uuid) ?? draft.accounts[0] ?? null
  const color = STATUS_COLORS[status]

  async function save() {
    if (isDemo()) {
      onSaved(draft)
      showNotice(t('friends.me.saved'))
      onClose()
      return
    }
    setSaving(true)
    try {
      onSaved(await api.friends.setProfile(draft))
      showNotice(t('friends.me.saved'))
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  return createPortal(
    <ModalShell title={t('friends.me.editTitle')} onClose={onClose} maxWidth="max-w-6xl">
      <div className="grid h-[64vh] max-h-[620px] grid-cols-[200px_minmax(0,1fr)_340px] gap-5">
        {/* Ce qu'on modifie : une partie à la fois. */}
        <nav className="flex flex-col gap-1">
          {PARTS.map((p) => (
            <button
              key={p}
              onClick={() => setPart(p)}
              className={`flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-left transition-colors ${
                part === p ? 'bg-accent/20 text-white' : 'text-txt-secondary hover:bg-white/[0.04] hover:text-white'
              }`}
            >
              <PartSummary part={p} draft={draft} color={color} />
              <span className="flex min-w-0 flex-col">
                <span className="text-[13px] font-bold">{t(`friends.me.parts.${p}`)}</span>
                <span className="truncate text-[11px] text-txt-muted">
                  {p === 'showcase'
                    ? t('friends.me.showcaseCount', { count: draft.showcase.length, max: SHOWCASE_MAX })
                    : p === 'account'
                      ? main?.name ?? '—'
                      : p === 'bio'
                        ? draft.bio
                          ? `${(draft.bio ?? '').length} / ${BIO_MAX}`
                          : t('friends.me.noBio')
                        : t(`friends.me.partHints.${p}`)}
                </span>
              </span>
            </button>
          ))}
        </nav>

        {/* La bibliothèque de la partie choisie. */}
        <div className="flex min-h-0 flex-col overflow-y-auto rounded-2xl border border-line bg-surface-1 p-4">
          {part === 'banner' && (
            <BannerLibrary library={library} value={draft.banner} color={color} visibility={draft.banner_visibility} onPick={(banner) => edit({ banner })} onVisibility={(v) => edit({ banner_visibility: v })} />
          )}
          {part === 'accent' && <AccentLibrary library={library} value={draft.accent} onPick={(accent) => edit({ accent })} />}
          {part === 'bio' && (
            <>
              <PartHeader title={t('friends.me.parts.bio')} visibility={draft.bio_visibility} onVisibility={(v) => edit({ bio_visibility: v })} />
              {draft.bio_hidden && <p className="mb-2 text-[12px] text-warning">{t('friends.me.bioHidden')}</p>}
              <textarea
                value={draft.bio ?? ''}
                rows={5}
                maxLength={BIO_MAX}
                onChange={(e) => edit({ bio: e.target.value.split('\n').slice(0, BIO_LINES).join('\n') })}
                placeholder={t('friends.me.bioPlaceholder')}
                className="w-full resize-none rounded-xl border border-line bg-black/30 px-3 py-2.5 text-[14px] leading-relaxed outline-none placeholder:text-txt-muted focus:border-accent/60"
              />
              <p className="mt-1 text-right text-[11px] tabular-nums text-txt-muted">
                {(draft.bio ?? '').length} / {BIO_MAX}
              </p>
            </>
          )}
          {part === 'showcase' && (
            <ShowcaseLibrary
              library={library}
              value={draft.showcase}
              visibility={draft.showcase_visibility}
              onChange={(showcase) => edit({ showcase })}
              onVisibility={(v) => edit({ showcase_visibility: v })}
            />
          )}
          {part === 'account' && (
            <>
              <PartHeader title={t('friends.me.parts.account')} />
              {draft.accounts.length === 0 ? (
                <p className="text-[12.5px] text-txt-muted">{t('friends.me.noAccounts')}</p>
              ) : (
                <div className="grid grid-cols-2 gap-2">
                  {draft.accounts.map((a) => {
                    const on = (draft.main_mc_uuid ?? draft.accounts[0]?.uuid) === a.uuid
                    return (
                      <button
                        key={a.uuid}
                        onClick={() => edit({ main_mc_uuid: a.uuid })}
                        className={`flex items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition-colors ${
                          on ? 'border-accent/60 bg-accent/15' : 'border-line hover:border-line-strong'
                        }`}
                      >
                        <SkinFace dataUri={`https://mc-heads.net/skin/${a.uuid}`} size={32} className="rounded-[7px]" />
                        <span className="flex-1 truncate text-[13.5px] font-semibold">{a.name}</span>
                        <span className={`h-4 w-4 rounded-full border-2 ${on ? 'border-accent bg-accent' : 'border-line-strong'}`} />
                      </button>
                    )
                  })}
                </div>
              )}
              <p className="mt-3 text-[12px] text-txt-muted">{t('friends.me.accountHint')}</p>
            </>
          )}
        </div>

        {/* La fiche telle que la verront mes amis, en direct. */}
        <div className="flex min-h-0 flex-col gap-2">
          <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t('friends.me.preview')}</span>
          <div className="min-h-0 flex-1 overflow-hidden rounded-2xl border border-line bg-[#111116]">
            <ProfileLayout
              banner={draft.banner}
              accent={draft.accent}
              color={color}
              avatar={<FriendAvatar skin={`https://mc-heads.net/skin/${main?.uuid ?? 'MHF_Steve'}`} state={status} size={72} />}
              bubble={custom ? <ThoughtBubble accent={draft.accent}>{custom}</ThoughtBubble> : null}
              name={username}
            >
              <div className="flex flex-col gap-2 px-4 pb-4 pt-1.5">
                {draft.bio && <p className="whitespace-pre-line text-[12.5px] leading-relaxed text-txt-secondary [overflow-wrap:anywhere]">{draft.bio}</p>}
                <ProfileShowcase items={showcasePreview(draft.showcase, served, library)} />
              </div>
            </ProfileLayout>
          </div>
        </div>
      </div>

      <div className="mt-5 flex items-center justify-end gap-2 border-t border-line-soft pt-4">
        <button onClick={onClose} disabled={saving} className="rounded-lg px-4 py-2 text-[13px] font-semibold text-txt-muted hover:text-white">
          {t('common.cancel')}
        </button>
        <button
          onClick={() => void save()}
          disabled={saving || !dirty}
          className="rounded-lg bg-accent px-5 py-2 text-[13px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
        >
          {t('friends.custom.save')}
        </button>
      </div>
    </ModalShell>,
    document.body,
  )
}

/** La petite vignette d'une partie dans le menu : ce qui est choisi en ce moment. */
function PartSummary({ part, draft, color }: { part: Part; draft: MyProfile; color: string }) {
  const box = 'h-8 w-8 shrink-0 rounded-lg border border-white/10'
  if (part === 'banner') return <span className={box} style={{ background: draft.banner ? BANNERS[draft.banner] : `linear-gradient(135deg, ${color}66, ${color}14)` }} />
  if (part === 'accent') return <span className={`${box} !rounded-full`} style={{ background: draft.accent ?? 'transparent' }} />
  const icon: Record<Exclude<Part, 'banner' | 'accent'>, string> = {
    bio: 'M5 6h14M5 11h14M5 16h9',
    showcase: 'M4 6h7v7H4zM13 6h7v7h-7zM4 15h16',
    account: 'M12 12a4 4 0 100-8 4 4 0 000 8zM4 20a8 8 0 0116 0',
  }
  return (
    <span className={`${box} flex items-center justify-center bg-white/[0.04]`}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
        <path d={icon[part]} />
      </svg>
    </span>
  )
}

/** Le titre d'une partie, et qui la voit. */
function PartHeader({ title, visibility, onVisibility, note }: { title: string; visibility?: Visibility3; onVisibility?: (v: Visibility3) => void; note?: string }) {
  const t = useT()
  return (
    <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
      <div className="flex flex-col">
        <h3 className="text-[15px] font-bold">{title}</h3>
        {note && <span className="text-[11.5px] text-txt-muted">{note}</span>}
      </div>
      {visibility && onVisibility && (
        <div className="flex items-center gap-2">
          <span className="text-[11.5px] text-txt-muted">{t('friends.me.whoSees')}</span>
          <div className="flex items-center gap-0.5 rounded-lg bg-black/30 p-0.5">
            {(['everyone', 'friends', 'me'] as Visibility3[]).map((v) => (
              <button
                key={v}
                onClick={() => onVisibility(v)}
                className={`rounded-md px-2.5 py-1 text-[11.5px] font-semibold transition-colors ${
                  visibility === v ? 'bg-white/[0.12] text-white' : 'text-txt-muted hover:text-txt-secondary'
                }`}
              >
                {t(`friends.me.visibility.${v}`)}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

/** Possédé : choisissable. Sinon : cadenas, et où le débloquer. */
function LockNote({ item }: { item: LibraryItem }) {
  const t = useT()
  if (item.owned) return null
  return (
    <span className="flex items-center gap-1 text-[10.5px] font-semibold text-white/80">
      <svg viewBox="0 0 24 24" fill="currentColor" className="h-3 w-3 shrink-0">
        <path d="M7 10V8a5 5 0 0110 0v2h1a1 1 0 011 1v9a1 1 0 01-1 1H6a1 1 0 01-1-1v-9a1 1 0 011-1h1zm2 0h6V8a3 3 0 00-6 0v2z" />
      </svg>
      {item.unlock ? t('friends.me.unlockTier', { tier: item.unlock.tier }) : t('friends.me.locked')}
    </span>
  )
}

/** « 5 / 10 possédées » et le filtre « tout / possédés ». */
function useOwnedFilter(items: LibraryItem[]) {
  const [onlyOwned, setOnlyOwned] = useState(false)
  const owned = items.filter((i) => i.owned).length
  const shown = onlyOwned ? items.filter((i) => i.owned) : items
  return { onlyOwned, setOnlyOwned, owned, shown }
}

function OwnedFilter({ owned, total, onlyOwned, onChange }: { owned: number; total: number; onlyOwned: boolean; onChange: (v: boolean) => void }) {
  const t = useT()
  return (
    <div className="mb-3 flex items-center justify-between gap-3">
      <span className="text-[12px] text-txt-muted">{t('friends.me.ownedCount', { owned, total })}</span>
      <div className="flex rounded-lg bg-black/30 p-0.5">
        {[false, true].map((v) => (
          <button
            key={String(v)}
            onClick={() => onChange(v)}
            className={`rounded-md px-2.5 py-1 text-[11.5px] font-semibold ${onlyOwned === v ? 'bg-white/[0.12] text-white' : 'text-txt-muted hover:text-txt-secondary'}`}
          >
            {t(v ? 'friends.me.filterOwned' : 'friends.me.filterAll')}
          </button>
        ))}
      </div>
    </div>
  )
}

function BannerLibrary({ library, value, color, visibility, onPick, onVisibility }: {
  library: ProfileLibrary
  value: string | null
  color: string
  visibility: Visibility3
  onPick: (banner: string | null) => void
  onVisibility: (v: Visibility3) => void
}) {
  const t = useT()
  const filter = useOwnedFilter(library.banners)
  return (
    <>
      <PartHeader title={t('friends.me.parts.banner')} visibility={visibility} onVisibility={onVisibility} />
      <OwnedFilter owned={filter.owned} total={library.banners.length} onlyOwned={filter.onlyOwned} onChange={filter.setOnlyOwned} />
      <div className="grid grid-cols-3 gap-2.5">
        <LibraryTile selected={!value} onClick={() => onPick(null)} label={t('friends.me.noBanner')} background={`linear-gradient(135deg, ${color}66, ${color}14)`} />
        {filter.shown.map((item) => (
          <LibraryTile
            key={item.code}
            selected={value === item.code}
            locked={!item.owned}
            onClick={() => item.owned && onPick(item.code)}
            background={BANNERS[item.code] ?? '#222'}
            note={<LockNote item={item} />}
          />
        ))}
      </div>
    </>
  )
}

function LibraryTile({ selected, locked = false, onClick, background, label, note }: {
  selected: boolean
  locked?: boolean
  onClick: () => void
  background: string
  label?: string
  note?: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      disabled={locked}
      className={`relative flex h-20 flex-col items-start justify-end gap-1 overflow-hidden rounded-xl border-2 p-2 text-left transition-transform ${
        selected ? 'border-white' : 'border-transparent'
      } ${locked ? 'cursor-not-allowed' : 'hover:scale-[1.03]'}`}
      style={{ background }}
    >
      {locked && <span className="absolute inset-0 bg-black/55" />}
      {label && <span className="relative rounded bg-black/40 px-1.5 text-[11px] font-semibold text-white/90">{label}</span>}
      {note && <span className="relative rounded bg-black/50 px-1.5 py-0.5">{note}</span>}
    </button>
  )
}

function AccentLibrary({ library, value, onPick }: { library: ProfileLibrary; value: string | null; onPick: (accent: string | null) => void }) {
  const t = useT()
  const filter = useOwnedFilter(library.accents)
  return (
    <>
      {/* La couleur suit la visibilité de la bannière (même bandeau). */}
      <PartHeader title={t('friends.me.parts.accent')} note={t('friends.me.accentNote')} />
      <OwnedFilter owned={filter.owned} total={library.accents.length} onlyOwned={filter.onlyOwned} onChange={filter.setOnlyOwned} />
      <div className="grid grid-cols-4 gap-3">
        <button
          onClick={() => onPick(null)}
          className={`flex flex-col items-center gap-2 rounded-xl border p-3 transition-colors ${!value ? 'border-white bg-white/[0.06]' : 'border-line hover:border-line-strong'}`}
        >
          <span className="h-10 w-10 rounded-full border-2 border-dashed border-line-strong" />
          <span className="text-[11.5px] font-semibold text-txt-secondary">{t('friends.me.noAccent')}</span>
        </button>
        {filter.shown.map((item) => (
          <button
            key={item.code}
            onClick={() => item.owned && onPick(item.code)}
            disabled={!item.owned}
            className={`flex flex-col items-center gap-2 rounded-xl border p-3 transition-colors ${
              value === item.code ? 'border-white bg-white/[0.06]' : 'border-line hover:border-line-strong'
            } ${item.owned ? '' : 'cursor-not-allowed opacity-70'}`}
          >
            <span className="relative h-10 w-10 rounded-full" style={{ background: item.code }}>
              {!item.owned && <span className="absolute inset-0 rounded-full bg-black/50" />}
            </span>
            {item.owned ? <span className="text-[11.5px] font-semibold text-txt-secondary">{item.code}</span> : <LockNote item={item} />}
          </button>
        ))}
      </div>
    </>
  )
}

/** La vitrine : 2 objets précis, pris dans tout ce que je possède. */
function ShowcaseLibrary({ library, value, visibility, onChange, onVisibility }: {
  library: ProfileLibrary
  value: ShowcaseItem[]
  visibility: Visibility3
  onChange: (items: ShowcaseItem[]) => void
  onVisibility: (v: Visibility3) => void
}) {
  const t = useT()
  const full = value.length >= SHOWCASE_MAX
  const toggle = (item: ShowcaseItem) => {
    const has = value.some((s) => sameShowcase(s, item))
    if (has) onChange(value.filter((s) => !sameShowcase(s, item)))
    else if (!full) onChange([...value, item])
  }
  const groups: { key: string; items: { item: ShowcaseItem; label: string; icon: React.ReactNode }[] }[] = [
    {
      key: 'ranks',
      items: (['mace', 'sword'] as const).map((mode) => ({ item: { kind: 'rank', mode }, label: t(`friends.me.showcaseItems.rank${mode === 'mace' ? 'Mace' : 'Sword'}`), icon: <GroupIcon d="M12 3l2.5 5 5.5.8-4 3.9.9 5.5L12 15.6 7.1 18.2 8 12.7 4 8.8l5.5-.8z" /> })),
    },
    {
      key: 'seasons',
      items: (['mace', 'sword'] as const).map((mode) => ({ item: { kind: 'season', mode }, label: t(`friends.me.showcaseItems.season${mode === 'mace' ? 'Mace' : 'Sword'}`), icon: <GroupIcon d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z" /> })),
    },
    {
      key: 'titles',
      items: library.titles.map((title) => ({ item: { kind: 'title', code: title.code }, label: title.label, icon: <TitleIcon asset={title.asset} /> })),
    },
  ]
  return (
    <>
      <PartHeader title={t('friends.me.parts.showcase')} visibility={visibility} onVisibility={onVisibility} note={t('friends.me.showcasePick', { max: SHOWCASE_MAX })} />
      <div className="flex flex-col gap-4">
        {groups.map((group) => (
          <div key={group.key} className="flex flex-col gap-2">
            <span className="text-[11px] font-bold uppercase tracking-[0.06em] text-txt-muted">{t(`friends.me.showcaseGroups.${group.key}`)}</span>
            {group.items.length === 0 ? (
              <p className="text-[12px] text-txt-muted">{t('friends.me.noTitles')}</p>
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {group.items.map(({ item, label, icon }) => {
                  const on = value.some((s) => sameShowcase(s, item))
                  const order = value.findIndex((s) => sameShowcase(s, item))
                  return (
                    <button
                      key={label}
                      onClick={() => toggle(item)}
                      disabled={!on && full}
                      className={`flex items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
                        on ? 'border-accent/60 bg-accent/15 text-white' : 'border-line text-txt-secondary hover:border-line-strong hover:text-white'
                      }`}
                    >
                      {icon}
                      <span className="flex-1 truncate text-[13px] font-semibold">{label}</span>
                      {on && <span className="flex h-5 w-5 items-center justify-center rounded-full bg-accent text-[11px] font-black text-white">{order + 1}</span>}
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        ))}
        <p className="text-[12px] text-txt-muted">{t('friends.me.showcaseHint')}</p>
      </div>
    </>
  )
}

function GroupIcon({ d }: { d: string }) {
  return (
    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/[0.05]">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinejoin="round" className="h-4 w-4">
        <path d={d} />
      </svg>
    </span>
  )
}

function TitleIcon({ asset }: { asset: string | null }) {
  const image = useContentAsset(asset)
  if (image) return <img src={image} alt="" className="h-8 w-8 shrink-0 object-contain" />
  return (
    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#f5b74a]/15 text-[#f5b74a]">
      <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
        <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5L3 8z" />
      </svg>
    </span>
  )
}
