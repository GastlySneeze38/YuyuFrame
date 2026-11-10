import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useSupportWatch } from '@/stores/useSupportWatch'
import { useCrashWatch } from '@/stores/useCrashWatch'
import { useDraftState, clearDraft } from '@/stores/useDrafts'
import { SUPPORT_DRAFT_KEY } from '@/lib/supportDraft'
import { CrashPanel } from '@/components/support/CrashPanel'
import { PatchNotesPanel } from '@/components/support/PatchNotesPanel'
import { SNAP, listItemVariants, listVariants } from '@/lib/motion'
import { politeInterval } from '@/lib/netQueue'
import Markdown, { LAUNCHER_THEME } from '@/components/ui/Markdown'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { PushedMoreButton, PushedScreen } from '@/mobile/ui/PushedScreen'
import { ActionSheet, SHEET_ICONS } from '@/mobile/ui/ActionSheet'
import type { SupportCategory, TicketDetail, TicketMessage, TicketStatus, TicketSummary } from '@/types/support'
import { formatDate } from '@/lib/format'
import { SwipeTabs } from '@/mobile/ui/SwipeTabs'

/**
 * Support — tout ce qu'on peut demander à l'équipe.
 *
 * Une question et une demande de remboursement suivent le même chemin : un
 * ticket, une conversation, un salon Discord privé ouvert par le back-office.
 * Mais on ne les écrit pas pareil — une demande formelle a besoin d'une
 * référence de paiement ou d'un consentement, pas d'un champ libre — d'où les
 * quatre entrées en haut de page, qui ouvrent le même formulaire réglé
 * différemment. La catégorie envoyée (`refund`, `plan`, `deletion`) est celle
 * que l'équipe trie dans le back-office.
 *
 * Aucune pièce jointe ici : le salon Discord du ticket sert à ça, et c'est
 * écrit à chaque endroit où l'envie d'en joindre une peut venir.
 */

const DRAFT_KEY = SUPPORT_DRAFT_KEY

type Tab = 'requests' | 'crashes' | 'patchNotes' | 'videos'
const SUPPORT_TABS: Tab[] = ['requests', 'crashes', 'patchNotes', 'videos']
type Kind = 'question' | 'refund' | 'plan' | 'deletion'

interface KindSpec {
  kind: Kind
  /** Catégorie envoyée. `question` laisse choisir. */
  category?: string
  icon: string
  danger?: boolean
}

const KINDS: KindSpec[] = [
  { kind: 'question', icon: 'M9.1 9a3 3 0 015.8 1c0 2-3 3-3 3M12 17h.01' },
  { kind: 'refund', category: 'refund', icon: 'M9 14L4 9l5-5M4 9h11a5 5 0 010 10h-3' },
  { kind: 'plan', category: 'plan', icon: 'M12 3l2.6 5.5 6 .8-4.4 4.2 1.1 6-5.3-2.9-5.3 2.9 1.1-6L3.4 9.3l6-.8L12 3z' },
  { kind: 'deletion', category: 'deletion', danger: true, icon: 'M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3' },
]

/** Catégories laissées au choix quand c'est une simple question. */
const QUESTION_CATEGORIES = ['technical', 'bug', 'account', 'billing', 'other']

const STATUS_STYLES: Record<TicketStatus, string> = {
  open: 'bg-accent/15 text-accent-hover',
  answered: 'bg-success/15 text-success',
  waiting: 'bg-warning/15 text-warning',
  closed: 'bg-surface-3 text-txt-muted',
}

export default function Support() {
  const t = useT()
  const navigate = useNavigate()
  const mobile = useIsMobile()
  const signedIn = useStore((s) => s.yuyuSignedIn)

  // La liste est celle de la veille du support : même source que les
  // pastilles, qui ne peuvent donc pas être en retard sur ce que montre l'écran.
  const tickets = useSupportWatch((s) => s.tickets)
  const setTickets = useSupportWatch((s) => s.setTickets)
  const [openTicket, setOpenTicket] = useState<TicketDetail | null>(null)
  const [categories, setCategories] = useState<SupportCategory[]>([])
  // `?new=question` : un autre écran envoie ici avec une question déjà
  // préparée (l'essai de compatibilité, voir `lib/supportDraft.ts`). Le
  // formulaire s'ouvre tout de suite, sans faire chercher la bonne entrée.
  // Seulement connecté : sans compte, c'est l'invitation à se connecter qui
  // s'affiche, et le brouillon attend.
  const [params] = useSearchParams()
  const [composing, setComposing] = useState<Kind | null>(() =>
    signedIn && params.get('new') === 'question' ? 'question' : null,
  )
  const [loadingDetail, setLoadingDetail] = useState(false)
  // `?tab=crashes` : la modale de plantage y renvoie directement, sinon on
  // arrive sur « Mes demandes » et il faut deviner que le rapport est dans
  // l'onglet d'à côté.
  const [tab, setTab] = useState<Tab>(() => {
    const asked = params.get('tab')
    return asked === 'crashes' || asked === 'patchNotes' || asked === 'videos' ? asked : 'requests'
  })

  useEffect(() => {
    if (!signedIn) return
    // Mêmes catégories que le panneau Discord : chargées tout de suite pour
    // que le formulaire s'ouvre sans attente.
    api.support.categories().then(setCategories).catch(() => setCategories([]))
  }, [signedIn])

  // Arriver sur l'écran relit tout de suite les tickets et les rapports de
  // plantage : liste, compteurs des onglets et pastilles d'un même coup.
  useEffect(() => {
    if (!signedIn) return
    void useSupportWatch.getState().refresh()
    void useCrashWatch.getState().refresh()
  }, [signedIn])

  // `?ticket=…` : la modale « ta demande a bougé » ouvre le fil concerné
  // plutôt que de déposer sur la liste, où il faudrait le retrouver
  // soi-même. Une fois par ticket demandé — rouvrir en boucle empêcherait de
  // revenir à la liste — mais aussi quand on est déjà sur cet écran.
  const requestedTicket = params.get('ticket')
  useEffect(() => {
    if (!requestedTicket) return
    setTab('requests')
    void open(requestedTicket)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestedTicket])

  // `?tab=…` arrivé pendant qu'on est déjà sur cet écran (la modale de
  // plantage y renvoie) : l'onglet suit.
  const requestedTab = params.get('tab')
  useEffect(() => {
    if (requestedTab === 'crashes' || requestedTab === 'patchNotes' || requestedTab === 'videos') setTab(requestedTab)
  }, [requestedTab])

  const labelOf = useMemo(
    () => (code: string) => categories.find((c) => c.code === code)?.label ?? code,
    [categories],
  )

  // La conversation à l'écran est déclarée à la veille du support : une
  // réponse qui y arrive n'ouvre ni modale ni pastille, elle s'affiche ici.
  // Sur PC, elle n'est visible que dans l'onglet « Mes demandes ».
  const openId = mobile || tab === 'requests' ? openTicket?.id ?? null : null
  useEffect(() => {
    useSupportWatch.getState().setViewing(openId)
    return () => useSupportWatch.getState().setViewing(null)
  }, [openId])

  // Elle a bougé côté serveur (flux en direct `support_changed`, ou la
  // relecture ci-dessous) : on la relit sans squelette — les nouveaux
  // messages apparaissent, et la lecture est marquée.
  const viewedChanged = useSupportWatch((s) => s.viewedChanged)
  useEffect(() => {
    if (!viewedChanged || !openId) return
    api.support
      .get(openId)
      .then((detail) => setOpenTicket((current) => (current?.id === detail.id ? detail : current)))
      .catch((e) => console.warn('[support] conversation non relue', e))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewedChanged])

  // Filet du flux en direct, seulement pendant qu'une conversation est
  // ouverte : on attend une réponse, une minute serait trop long.
  useEffect(() => {
    if (!openId) return
    return politeInterval(() => void useSupportWatch.getState().refresh(), 15_000)
  }, [openId])

  async function open(id: string) {
    setLoadingDetail(true)
    try {
      const detail = await api.support.get(id)
      setOpenTicket(detail)
      // La lecture efface la pastille côté serveur : la liste, et avec elle
      // toutes les pastilles, suivent sur-le-champ.
      setTickets((list) => list.map((x) => (x.id === id ? { ...x, has_unread: false } : x)))
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setLoadingDetail(false)
    }
  }

  function absorb(detail: TicketDetail) {
    setOpenTicket(detail)
    const { messages: _messages, ...summary } = detail
    setTickets((list) => [summary, ...list.filter((x) => x.id !== summary.id)])
  }

  async function hide(id: string) {
    try {
      await api.support.hide(id)
      setTickets((list) => list.filter((x) => x.id !== id))
      setOpenTicket(null)
    } catch (e) {
      showError(errorMessage(e))
    }
  }

  // ── Téléphone ──
  // Pas de place pour la liste et la conversation côte à côte : la liste
  // occupe l'écran, une demande s'ouvre en écran poussé. Elle est dans
  // l'adresse (`?ticket=<id>`, déjà lue plus haut) pour que le bouton retour
  // d'Android la referme. Une demande qui vient d'être créée s'ouvre sans
  // adresse : la refermer se fait alors sur place.
  const [, setParams] = useSearchParams()
  const pushedTicket = useRef(false)
  useEffect(() => {
    if (mobile && !requestedTicket) setOpenTicket(null)
  }, [mobile, requestedTicket])
  const openMobile = (id: string) => { pushedTicket.current = true; setParams({ ticket: id }) }
  const closeMobile = () => {
    if (!requestedTicket) setOpenTicket(null)
    else if (pushedTicket.current) { pushedTicket.current = false; navigate(-1) }
    else setParams({}, { replace: true })
  }

  if (mobile) {
    return (
      <div className="relative flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
        <header className="flex shrink-0 items-center gap-1 pb-2 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
          <MenuButton />
          <h1 className="min-w-0 flex-1 truncate text-[28px] font-bold tracking-tight text-white">{t('support.title')}</h1>
        </header>
        <TabBar tab={tab} onPick={setTab} />

        {/* Balayer le contenu change d'onglet (`SwipeTabs`). */}
        <SwipeTabs tabs={SUPPORT_TABS} value={tab} onChange={setTab}>
        <div className="flex min-h-0 flex-1 flex-col">
        {tab === 'videos' ? (
          <VideosSoon />
        ) : tab === 'patchNotes' ? (
          <PatchNotesPanel />
        ) : tab === 'crashes' ? (
          <CrashPanel signedIn={signedIn} />
        ) : !signedIn ? (
          <SignInFirst onSignIn={() => navigate('/yuyu')} />
        ) : (
          <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(24px,env(safe-area-inset-bottom))] pt-3">
            <div className="flex flex-col gap-6">
              <KindBar onPick={setComposing} />
              <section className="flex flex-col gap-2">
                <h2 className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('support.myRequests')}</h2>
                {tickets === null ? (
                  <SkeletonRows count={3} rowClassName="h-16 rounded-2xl" />
                ) : tickets.length === 0 ? (
                  <div className="flex flex-col gap-1 rounded-2xl border border-white/[0.06] bg-white/[0.04] px-4 py-5 text-center">
                    <p className="text-[15px] font-semibold">{t('support.emptyTitle')}</p>
                    <p className="text-[13px] leading-relaxed text-white/55">{t('support.emptyText')}</p>
                  </div>
                ) : (
                  <div className="flex flex-col divide-y divide-white/[0.06] overflow-hidden rounded-2xl border border-white/[0.06] bg-white/[0.04]">
                    {tickets.map((ticket) => (
                      <button
                        key={ticket.id}
                        onClick={() => openMobile(ticket.id)}
                        className="flex min-h-[68px] w-full items-center gap-3 px-4 py-3 text-left active:bg-white/[0.04]"
                      >
                        <span className="flex min-w-0 flex-1 flex-col gap-1">
                          <span className="flex items-center gap-2">
                            <span className="truncate text-[15px] font-semibold text-white">{ticket.subject}</span>
                            {/* Point fixe : pas de pulsation en boucle sur téléphone. */}
                            {ticket.has_unread && <span className="h-2.5 w-2.5 shrink-0 rounded-full bg-danger" />}
                          </span>
                          <span className="flex items-center gap-2">
                            <StatusPill status={ticket.status} />
                            <span className="truncate text-[13px] text-white/55">{ticket.public_id} · {labelOf(ticket.category)}</span>
                          </span>
                        </span>
                        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
                      </button>
                    ))}
                  </div>
                )}
                <p className="px-1 text-[13px] leading-relaxed text-white/55">{t('support.discordNote')}</p>
              </section>
            </div>
          </div>
        )}
        </div>
        </SwipeTabs>

        <MobileConversation
          ticket={openTicket}
          loading={loadingDetail}
          labelOf={labelOf}
          onReplied={absorb}
          onBack={closeMobile}
          onDelete={(id) => { closeMobile(); void hide(id) }}
        />

        <AnimatePresence>
          {composing && (
            <RequestModal
              kind={composing}
              categories={categories}
              onClose={() => setComposing(null)}
              onCreated={(detail) => {
                setComposing(null)
                absorb(detail)
              }}
            />
          )}
        </AnimatePresence>
      </div>
    )
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('support.title')}
          </h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('support.subtitle')}</p>
        </div>
      </PageHeader>

      <TabBar tab={tab} onPick={setTab} />

      {tab === 'videos' ? (
        <VideosSoon />
      ) : tab === 'patchNotes' ? (
        // Sans compte aussi : les notes de version sont publiques, c'est le
        // même contenu que sur le site.
        <PatchNotesPanel />
      ) : tab === 'crashes' ? (
        // Accessible sans compte : un plantage peut très bien arriver avant
        // d'en avoir un, et le rapport est déjà sur le disque. Seul l'envoi
        // demande d'être connecté.
        <CrashPanel signedIn={signedIn} />
      ) : !signedIn ? (
        <SignInFirst onSignIn={() => navigate('/yuyu')} />
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          <KindBar onPick={setComposing} />

          {tickets === null ? (
            <SkeletonRows count={6} className="flex flex-col gap-2" rowClassName="h-16" />
          ) : (
            <div className="grid min-h-0 flex-1 grid-cols-[300px_minmax(0,1fr)]">
              <TicketList
                tickets={tickets}
                openId={openTicket?.id ?? null}
                onOpen={open}
                onDelete={hide}
                labelOf={labelOf}
              />
              <Conversation ticket={openTicket} loading={loadingDetail} labelOf={labelOf} onReplied={absorb} />
            </div>
          )}
        </div>
      )}

      <AnimatePresence>
        {composing && (
          <RequestModal
            kind={composing}
            categories={categories}
            onClose={() => setComposing(null)}
            onCreated={(detail) => {
              setComposing(null)
              absorb(detail)
            }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

// ── Les deux volets ──────────────────────────────────────────────────────────

/**
 * Demandes d'un côté, rapports de plantage de l'autre.
 *
 * Les deux appartiennent au support, mais rien d'autre ne les rapproche : une
 * demande, on l'écrit ; un rapport, on le trouve déjà écrit. Les mélanger dans
 * une même liste rendrait les deux illisibles.
 */
function TabBar({ tab, onPick }: { tab: Tab; onPick: (tab: Tab) => void }) {
  const t = useT()
  // Le point rouge de l'onglet où se trouve la nouvelle : la pastille de
  // l'accueil additionne les deux, ici on dit lequel.
  const unread: Partial<Record<Tab, number>> = {
    requests: useSupportWatch((s) => s.unread),
    crashes: useCrashWatch((s) => s.updates),
  }
  const mobile = useIsMobile()
  // Téléphone : un commutateur à quatre cases égales qui tient dans la
  // largeur — tous les onglets visibles d'un coup, sans rien à faire défiler
  // pour découvrir le dernier. Libellés courts (`tabShort`) pour que chacun
  // tienne sur une ligne ; le nombre de nouveautés se pose en coin.
  if (mobile) {
    return (
      <div className="shrink-0 px-4 pb-3">
        <div className="grid grid-cols-4 gap-1 rounded-2xl bg-white/[0.04] p-1">
          {SUPPORT_TABS.map((key) => (
            <button
              key={key}
              onClick={() => onPick(key)}
              aria-pressed={tab === key}
              aria-label={t(`support.tab.${key}`)}
              className={`relative h-10 min-w-0 rounded-xl px-1 text-[13px] font-semibold transition-colors duration-150 ${
                tab === key ? 'bg-[rgb(var(--accent)/0.35)] text-white' : 'text-white/60'
              }`}
            >
              <span className="block truncate">{t(`support.tabShort.${key}`)}</span>
              {(unread[key] ?? 0) > 0 && (
                <span className="absolute -right-0.5 -top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-1 text-[11px] font-bold tabular-nums text-white">
                  {unread[key]}
                </span>
              )}
            </button>
          ))}
        </div>
      </div>
    )
  }
  return (
    <div className="flex shrink-0 gap-1 border-b border-line-soft px-6">
      {SUPPORT_TABS.map((key) => (
        <button
          key={key}
          onClick={() => onPick(key)}
          className={`relative px-3 py-2.5 text-[12px] font-semibold transition-colors duration-150 ${
            tab === key ? 'text-txt-primary' : 'text-txt-muted hover:text-txt-secondary'
          }`}
        >
          {t(`support.tab.${key}`)}
          {(unread[key] ?? 0) > 0 && (
            <span className="ml-1.5 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-1 align-[1px] text-[10.5px] font-black leading-none tabular-nums text-white">
              {unread[key]}
            </span>
          )}
          {tab === key && (
            <motion.span layoutId="support-tab" transition={SNAP} className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-accent-hover" />
          )}
        </button>
      ))}
    </div>
  )
}

// ── Les quatre entrées ───────────────────────────────────────────────────────

function KindBar({ onPick }: { onPick: (kind: Kind) => void }) {
  const t = useT()
  const mobile = useIsMobile()
  // Téléphone : deux par ligne, titre lisible et phrase courte en dessous.
  if (mobile) {
    return (
      <div className="grid grid-cols-2 gap-2">
        {KINDS.map((k) => (
          <button
            key={k.kind}
            onClick={() => onPick(k.kind)}
            className="press flex min-h-[112px] flex-col items-start gap-2 rounded-2xl border border-white/[0.06] bg-white/[0.04] p-3.5 text-left"
          >
            <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${k.danger ? 'bg-danger/12 text-danger' : 'bg-accent/15 text-accent-hover'}`}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-5 w-5">
                <path d={k.icon} />
              </svg>
            </span>
            <span className="text-[15px] font-semibold leading-tight">{t(`support.kind.${k.kind}Title`)}</span>
            <span className="line-clamp-2 text-[13px] leading-snug text-white/55">{t(`support.kind.${k.kind}Text`)}</span>
          </button>
        ))}
      </div>
    )
  }
  return (
    <motion.div
      variants={listVariants}
      initial="initial"
      animate="animate"
      className="grid shrink-0 grid-cols-4 gap-3 border-b border-line-soft px-6 py-4"
    >
      {KINDS.map((k) => (
        <motion.button
          key={k.kind}
          variants={listItemVariants}
          whileHover={{ y: -3 }}
          whileTap={{ scale: 0.99 }}
          transition={SNAP}
          onClick={() => onPick(k.kind)}
          className={`flex items-start gap-3 rounded-2xl border p-3.5 text-left transition-colors duration-150 ${
            k.danger
              ? 'border-line hover:border-danger/40 hover:bg-danger/8'
              : 'border-line hover:border-accent/40 hover:bg-accent/5'
          }`}
        >
          <span
            className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${
              k.danger ? 'bg-danger/12 text-danger' : 'bg-accent/12 text-accent-hover'
            }`}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-[18px] w-[18px]">
              <path d={k.icon} />
            </svg>
          </span>
          <span className="flex min-w-0 flex-col gap-0.5">
            <span className="text-[13px] font-semibold">{t(`support.kind.${k.kind}Title`)}</span>
            <span className="text-[11px] leading-snug text-txt-muted">{t(`support.kind.${k.kind}Text`)}</span>
          </span>
        </motion.button>
      ))}
    </motion.div>
  )
}

// ── Liste ────────────────────────────────────────────────────────────────────

function TicketList({
  tickets,
  openId,
  onOpen,
  onDelete,
  labelOf,
}: {
  tickets: TicketSummary[]
  openId: string | null
  onOpen: (id: string) => void
  onDelete: (id: string) => void
  labelOf: (code: string) => string
}) {
  const t = useT()
  // Suppression en deux temps, sur la carte elle-même : la corbeille devient
  // rouge, un second clic efface. Un seul ticket à la fois peut être en
  // attente de confirmation.
  const [confirmId, setConfirmId] = useState<string | null>(null)

  return (
    <div className="flex min-h-0 flex-col border-r border-line-soft">
      <p className="px-4 pt-4 pb-2 text-[11px] font-semibold uppercase tracking-wider text-txt-muted">
        {t('support.myRequests')}
      </p>
      {tickets.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-2 px-6 text-center">
          <p className="text-[13px] font-semibold">{t('support.emptyTitle')}</p>
          <p className="text-[11px] leading-relaxed text-txt-secondary">{t('support.emptyText')}</p>
        </div>
      ) : (
        <motion.div
          variants={listVariants}
          initial="initial"
          animate="animate"
          className="flex min-h-0 flex-col gap-1 overflow-y-auto px-3 pb-3"
        >
          {tickets.map((ticket) => (
            <motion.div
              key={ticket.id}
              variants={listItemVariants}
              layout
              onClick={() => onOpen(ticket.id)}
              onMouseLeave={() => setConfirmId((id) => (id === ticket.id ? null : id))}
              className={`group relative flex cursor-pointer flex-col gap-1.5 rounded-xl border px-3 py-2.5 text-left transition-colors duration-150 ${
                ticket.id === openId
                  ? 'border-accent/40 bg-accent/10'
                  : 'border-transparent hover:border-line hover:bg-surface-1'
              }`}
            >
              <div className="flex items-center gap-2">
                <span className="truncate text-[13px] font-semibold">{ticket.subject}</span>
                {ticket.has_unread && (
                  // Pastille : l'équipe a répondu depuis la dernière ouverture.
                  <motion.span
                    className="h-2 w-2 shrink-0 rounded-full bg-danger"
                    animate={{ opacity: [1, 0.35, 1] }}
                    transition={{ duration: 1.8, repeat: Infinity }}
                  />
                )}
              </div>
              <div className="flex items-center gap-2">
                <StatusPill status={ticket.status} />
                <span className="truncate text-[10px] text-txt-muted">
                  {ticket.public_id} · {labelOf(ticket.category)}
                </span>
              </div>

              {/* Seulement sur une demande close : effacer une conversation en
                  cours la rendrait introuvable alors que l'équipe y écrit. */}
              {ticket.status === 'closed' && (
                <motion.button
                  onClick={(e) => {
                    e.stopPropagation()
                    if (confirmId === ticket.id) onDelete(ticket.id)
                    else setConfirmId(ticket.id)
                  }}
                  title={confirmId === ticket.id ? t('support.confirmRemove') : t('support.remove')}
                  whileHover={{ scale: 1.1 }}
                  whileTap={{ scale: 0.92 }}
                  transition={SNAP}
                  animate={confirmId === ticket.id ? { opacity: 1 } : {}}
                  className={`absolute right-2 top-2 flex h-7 w-7 items-center justify-center rounded-lg transition-colors duration-150 ${
                    confirmId === ticket.id
                      ? 'bg-danger/20 text-danger opacity-100'
                      : 'text-txt-muted opacity-0 hover:bg-surface-3 hover:text-danger group-hover:opacity-100'
                  }`}
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-[15px] w-[15px]">
                    <path d="M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3" />
                  </svg>
                </motion.button>
              )}
            </motion.div>
          ))}
        </motion.div>
      )}
    </div>
  )
}

function StatusPill({ status }: { status: TicketStatus }) {
  const t = useT()
  return (
    <span className={`shrink-0 rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider max-sm:text-[11px] max-sm:tracking-wide ${STATUS_STYLES[status] ?? STATUS_STYLES.open}`}>
      {t(`support.status.${status}`)}
    </span>
  )
}

// ── Conversation ─────────────────────────────────────────────────────────────

/** Le corps d'un message rendu par le moteur des notes de version
 *  (`components/ui/Markdown.tsx`, le Markdown de Discord, où l'équipe répond
 *  aussi) : gras, listes, citations, code, et liens — nus ou [texte](…) —
 *  ouverts dans le navigateur par l'écouteur commun (`installExternalLinks`).
 *  Le texte se sélectionne (`selectable`), le reste de l'appli ne l'étant pas. */
const MESSAGE_THEME = {
  ...LAUNCHER_THEME,
  p: 'text-[13px] text-txt-primary leading-relaxed max-sm:text-[15px]',
  ul: 'list-disc pl-5 text-[13px] text-txt-primary flex flex-col gap-1 max-sm:text-[15px]',
  quote: 'border-l-2 border-accent/50 pl-3 text-[13px] text-txt-secondary max-sm:text-[15px]',
  link: 'text-[rgb(var(--accent-text))] underline decoration-[rgb(var(--accent-text)/0.4)] underline-offset-2 hover:decoration-[rgb(var(--accent-text))]',
}

function MessageBody({ body }: { body: string }) {
  return (
    <div className="selectable min-w-0 cursor-text [overflow-wrap:anywhere]">
      <Markdown text={body} theme={MESSAGE_THEME} />
    </div>
  )
}

/**
 * Un message d'une bulle. Les miens écrits dans le launcher (`editable`) se
 * modifient sur place : crayon au survol sur PC, toujours là (discret) au
 * doigt — un appui long y sélectionne déjà le texte. Échap annule,
 * Ctrl+Entrée enregistre. Le bot refait ensuite sa copie dans le salon
 * Discord du ticket.
 */
function MessageItem({ ticketId, message, onSaved }: { ticketId: string; message: TicketMessage; onSaved: (detail: TicketDetail) => void }) {
  const t = useT()
  const [draft, setDraft] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const field = useRef<HTMLTextAreaElement>(null)

  // Le champ prend la hauteur du texte, curseur à la fin.
  useEffect(() => {
    const el = field.current
    if (draft === null || !el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 240)}px`
  }, [draft])
  useEffect(() => {
    const el = field.current
    if (!el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  }, [draft === null])

  async function save() {
    const body = draft?.trim()
    if (!body) return
    if (body === message.body) { setDraft(null); return }
    setSaving(true)
    try {
      onSaved(await api.support.editMessage(ticketId, message.id, body))
      setDraft(null)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setSaving(false)
    }
  }

  if (draft !== null) {
    return (
      <div className="flex flex-col gap-2">
        <textarea
          ref={field}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') { e.preventDefault(); setDraft(null) }
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); void save() }
          }}
          maxLength={4000}
          rows={1}
          className="w-full min-w-[240px] resize-none rounded-lg border border-line bg-bg-primary/60 px-2.5 py-2 text-[13px] leading-relaxed text-txt-primary outline-none focus:border-[rgb(var(--accent)/0.6)] max-sm:min-w-0 max-sm:text-[16px]"
        />
        <div className="flex justify-end gap-1.5">
          <button
            onClick={() => setDraft(null)}
            className="press rounded-lg px-2.5 py-1 text-[11.5px] font-semibold text-txt-secondary hover:bg-white/[0.06] max-sm:min-h-[36px] max-sm:text-[13px]"
          >
            {t('support.editCancel')}
          </button>
          <button
            onClick={() => void save()}
            disabled={saving || !draft.trim()}
            className="press flex items-center gap-1.5 rounded-lg bg-[rgb(var(--accent))] px-2.5 py-1 text-[11.5px] font-semibold text-white hover:bg-[rgb(var(--accent-hover))] disabled:opacity-50 max-sm:min-h-[36px] max-sm:text-[13px]"
          >
            {saving && <ButtonSpinner size={11} trackColor="rgba(255,255,255,0.25)" />}
            {t('support.editSave')}
          </button>
        </div>
      </div>
    )
  }

  if (!message.editable) return <MessageBody body={message.body} />
  return (
    <div className="group/msg relative pr-6">
      <MessageBody body={message.body} />
      <button
        onClick={() => setDraft(message.body)}
        title={t('support.edit')}
        aria-label={t('support.edit')}
        className="absolute -right-1.5 -top-1 flex h-7 w-7 items-center justify-center rounded-md text-txt-muted opacity-0 transition-opacity duration-150 hover:bg-white/[0.06] hover:text-txt-primary focus-visible:opacity-100 group-hover/msg:opacity-100 max-sm:h-9 max-sm:w-9 max-sm:-top-2 max-sm:opacity-60"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5 max-sm:h-4 max-sm:w-4">
          <path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4 12.5-12.5z" />
        </svg>
      </button>
    </div>
  )
}

/** Des messages qui se suivent, du même auteur, écrits dans la même minute :
 *  une seule bulle, une seule heure — sinon trois lignes envoyées d'affilée
 *  font trois bulles et trois fois la même heure. */
interface MessageGroup {
  key: string
  author_kind: TicketMessage['author_kind']
  author_name: string
  created_at: string
  messages: TicketMessage[]
}

function groupMessages(messages: TicketMessage[]): MessageGroup[] {
  const groups: MessageGroup[] = []
  for (const m of messages) {
    const last = groups[groups.length - 1]
    const minute = Math.floor(new Date(m.created_at).getTime() / 60_000)
    if (
      last &&
      last.author_kind === m.author_kind &&
      last.author_name === m.author_name &&
      Math.floor(new Date(last.created_at).getTime() / 60_000) === minute
    ) {
      last.messages.push(m)
    } else {
      groups.push({ key: m.id, author_kind: m.author_kind, author_name: m.author_name, created_at: m.created_at, messages: [m] })
    }
  }
  return groups
}

/** La dernière modification d'un message de la bulle, s'il y en a une. */
function lastEdit(g: MessageGroup): string | null {
  let last: string | null = null
  for (const m of g.messages) if (m.edited_at && (!last || m.edited_at > last)) last = m.edited_at
  return last
}

/** L'heure sous une bulle, du côté de son auteur. Aujourd'hui : l'heure
 *  seule ; un autre jour : la date devant (l'année seulement si elle diffère). */
function MessageTime({ at, mine, edited }: { at: string; mine: boolean; edited: string | null }) {
  const t = useT()
  const language = useStore((s) => s.language)
  const locale = language === 'sga' ? 'fr' : language
  const date = new Date(at)
  const now = new Date()
  const time = formatDate(date, locale, { hour: '2-digit', minute: '2-digit' })
  const sameDay = date.toDateString() === now.toDateString()
  const day = sameDay
    ? null
    : formatDate(date, locale, date.getFullYear() === now.getFullYear() ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' })
  return (
    <time
      dateTime={at}
      title={
        formatDate(date, locale, { dateStyle: 'full', timeStyle: 'short' }) +
        (edited ? `\n${t('support.edited')} : ${formatDate(new Date(edited), locale, { dateStyle: 'medium', timeStyle: 'short' })}` : '')
      }
      className={`px-1.5 text-[10.5px] tabular-nums text-txt-muted max-sm:text-[12px] max-sm:text-white/40 ${mine ? 'self-end' : 'self-start'}`}
    >
      {day ? `${day} · ${time}` : time}
      {edited && ` · ${t('support.edited')}`}
    </time>
  )
}

function Conversation({
  ticket,
  loading,
  labelOf,
  onReplied,
}: {
  ticket: TicketDetail | null
  loading: boolean
  labelOf: (code: string) => string
  onReplied: (detail: TicketDetail) => void
}) {
  const t = useT()
  const [message, setMessage] = useState('')
  const [sending, setSending] = useState(false)

  // Chaque ticket a son champ de réponse : changer de conversation ne
  // transporte pas le brouillon de la précédente.
  useEffect(() => setMessage(''), [ticket?.id])

  if (loading) {
    // Silhouette d'une conversation : en-tête puis messages alternés.
    return (
      <div aria-busy className="flex flex-col gap-3 p-4">
        <Skeleton className="h-8 w-1/2 rounded-xl" />
        <Skeleton className="h-16 w-3/4 rounded-2xl" />
        <Skeleton className="ml-auto h-12 w-2/3 rounded-2xl" />
        <Skeleton className="h-20 w-3/4 rounded-2xl" />
      </div>
    )
  }

  if (!ticket) {
    return (
      <div className="flex flex-col items-center justify-center gap-2 px-8 text-center">
        <p className="text-[13px] text-txt-secondary">{t('support.pickTicket')}</p>
        <p className="max-w-sm text-[11px] leading-relaxed text-txt-muted">{t('support.discordNote')}</p>
      </div>
    )
  }

  async function send() {
    const body = message.trim()
    if (!body || !ticket) return
    setSending(true)
    try {
      onReplied(await api.support.reply(ticket.id, body))
      setMessage('')
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="flex min-h-0 flex-col">
      <div className="flex items-center gap-3 border-b border-line-soft px-6 py-3">
        <div className="min-w-0">
          <p className="truncate text-[14px] font-semibold">{ticket.subject}</p>
          <p className="text-[10px] text-txt-muted">
            {ticket.public_id} · {labelOf(ticket.category)}
          </p>
        </div>
        <div className="ml-auto">
          <StatusPill status={ticket.status} />
        </div>
      </div>

      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-6 py-5">
        {groupMessages(ticket.messages).map((g, i) => (
          <motion.div
            key={g.key}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: Math.min(i * 0.03, 0.2) }}
            className={`flex max-w-[80%] flex-col gap-1.5 ${g.author_kind === 'staff' ? 'self-start' : 'self-end'}`}
          >
            <div
              className={`flex flex-col gap-2 rounded-2xl border px-4 py-3 ${
                g.author_kind === 'staff' ? 'border-accent/25 bg-accent/8' : 'border-line bg-surface-1'
              }`}
            >
              <p className="-mb-1 text-[10px] font-semibold uppercase tracking-wider text-txt-muted">
                {g.author_kind === 'staff' ? t('support.team') : g.author_name}
              </p>
              {g.messages.map((m) => <MessageItem key={m.id} ticketId={ticket.id} message={m} onSaved={onReplied} />)}
            </div>
            <MessageTime at={g.created_at} mine={g.author_kind !== 'staff'} edited={lastEdit(g)} />
          </motion.div>
        ))}
      </div>

      <div className="border-t border-line-soft px-6 py-4">
        <div className="flex items-end gap-2">
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder={ticket.status === 'closed' ? t('support.reopenPlaceholder') : t('support.replyPlaceholder')}
            maxLength={4000}
            rows={2}
            className="flex-1 resize-none rounded-xl border border-line bg-surface-2 px-3 py-2 text-[13px] text-txt-primary outline-none transition-colors duration-150 placeholder:text-txt-muted focus:border-accent/60"
          />
          <Button variant="primary" onClick={send} loading={sending} disabled={!message.trim()}>
            {t('support.send')}
          </Button>
        </div>
        <p className="mt-2 text-[10px] text-txt-muted">{t('support.attachmentsNote')}</p>
      </div>
    </div>
  )
}

/**
 * Une demande, sur téléphone : écran poussé, messages en bulles (l'équipe à
 * gauche, soi à droite), champ de réponse collé en bas avec un bouton
 * d'envoi rond de 44 px. Supprimer une demande close passe par « … » et se
 * confirme dans la feuille — pas de corbeille qui apparaît au survol.
 */
function MobileConversation({ ticket, loading, labelOf, onReplied, onBack, onDelete }: {
  ticket: TicketDetail | null
  loading: boolean
  labelOf: (code: string) => string
  onReplied: (detail: TicketDetail) => void
  onBack: () => void
  onDelete: (id: string) => void
}) {
  const t = useT()
  const [message, setMessage] = useState('')
  const [sending, setSending] = useState(false)
  const [menu, setMenu] = useState(false)
  // Garde la dernière demande le temps que l'écran reparte vers la droite.
  const last = useRef<TicketDetail | null>(null)
  if (ticket) last.current = ticket
  const shown = ticket ?? last.current

  useEffect(() => setMessage(''), [ticket?.id])

  async function send() {
    const body = message.trim()
    if (!body || !ticket) return
    setSending(true)
    try {
      onReplied(await api.support.reply(ticket.id, body))
      setMessage('')
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <>
      <PushedScreen
        open={!!ticket || loading}
        onBack={onBack}
        backLabel={t('support.myRequests')}
        title={shown?.subject ?? '…'}
        subtitle={shown ? `${shown.public_id} · ${labelOf(shown.category)}` : undefined}
        right={shown?.status === 'closed' ? <PushedMoreButton label={t('support.remove')} onClick={() => setMenu(true)} /> : undefined}
        bodyClassName="px-4 py-4"
        footer={shown && (
          <div className="shrink-0 border-t border-white/[0.06] bg-[rgb(var(--bg-primary))] px-3 pb-[max(10px,env(safe-area-inset-bottom))] pt-2.5">
            <div className="flex items-end gap-2">
              <textarea
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                placeholder={shown.status === 'closed' ? t('support.reopenPlaceholder') : t('support.replyPlaceholder')}
                maxLength={4000}
                rows={1}
                // La hauteur suit le texte jusqu'à cinq lignes, comme un
                // champ de messagerie.
                onInput={(e) => {
                  const el = e.currentTarget
                  el.style.height = 'auto'
                  el.style.height = `${Math.min(el.scrollHeight, 132)}px`
                }}
                className="max-h-[132px] min-h-[44px] flex-1 resize-none rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-2.5 text-[16px] leading-snug text-white outline-none placeholder:text-white/40 focus:border-[rgb(var(--accent-hover)/0.6)]"
              />
              <button
                onClick={send}
                disabled={!message.trim() || sending}
                aria-label={t('support.send')}
                className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[rgb(var(--accent))] text-white disabled:opacity-40"
              >
                {sending
                  ? <ButtonSpinner size={16} trackColor="rgba(255,255,255,0.2)" />
                  : <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><path d="M3.4 20.4l17.45-7.48a1 1 0 000-1.84L3.4 3.6a1 1 0 00-1.39 1.17L4.2 12l-2.19 7.23a1 1 0 001.39 1.17z" /></svg>}
              </button>
            </div>
          </div>
        )}
      >
        {loading && !ticket ? (
          // Silhouette d'une conversation : messages alternés.
          <div aria-busy className="flex flex-col gap-3 py-4">
            <Skeleton className="h-16 w-3/4 rounded-2xl" />
            <Skeleton className="ml-auto h-12 w-2/3 rounded-2xl" />
            <Skeleton className="h-20 w-3/4 rounded-2xl" />
          </div>
        ) : shown && (
          <div className="flex flex-col gap-3">
            <div className="flex justify-center"><StatusPill status={shown.status} /></div>
            {groupMessages(shown.messages).map((g) => (
              <div key={g.key} className={`flex max-w-[85%] flex-col gap-1.5 ${g.author_kind === 'staff' ? 'self-start' : 'self-end'}`}>
                <div
                  className={`flex flex-col gap-2 rounded-2xl border px-4 py-3 ${
                    g.author_kind === 'staff' ? 'rounded-bl-md border-accent/25 bg-accent/10' : 'rounded-br-md border-white/[0.08] bg-white/[0.06]'
                  }`}
                >
                  <p className="-mb-1 text-[12px] font-semibold uppercase tracking-wide text-white/50">
                    {g.author_kind === 'staff' ? t('support.team') : g.author_name}
                  </p>
                  {g.messages.map((m) => <MessageItem key={m.id} ticketId={shown.id} message={m} onSaved={onReplied} />)}
                </div>
                <MessageTime at={g.created_at} mine={g.author_kind !== 'staff'} edited={lastEdit(g)} />
              </div>
            ))}
            <p className="pt-1 text-center text-[13px] leading-relaxed text-white/45">{t('support.attachmentsNote')}</p>
          </div>
        )}
      </PushedScreen>
      <ActionSheet
        open={menu}
        onClose={() => setMenu(false)}
        header={<span className="min-w-0 flex-1 truncate text-[15px] font-bold text-txt-primary">{shown?.subject}</span>}
        actions={shown ? [{
          id: 'delete',
          label: t('support.remove'),
          icon: SHEET_ICONS.delete,
          danger: true,
          confirm: t('support.confirmRemoveQuestion'),
          onSelect: () => onDelete(shown.id),
        }] : []}
      />
    </>
  )
}

// ── Formulaire commun aux quatre entrées ─────────────────────────────────────

function RequestModal({
  kind,
  categories,
  onClose,
  onCreated,
}: {
  kind: Kind
  categories: SupportCategory[]
  onClose: () => void
  onCreated: (detail: TicketDetail) => void
}) {
  const t = useT()
  const spec = KINDS.find((k) => k.kind === kind)!
  const selectedInstanceId = useStore((s) => s.selectedInstanceId)
  const plan = useStore((s) => s.yuyuPlan)

  const [category, setCategory] = useDraftState(DRAFT_KEY, 'category', 'technical')
  const [subject, setSubject] = useDraftState(DRAFT_KEY, `${kind}.subject`, '')
  const [message, setMessage] = useDraftState(DRAFT_KEY, `${kind}.message`, '')
  const [reference, setReference] = useDraftState(DRAFT_KEY, `${kind}.reference`, '')
  const [attach, setAttach] = useDraftState(DRAFT_KEY, 'attach', true)
  const [agreed, setAgreed] = useState(false)
  const [diagnostic, setDiagnostic] = useState<string | null>(null)
  const [showReport, setShowReport] = useState(false)
  const [sending, setSending] = useState(false)

  const isQuestion = kind === 'question'
  const needsReference = kind === 'refund' || kind === 'plan'
  const isDeletion = kind === 'deletion'
  // Un rapport technique n'a aucun intérêt sur une demande de remboursement.
  const offersDiagnostic = isQuestion

  useEffect(() => {
    if (!offersDiagnostic) return
    api.support.diagnostic(selectedInstanceId).then(setDiagnostic).catch(() => setDiagnostic(null))
  }, [offersDiagnostic, selectedInstanceId])

  const ready =
    message.trim().length > 0 &&
    (isQuestion ? subject.trim().length >= 3 : true) &&
    (needsReference ? reference.trim().length > 0 : true) &&
    (isDeletion ? agreed : true)

  async function submit() {
    setSending(true)
    try {
      // Les demandes formelles partent avec un en-tête fixe : l'équipe reçoit
      // toujours les mêmes informations au même endroit, quel que soit ce que
      // la personne a écrit en dessous.
      const header = needsReference
        ? `${t(`support.kind.${kind}Title`)}\n${t('support.referenceLabel')} : ${reference.trim()}\n${t('support.currentPlan')} : ${plan}\n\n`
        : isDeletion
          ? `${t('support.deletionHeader')}\n\n`
          : ''
      const detail = await api.support.create({
        category: spec.category ?? category,
        subject: isQuestion ? subject.trim() : t(`support.kind.${kind}Subject`),
        message: header + message.trim(),
        diagnostic: offersDiagnostic && attach ? diagnostic : null,
      })
      clearDraft(DRAFT_KEY)
      onCreated(detail)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <ModalShell title={t(`support.kind.${kind}Title`)} onClose={onClose} maxWidth="max-w-lg">
      {/* Sur téléphone la feuille défile déjà : pas de seconde zone de
          défilement dans la première. */}
      <div className="flex max-h-[62vh] flex-col gap-4 overflow-y-auto pr-1 max-sm:max-h-none max-sm:overflow-visible max-sm:pr-0">
        <p className="text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t(`support.kind.${kind}Intro`)}</p>

        {isDeletion && (
          <div className="rounded-xl border border-danger/35 bg-danger/8 p-3">
            <p className="text-[12px] font-semibold text-danger max-sm:text-[15px]">{t('support.deletionWarnTitle')}</p>
            <p className="mt-1 text-[11px] leading-relaxed text-txt-secondary max-sm:text-[14px]">{t('support.deletionWarnText')}</p>
          </div>
        )}

        {isQuestion && (
          <>
            <div className="flex flex-col gap-1.5">
              <span className="text-[12px] font-medium text-txt-secondary max-sm:text-[13px]">{t('support.category')}</span>
              <div className="grid grid-cols-2 gap-2">
                {categories
                  .filter((c) => QUESTION_CATEGORIES.includes(c.code))
                  .map((c) => (
                    <motion.button
                      key={c.code}
                      whileTap={{ scale: 0.98 }}
                      onClick={() => setCategory(c.code)}
                      title={c.description}
                      className={`rounded-xl border px-3 py-2 text-left text-[12px] transition-colors duration-150 max-sm:min-h-[44px] max-sm:text-[15px] ${
                        category === c.code
                          ? 'border-accent/50 bg-accent/12 text-txt-primary'
                          : 'border-line bg-surface-2 text-txt-secondary hover:bg-surface-3'
                      }`}
                    >
                      {c.label}
                    </motion.button>
                  ))}
              </div>
            </div>

            <Field
              label={t('support.subject')}
              value={subject}
              maxLength={120}
              onChange={(e) => setSubject(e.target.value)}
              placeholder={t('support.subjectPlaceholder')}
            />
          </>
        )}

        {needsReference && (
          <Field
            label={t('support.referenceLabel')}
            value={reference}
            maxLength={120}
            onChange={(e) => setReference(e.target.value)}
            placeholder={t('support.referencePlaceholder')}
            hint={t('support.referenceHint')}
          />
        )}

        <label className="flex flex-col gap-1.5">
          <span className="text-[12px] font-medium text-txt-secondary max-sm:text-[13px]">{t('support.message')}</span>
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={5}
            maxLength={4000}
            placeholder={t(`support.kind.${kind}Placeholder`)}
            className="resize-none rounded-xl border border-line bg-surface-2 px-3 py-2 text-[13px] text-txt-primary outline-none transition-colors duration-150 placeholder:text-txt-muted focus:border-accent/60 max-sm:px-4 max-sm:py-3 max-sm:text-[16px]"
          />
        </label>

        {offersDiagnostic && (
          <div className="rounded-xl border border-line bg-surface-1 p-3">
            <label className="flex cursor-pointer items-start gap-2.5">
              <input
                type="checkbox"
                checked={attach}
                onChange={(e) => setAttach(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-[rgb(var(--accent-text))]"
              />
              <span className="flex flex-col gap-0.5">
                <span className="text-[12px] font-medium max-sm:text-[15px]">{t('support.attachDiagnostic')}</span>
                <span className="text-[11px] leading-relaxed text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{t('support.diagnosticNote')}</span>
              </span>
            </label>
            {attach && diagnostic && (
              <>
                <button
                  onClick={() => setShowReport((v) => !v)}
                  className="mt-2 text-[11px] font-semibold text-accent-hover hover:underline max-sm:min-h-[44px] max-sm:text-[15px]"
                >
                  {showReport ? t('support.hideReport') : t('support.showReport')}
                </button>
                <AnimatePresence initial={false}>
                  {showReport && (
                    <motion.pre
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap rounded-lg bg-surface-2 p-2.5 text-[10px] leading-relaxed text-txt-secondary"
                    >
                      {diagnostic}
                    </motion.pre>
                  )}
                </AnimatePresence>
              </>
            )}
          </div>
        )}

        {isDeletion && (
          <label className="flex cursor-pointer items-start gap-2.5">
            <input
              type="checkbox"
              checked={agreed}
              onChange={(e) => setAgreed(e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-[#ef4444]"
            />
            <span className="text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('support.deletionConsent')}</span>
          </label>
        )}

        <p className="text-[11px] leading-relaxed text-txt-muted max-sm:text-[13px] max-sm:text-white/55">{t('support.attachmentsNote')}</p>
      </div>

      <div className="flex justify-end gap-2 max-sm:[&>*]:h-12 max-sm:[&>*]:flex-1 max-sm:[&>*]:text-[15px]">
        <Button variant="ghost" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant={isDeletion ? 'danger' : 'primary'} onClick={submit} loading={sending} disabled={!ready}>
          {t(`support.kind.${kind}Submit`)}
        </Button>
      </div>
    </ModalShell>
  )
}

// ── Vidéos ───────────────────────────────────────────────────────────────────

/** Tutoriels vidéo : l'onglet existe déjà, son contenu arrive plus tard. */
function VideosSoon() {
  const t = useT()
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
      className="flex flex-1 flex-col items-center justify-center gap-3 px-7 text-center"
    >
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/12 text-accent-hover">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="h-7 w-7">
          <rect x="2.5" y="5" width="19" height="14" rx="3" />
          <path d="M10 9.5v5l4.5-2.5z" />
        </svg>
      </div>
      <span className="rounded-full bg-accent/15 px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider text-accent-hover max-sm:text-[12px]">
        {t('support.videos.badge')}
      </span>
      <p className="text-[15px] font-semibold max-sm:text-[17px]">{t('support.videos.title')}</p>
      <p className="max-w-sm text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('support.videos.text')}</p>
    </motion.div>
  )
}

// ── Compte requis ────────────────────────────────────────────────────────────

function SignInFirst({ onSignIn }: { onSignIn: () => void }) {
  const t = useT()
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
      className="flex flex-1 flex-col items-center justify-center gap-3 px-7 text-center"
    >
      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-accent/12 text-accent-hover">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className="h-7 w-7">
          <path d="M12 2a9 9 0 00-9 9v5a3 3 0 003 3h1a1 1 0 001-1v-5a1 1 0 00-1-1H5v-1a7 7 0 1114 0v1h-2a1 1 0 00-1 1v5a1 1 0 001 1h1a3 3 0 003-3v-5a9 9 0 00-9-9z" />
        </svg>
      </div>
      <p className="text-[15px] font-semibold max-sm:text-[17px]">{t('support.signInTitle')}</p>
      <p className="max-w-sm text-[12px] leading-relaxed text-txt-secondary max-sm:text-[15px]">{t('support.signInText')}</p>
      <Button variant="primary" onClick={onSignIn}>
        {t('support.signIn')}
      </Button>
    </motion.div>
  )
}
