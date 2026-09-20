import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { Field } from '@/components/ui/Field'
import { ModalShell } from '@/components/ui/ModalShell'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { api } from '@/api/client'
import { errorMessage } from '@/lib/apiError'
import { showError } from '@/stores/useErrorToast'
import { useStore } from '@/stores/useStore'
import { useDraftState, clearDraft } from '@/stores/useDrafts'
import { listItemVariants, listVariants } from '@/lib/motion'
import { useT } from '@/i18n'
import type { SupportCategory, TicketDetail, TicketStatus, TicketSummary } from '@/types/support'

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

const DRAFT_KEY = 'support-ticket'

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
  const signedIn = useStore((s) => s.yuyuSignedIn)

  const [tickets, setTickets] = useState<TicketSummary[] | null>(null)
  const [openTicket, setOpenTicket] = useState<TicketDetail | null>(null)
  const [categories, setCategories] = useState<SupportCategory[]>([])
  const [composing, setComposing] = useState<Kind | null>(null)
  const [loadingDetail, setLoadingDetail] = useState(false)

  useEffect(() => {
    if (!signedIn) return
    api.support.list().then(setTickets).catch((e) => {
      setTickets([])
      showError(errorMessage(e))
    })
    // Mêmes catégories que le panneau Discord : chargées tout de suite pour
    // que le formulaire s'ouvre sans attente.
    api.support.categories().then(setCategories).catch(() => setCategories([]))
  }, [signedIn])

  const labelOf = useMemo(
    () => (code: string) => categories.find((c) => c.code === code)?.label ?? code,
    [categories],
  )

  async function open(id: string) {
    setLoadingDetail(true)
    try {
      const detail = await api.support.get(id)
      setOpenTicket(detail)
      // La lecture efface la pastille côté serveur : la liste doit suivre.
      setTickets((list) => list?.map((x) => (x.id === id ? { ...x, has_unread: false } : x)) ?? null)
    } catch (e) {
      showError(errorMessage(e))
    } finally {
      setLoadingDetail(false)
    }
  }

  function absorb(detail: TicketDetail) {
    setOpenTicket(detail)
    const { messages: _messages, ...summary } = detail
    setTickets((list) => [summary, ...(list ?? []).filter((x) => x.id !== summary.id)])
  }

  async function hide(id: string) {
    try {
      await api.support.hide(id)
      setTickets((list) => list?.filter((x) => x.id !== id) ?? null)
      setOpenTicket(null)
    } catch (e) {
      showError(errorMessage(e))
    }
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

      {!signedIn ? (
        <SignInFirst onSignIn={() => navigate('/yuyu')} />
      ) : (
        <div className="flex min-h-0 flex-1 flex-col">
          <KindBar onPick={setComposing} />

          {tickets === null ? (
            <div className="flex flex-1 items-center justify-center">
              <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
            </div>
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

// ── Les quatre entrées ───────────────────────────────────────────────────────

function KindBar({ onPick }: { onPick: (kind: Kind) => void }) {
  const t = useT()
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
          transition={{ type: 'spring', stiffness: 700, damping: 30, mass: 0.5 }}
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
                    className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent-hover"
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
                  transition={{ type: 'spring', stiffness: 700, damping: 28, mass: 0.4 }}
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
    <span className={`rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider ${STATUS_STYLES[status] ?? STATUS_STYLES.open}`}>
      {t(`support.status.${status}`)}
    </span>
  )
}

// ── Conversation ─────────────────────────────────────────────────────────────

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
    return (
      <div className="flex items-center justify-center">
        <ButtonSpinner size={24} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
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
        {ticket.messages.map((m, i) => (
          <motion.div
            key={m.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: Math.min(i * 0.03, 0.2) }}
            className={`max-w-[80%] rounded-2xl border px-4 py-3 ${
              m.author_kind === 'staff'
                ? 'border-accent/25 bg-accent/8'
                : 'self-end border-line bg-surface-1'
            }`}
          >
            <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-txt-muted">
              {m.author_kind === 'staff' ? t('support.team') : m.author_name}
            </p>
            <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-txt-primary">{m.body}</p>
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
      <div className="flex max-h-[62vh] flex-col gap-4 overflow-y-auto pr-1">
        <p className="text-[12px] leading-relaxed text-txt-secondary">{t(`support.kind.${kind}Intro`)}</p>

        {isDeletion && (
          <div className="rounded-xl border border-danger/35 bg-danger/8 p-3">
            <p className="text-[12px] font-semibold text-danger">{t('support.deletionWarnTitle')}</p>
            <p className="mt-1 text-[11px] leading-relaxed text-txt-secondary">{t('support.deletionWarnText')}</p>
          </div>
        )}

        {isQuestion && (
          <>
            <div className="flex flex-col gap-1.5">
              <span className="text-[12px] font-medium text-txt-secondary">{t('support.category')}</span>
              <div className="grid grid-cols-2 gap-2">
                {categories
                  .filter((c) => QUESTION_CATEGORIES.includes(c.code))
                  .map((c) => (
                    <motion.button
                      key={c.code}
                      whileTap={{ scale: 0.98 }}
                      onClick={() => setCategory(c.code)}
                      title={c.description}
                      className={`rounded-xl border px-3 py-2 text-left text-[12px] transition-colors duration-150 ${
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
          <span className="text-[12px] font-medium text-txt-secondary">{t('support.message')}</span>
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={5}
            maxLength={4000}
            placeholder={t(`support.kind.${kind}Placeholder`)}
            className="resize-none rounded-xl border border-line bg-surface-2 px-3 py-2 text-[13px] text-txt-primary outline-none transition-colors duration-150 placeholder:text-txt-muted focus:border-accent/60"
          />
        </label>

        {offersDiagnostic && (
          <div className="rounded-xl border border-line bg-surface-1 p-3">
            <label className="flex cursor-pointer items-start gap-2.5">
              <input
                type="checkbox"
                checked={attach}
                onChange={(e) => setAttach(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-[#818cf8]"
              />
              <span className="flex flex-col gap-0.5">
                <span className="text-[12px] font-medium">{t('support.attachDiagnostic')}</span>
                <span className="text-[11px] leading-relaxed text-txt-muted">{t('support.diagnosticNote')}</span>
              </span>
            </label>
            {attach && diagnostic && (
              <>
                <button
                  onClick={() => setShowReport((v) => !v)}
                  className="mt-2 text-[11px] font-semibold text-accent-hover hover:underline"
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
            <span className="text-[12px] leading-relaxed text-txt-secondary">{t('support.deletionConsent')}</span>
          </label>
        )}

        <p className="text-[11px] leading-relaxed text-txt-muted">{t('support.attachmentsNote')}</p>
      </div>

      <div className="flex justify-end gap-2">
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
      <p className="text-[15px] font-semibold">{t('support.signInTitle')}</p>
      <p className="max-w-sm text-[12px] leading-relaxed text-txt-secondary">{t('support.signInText')}</p>
      <Button variant="primary" onClick={onSignIn}>
        {t('support.signIn')}
      </Button>
    </motion.div>
  )
}
