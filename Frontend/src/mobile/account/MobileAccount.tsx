import { useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useNavigate } from 'react-router-dom'
import { useStore } from '@/stores/useStore'
import type { Account } from '@/types'
import { SkinBust } from '@/components/home/SkinBust'
import { SkinFace } from '@/components/ui/SkinFace'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { MenuButton } from '@/mobile/ui/NavDrawer'
import { ActionSheet, SHEET_ICONS, type SheetAction } from '@/mobile/ui/ActionSheet'
import { Sheet, SheetTitle } from '@/mobile/ui/Sheet'
import { useT } from '@/i18n'

type Step = 'idle' | 'loading' | 'polling' | 'confirmed' | 'error'

const SKIN_ICON = <path d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M4 6h16v12H4V6z" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />

/**
 * L'écran Compte sur téléphone. Le PC le partage en deux colonnes (compte
 * YuyuFrame à gauche, skin et comptes Minecraft à droite) qui ne tiennent
 * pas dans 375 px ; ici tout s'empile, dans l'ordre d'importance :
 *
 * 1. le compte actif en grand — buste du skin (même rendu figé que
 *    l'accueil : dessiné seulement quand il change), pseudo, « Actif » ;
 *    toucher le buste mène à l'écran Skins ;
 * 2. les comptes Minecraft, une ligne de 68 px chacun. Toucher une ligne
 *    rend ce compte actif sur place — le PC renvoie à l'accueil, ici on
 *    voit la coche passer d'une ligne à l'autre et le buste changer, ce qui
 *    dit mieux ce qui vient de se passer. « … » ouvre la feuille d'actions
 *    (skin, supprimer avec confirmation) ;
 * 3. « Ajouter un compte » : la même fenêtre de choix que le PC ;
 * 4. le compte YuyuFrame, en une ligne qui ouvre son écran.
 *
 * La connexion Microsoft ne réduit pas la fenêtre comme sur PC (il n'y a
 * pas de fenêtre à réduire) : le code s'affiche dans une feuille du bas,
 * qu'on garde ouverte en revenant du navigateur.
 *
 * Toute la logique reste dans `pages/Login.tsx`.
 */
export function MobileAccount(p: {
  accounts: Account[]
  activeUuid: string | null
  skins: Record<string, string>
  step: Step
  userCode: string
  verifyUrl: string
  error: string
  onSelect: (acc: Account) => void
  onRemove: (acc: Account) => void
  onAdd: () => void
  onOpenVerify: () => void
  onCancelLogin: () => void
  onRetry: () => void
}) {
  const t = useT()
  const navigate = useNavigate()
  const yuyuSignedIn = useStore((s) => s.yuyuSignedIn)
  const yuyuUsername = useStore((s) => s.yuyuUsername)
  const yuyuPlan = useStore((s) => s.yuyuPlan)
  const [actionsFor, setActionsFor] = useState<Account | null>(null)
  const [copied, setCopied] = useState(false)

  const active = p.accounts.find((a) => a.uuid === p.activeUuid) ?? null
  const loginOpen = p.step !== 'idle'

  const copyCode = () => {
    navigator.clipboard.writeText(p.userCode).catch(() => {})
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const actions: SheetAction[] = actionsFor ? [
    { id: 'skin', label: t('login.changeSkin'), icon: SKIN_ICON, onSelect: () => navigate(`/skins?account=${actionsFor.uuid}`) },
    {
      id: 'delete',
      label: t('login.disconnect'),
      icon: SHEET_ICONS.delete,
      danger: true,
      confirm: t('login.confirmRemove'),
      onSelect: () => p.onRemove(actionsFor),
    },
  ] : []

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">
      <header className="flex shrink-0 items-center gap-1 pb-1 pl-1.5 pr-4 pt-[max(12px,env(safe-area-inset-top))]">
        <MenuButton />
        <h1 className="min-w-0 flex-1 truncate text-[28px] font-bold tracking-tight text-white">{t('account.title')}</h1>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[max(24px,env(safe-area-inset-bottom))]">
        <div className="flex flex-col gap-6">
          {/* ── Le compte actif ─────────────────────────────────────────── */}
          <section className="relative flex flex-col items-center overflow-hidden rounded-3xl border border-white/[0.06] bg-[radial-gradient(ellipse_at_50%_62%,rgba(75,63,207,0.24)_0%,transparent_72%)] pb-4 pt-2">
            <div className="h-[220px] w-full">
              <SkinBust
                uuid={active?.uuid ?? null}
                localSkin={active ? p.skins[active.uuid] ?? null : null}
                className="h-full w-full"
                onClick={() => active && navigate(`/skins?account=${active.uuid}`)}
                title={t('login.changeSkin')}
              />
            </div>
            <AnimatePresence mode="popLayout" initial={false}>
              <motion.div
                key={active?.uuid ?? 'none'}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6, transition: { duration: 0.12 } }}
                transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
                className="flex flex-col items-center gap-0.5"
              >
                <span className="text-[20px] font-bold text-white">{active?.username ?? t('login.noAccount')}</span>
                {active && <span className="text-[13px] font-semibold text-success">{t('login.active')}</span>}
              </motion.div>
            </AnimatePresence>
          </section>

          {/* ── Comptes Minecraft ───────────────────────────────────────── */}
          <section className="flex flex-col gap-2">
            <h2 className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">{t('login.minecraftAccounts')}</h2>
            <div className="flex flex-col divide-y divide-white/[0.06] overflow-hidden rounded-2xl border border-white/[0.06] bg-white/[0.04]">
              {p.accounts.map((acc) => {
                const isActive = acc.uuid === p.activeUuid
                return (
                  <div key={acc.uuid} className="flex h-[68px] items-center pr-1">
                    <button
                      onClick={() => !isActive && p.onSelect(acc)}
                      aria-pressed={isActive}
                      className="flex h-full min-w-0 flex-1 items-center gap-3 pl-3 text-left active:bg-white/[0.04]"
                    >
                      <span className="relative shrink-0">
                        <SkinFace dataUri={p.skins[acc.uuid] ?? `https://mc-heads.net/skin/${acc.uuid}`} size={44} className="rounded-xl" />
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <span className={`truncate text-[15px] ${isActive ? 'font-bold text-white' : 'font-semibold text-white/90'}`}>{acc.username}</span>
                        <span className={`truncate text-[13px] ${isActive ? 'text-success' : 'text-white/55'}`}>
                          {isActive ? t('login.active') : t('login.savedAccount')}
                        </span>
                      </span>
                      {isActive && (
                        <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-[#a99bff]"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" /></svg>
                      )}
                    </button>
                    <button
                      onClick={() => setActionsFor(acc)}
                      aria-label={t('instancesPage.moreActions')}
                      aria-haspopup="dialog"
                      className="press flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-white/55"
                    >
                      <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5"><circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" /></svg>
                    </button>
                  </div>
                )
              })}
              <button
                onClick={p.onAdd}
                disabled={loginOpen}
                className="flex h-[60px] w-full items-center gap-3 px-3 text-left active:bg-white/[0.04] disabled:opacity-50"
              >
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent/20 text-accent-hover">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" className="h-5 w-5"><path d="M12 5v14M5 12h14" /></svg>
                </span>
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="text-[15px] font-semibold text-[#c4b9ff]">{t('login.addAccount')}</span>
                  <span className="truncate text-[13px] text-white/55">{t('login.connectViaMicrosoft')}</span>
                </span>
              </button>
            </div>
          </section>

          {/* ── Compte YuyuFrame ────────────────────────────────────────── */}
          <section className="flex flex-col gap-2">
            <h2 className="px-1 text-[13px] font-semibold uppercase tracking-[0.06em] text-white/55">YuyuFrame</h2>
            <button
              onClick={() => navigate(yuyuSignedIn ? '/yuyu-account' : '/yuyu?mode=login')}
              className="press flex min-h-[64px] w-full items-center gap-3 rounded-2xl border border-white/[0.06] bg-white/[0.04] px-3 text-left"
            >
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent/25 text-[17px] font-black text-white">
                {yuyuSignedIn ? (yuyuUsername ?? '?')[0].toUpperCase() : 'Y'}
              </span>
              <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="truncate text-[15px] font-semibold text-white">
                  {yuyuSignedIn ? yuyuUsername : t('account.signedOutTitle')}
                </span>
                <span className={`truncate text-[13px] ${yuyuSignedIn && yuyuPlan !== 'free' ? 'font-semibold text-[#c4b5fd]' : 'text-white/55'}`}>
                  {yuyuSignedIn
                    ? `${t('account.yuyuAccount')} · ${yuyuPlan === 'premium' ? 'Premium' : yuyuPlan === 'ultimate' ? 'Ultimate' : t('account.freePlan')}`
                    : t('account.signIn')}
                </span>
              </span>
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5 shrink-0 text-white/35"><path d="M9.3 6.7 10.7 5.3l6.7 6.7-6.7 6.7-1.4-1.4 5.3-5.3z" /></svg>
            </button>
          </section>
        </div>
      </div>

      <ActionSheet
        open={!!actionsFor}
        onClose={() => setActionsFor(null)}
        header={actionsFor && (
          <>
            <SkinFace dataUri={p.skins[actionsFor.uuid] ?? `https://mc-heads.net/skin/${actionsFor.uuid}`} size={36} className="rounded-xl" />
            <span className="min-w-0 flex-1 truncate text-[15px] font-bold text-txt-primary">{actionsFor.username}</span>
          </>
        )}
        actions={actions}
      />

      {/* ── Connexion Microsoft ─────────────────────────────────────────── */}
      {/* Se ferme d'un glissé (ce qui annule), sauf une fois confirmée : la
          suite part toute seule vers l'accueil. */}
      <Sheet
        open={loginOpen}
        onClose={p.onCancelLogin}
        dismissible={p.step !== 'confirmed'}
        label={t('login.addAccount')}
        header={<SheetTitle>Microsoft</SheetTitle>}
        bodyClassName="flex flex-col gap-4 px-5 pb-5"
      >
        {p.step === 'loading' && (
          <div className="flex items-center justify-center gap-3 py-6">
            <ButtonSpinner size={20} trackColor="rgba(255,255,255,0.1)" />
            <span className="text-[15px] text-white/70">{t('login.connecting')}</span>
          </div>
        )}

        {p.step === 'polling' && (
          <>
            <p className="text-[15px] leading-relaxed text-white/70">{t('login.enterCodeOnMicrosoft')}</p>
            <button
              onClick={copyCode}
              className={`press flex flex-col items-center gap-1 rounded-2xl border py-4 ${copied ? 'border-[rgba(74,222,128,0.35)] bg-[rgba(74,222,128,0.08)]' : 'border-white/[0.08] bg-black/40'}`}
            >
              <span className="selectable font-mono text-[30px] font-black tracking-[0.2em] text-white">{p.userCode}</span>
              <span className={`text-[13px] ${copied ? 'text-[rgb(134,239,172)]' : 'text-white/55'}`}>
                {copied ? t('login.copiedExclaim') : t('login.clickToCopy')}
              </span>
            </button>
            <button onClick={p.onOpenVerify} className="press h-12 w-full rounded-2xl bg-[#4B3FCF] text-[16px] font-bold text-white">
              {t('login.openMicrosoft')}
            </button>
            <div className="flex items-center justify-center gap-2.5">
              <ButtonSpinner size={14} trackColor="rgba(255,255,255,0.1)" />
              <span className="text-[13px] text-white/55">{t('login.waitingForConfirmation')}</span>
            </div>
            <button onClick={p.onCancelLogin} className="press h-11 w-full rounded-2xl bg-white/[0.06] text-[15px] font-semibold text-white/75">
              {t('common.cancel')}
            </button>
          </>
        )}

        {p.step === 'confirmed' && (
          <div className="flex flex-col items-center gap-3 py-4 text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-full border border-[rgba(74,222,128,0.35)] bg-[rgba(74,222,128,0.12)]">
              <svg viewBox="0 0 24 24" fill="none" stroke="#4ade80" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" className="h-8 w-8"><path d="M20 6L9 17l-5-5" /></svg>
            </div>
            <p className="text-[17px] font-bold text-white">{t('login.connectedSuccessfully')}</p>
            {active && <p className="text-[15px] text-white/60">{active.username}</p>}
          </div>
        )}

        {p.step === 'error' && (
          <>
            <div className="rounded-2xl border border-[rgba(200,50,50,0.25)] bg-[rgba(200,50,50,0.12)] px-4 py-3">
              <p className="selectable whitespace-pre-wrap break-all text-[14px] text-[rgb(252,165,165)]">{p.error}</p>
            </div>
            <div className="flex gap-2">
              <button onClick={p.onCancelLogin} className="press h-12 flex-1 rounded-2xl bg-white/[0.06] text-[15px] font-semibold text-white/75">
                {t('common.cancel')}
              </button>
              <button onClick={p.onRetry} className="press h-12 flex-1 rounded-2xl bg-[#4B3FCF] text-[15px] font-bold text-white">
                {t('login.retry')}
              </button>
            </div>
          </>
        )}
      </Sheet>
    </div>
  )
}
