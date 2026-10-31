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
      {/* Trois colonnes à toutes les tailles ; sur une petite fenêtre, le menu
          et l'aperçu rétrécissent pour laisser la place à la bibliothèque.
          La hauteur est celle de la fenêtre moins le titre, le pied et les
          marges de la modale (≈ 205 px) : la modale tient toujours à l'écran,
          et seule la liste de la bibliothèque défile. */}
      <div className="grid h-[min(620px,calc(100vh-205px))] min-h-[240px] grid-cols-[156px_minmax(0,1fr)_250px] gap-3 lg:grid-cols-[190px_minmax(0,1fr)_300px] lg:gap-5 xl:grid-cols-[200px_minmax(0,1fr)_340px]">
        {/* Ce qu'on modifie : une partie à la fois. */}
        <nav className="flex min-w-0 flex-col gap-1 overflow-y-auto">
          {PARTS.map((p) => (
            <button
              key={p}
              onClick={() => setPart(p)}
              className={`flex items-center gap-2.5 rounded-xl px-2 py-2 text-left transition-colors lg:px-3 lg:py-2.5 ${
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
        {/* Les bibliothèques font défiler leur seule liste (en-tête fixe) ;
            la bio et le compte, courts, défilent en entier s'il le faut. */}
        <div
          className={`flex min-h-0 min-w-0 flex-col rounded-2xl border border-line bg-surface-1 p-3 lg:p-4 ${
            part === 'bio' || part === 'account' ? 'overflow-y-auto' : 'overflow-hidden'
          }`}
        >
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
                <div className="grid grid-cols-1 gap-2 lg:grid-cols-2">
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

      {/* Le pied reste mince : l'écart de la modale (gap-5) le sépare déjà
          du contenu, toute la hauteur doit aller aux colonnes. */}
      <div className="-mt-1 flex items-center justify-end gap-2 border-t border-line-soft pt-3">
        <button onClick={onClose} disabled={saving} className="rounded-lg px-4 py-1.5 text-[13px] font-semibold text-txt-muted hover:text-white">
          {t('common.cancel')}
        </button>
        <button
          onClick={() => void save()}
          disabled={saving || !dirty}
          className="rounded-lg bg-accent px-5 py-1.5 text-[13px] font-bold text-white transition-colors hover:bg-accent-hover disabled:opacity-40"
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
    // Le titre et « qui la voit » côte à côte quand il y a la place, l'un
    // sous l'autre sinon : jamais un libellé coupé mot par mot.
    <div className="mb-3 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
      <div className="flex min-w-0 flex-col">
        <h3 className="text-[15px] font-bold">{title}</h3>
        {note && <span className="text-[11.5px] text-txt-muted">{note}</span>}
      </div>
      {visibility && onVisibility && (
        <div className="flex shrink-0 items-center gap-2">
          <span className="whitespace-nowrap text-[11.5px] text-txt-muted">{t('friends.me.whoSees')}</span>
          <div className="flex shrink-0 items-center gap-0.5 rounded-lg bg-black/30 p-0.5">
            {(['everyone', 'friends', 'me'] as Visibility3[]).map((v) => (
              <button
                key={v}
                onClick={() => onVisibility(v)}
                className={`whitespace-nowrap rounded-md px-2.5 py-1 text-[11.5px] font-semibold transition-colors ${
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

/** « 5 / 10 possédées » et le filtre « tout / possédés ». Ce qui reste à
 *  débloquer passe toujours en bas de la liste, après tout ce qu'on possède. */
function useOwnedFilter(items: LibraryItem[]) {
  const [onlyOwned, setOnlyOwned] = useState(false)
  const owned = items.filter((i) => i.owned)
  const shown = onlyOwned ? owned : [...owned, ...items.filter((i) => !i.owned)]
  return { onlyOwned, setOnlyOwned, owned: owned.length, shown }
}

/** Une bibliothèque : l'en-tête reste en place, la liste défile en dessous. */
function LibraryLayout({ header, children }: { header: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="shrink-0">{header}</div>
      <div className="-mr-2 min-h-0 flex-1 overflow-y-auto pr-2">{children}</div>
    </div>
  )
}

function OwnedFilter({ owned, total, onlyOwned, onChange }: { owned: number; total: number; onlyOwned: boolean; onChange: (v: boolean) => void }) {
  const t = useT()
  return (
    <div className="mb-3 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
      <span className="whitespace-nowrap text-[12px] text-txt-muted">{t('friends.me.ownedCount', { owned, total })}</span>
      <div className="flex shrink-0 rounded-lg bg-black/30 p-0.5">
        {[false, true].map((v) => (
          <button
            key={String(v)}
            onClick={() => onChange(v)}
            className={`whitespace-nowrap rounded-md px-2.5 py-1 text-[11.5px] font-semibold ${onlyOwned === v ? 'bg-white/[0.12] text-white' : 'text-txt-muted hover:text-txt-secondary'}`}
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
  // Une bannière que cette version du launcher ne sait pas dessiner (ajoutée
  // dans une version plus récente) n'est pas proposée.
  const drawable = library.banners.filter((b) => BANNERS[b.code])
  const filter = useOwnedFilter(drawable)
  return (
    <LibraryLayout
      header={
        <>
          <PartHeader title={t('friends.me.parts.banner')} visibility={visibility} onVisibility={onVisibility} />
          <OwnedFilter owned={filter.owned} total={drawable.length} onlyOwned={filter.onlyOwned} onChange={filter.setOnlyOwned} />
        </>
      }
    >
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-3 lg:gap-2.5">
        <LibraryTile selected={!value} onClick={() => onPick(null)} label={t('friends.me.noBanner')} background={`linear-gradient(135deg, ${color}66, ${color}14)`} />
        {filter.shown.map((item) => (
          <LibraryTile
            key={item.code}
            selected={value === item.code}
            locked={!item.owned}
            onClick={() => item.owned && onPick(item.code)}
            background={BANNERS[item.code]}
            label={item.label}
            note={<LockNote item={item} />}
          />
        ))}
      </div>
    </LibraryLayout>
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
    <LibraryLayout
      header={
        <>
          {/* La couleur suit la visibilité de la bannière (même bandeau). */}
          <PartHeader title={t('friends.me.parts.accent')} note={t('friends.me.accentNote')} />
          <OwnedFilter owned={filter.owned} total={library.accents.length} onlyOwned={filter.onlyOwned} onChange={filter.setOnlyOwned} />
        </>
      }
    >
      <div className="grid grid-cols-3 gap-2 lg:grid-cols-4 lg:gap-3">
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
            <span className="max-w-full truncate text-[11.5px] font-semibold text-txt-secondary">{item.label ?? item.code}</span>
            <LockNote item={item} />
          </button>
        ))}
      </div>
    </LibraryLayout>
  )
}

type ShowcaseGroup = 'ranks' | 'seasons' | 'titles'

/**
 * La vitrine : 2 objets précis, pris dans tout ce que je possède. Un onglet
 * par sorte d'objet, la liste défile (on peut avoir beaucoup de titres) ; en
 * haut, les deux places de la vitrine, visibles quel que soit l'onglet, et
 * qu'on vide d'un clic.
 */
function ShowcaseLibrary({ library, value, visibility, onChange, onVisibility }: {
  library: ProfileLibrary
  value: ShowcaseItem[]
  visibility: Visibility3
  onChange: (items: ShowcaseItem[]) => void
  onVisibility: (v: Visibility3) => void
}) {
  const t = useT()
  const [tab, setTab] = useState<ShowcaseGroup>('ranks')
  const full = value.length >= SHOWCASE_MAX
  const toggle = (item: ShowcaseItem) => {
    const has = value.some((s) => sameShowcase(s, item))
    if (has) onChange(value.filter((s) => !sameShowcase(s, item)))
    else if (!full) onChange([...value, item])
  }
  const groups: { key: ShowcaseGroup; items: { item: ShowcaseItem; label: string; icon: React.ReactNode }[] }[] = [
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
  const all = groups.flatMap((g) => g.items)
  const current = groups.find((g) => g.key === tab) ?? groups[0]
  // Les deux places : ce qui y est, dans l'ordre d'affichage ; un objet que je
  // ne possède plus (titre expiré) n'a pas de libellé et garde un tiret.
  const slots = Array.from({ length: SHOWCASE_MAX }, (_, i) => {
    const item = value[i]
    if (!item) return null
    return { item, entry: all.find((e) => sameShowcase(e.item, item)) ?? null }
  })

  return (
    <LibraryLayout
      header={
        <>
          <PartHeader title={t('friends.me.parts.showcase')} visibility={visibility} onVisibility={onVisibility} note={t('friends.me.showcasePick', { max: SHOWCASE_MAX })} />

          {/* Les places de la vitrine. */}
          <div className="mb-3 grid grid-cols-2 gap-2">
            {slots.map((slot, i) =>
              slot ? (
                <button
                  key={i}
                  onClick={() => toggle(slot.item)}
                  className="group flex items-center gap-2.5 rounded-xl border border-accent/60 bg-accent/15 px-2.5 py-2 text-left"
                >
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-accent text-[11px] font-black text-white">{i + 1}</span>
                  {slot.entry?.icon}
                  <span className="flex-1 truncate text-[12.5px] font-semibold text-white">{slot.entry?.label ?? '—'}</span>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className="h-3.5 w-3.5 shrink-0 text-white/50 group-hover:text-white">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              ) : (
                <div key={i} className="flex items-center gap-2.5 rounded-xl border border-dashed border-line-strong px-2.5 py-2">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-line-strong text-[11px] font-black text-txt-muted">{i + 1}</span>
                  <span className="h-8" />
                </div>
              ),
            )}
          </div>

          {/* Un onglet par sorte d'objet ; le chiffre dit combien en sont choisis. */}
          {/* Les onglets se partagent la largeur ; serrés, ils perdent leur
              compteur plutôt que de défiler. */}
          <div className="mb-3 flex border-b border-line-soft">
            {groups.map((group) => {
              const picked = group.items.filter((e) => value.some((s) => sameShowcase(s, e.item))).length
              return (
                <button
                  key={group.key}
                  onClick={() => setTab(group.key)}
                  className={`-mb-px flex min-w-0 flex-1 items-center justify-center gap-1.5 border-b-2 px-1.5 py-2 text-[12px] font-semibold transition-colors lg:flex-none lg:px-3 lg:text-[12.5px] ${
                    tab === group.key ? 'border-accent text-white' : 'border-transparent text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  <span className="truncate">{t(`friends.me.showcaseGroups.${group.key}`)}</span>
                  <span className="hidden text-[11px] tabular-nums text-txt-muted lg:inline">{group.items.length}</span>
                  {picked > 0 && <span className="h-1.5 w-1.5 rounded-full bg-accent" />}
                </button>
              )
            })}
          </div>
        </>
      }
    >
      {current.items.length === 0 ? (
        <p className="text-[12px] text-txt-muted">{t('friends.me.noTitles')}</p>
      ) : (
        <div className="grid grid-cols-1 gap-2 lg:grid-cols-2">
          {current.items.map(({ item, label, icon }) => {
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
      {tab === 'ranks' && <p className="mt-3 text-[12px] text-txt-muted">{t('friends.me.showcaseHint')}</p>}
    </LibraryLayout>
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
