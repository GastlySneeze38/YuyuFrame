import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { open as openFileDialog } from '@tauri-apps/plugin-dialog'
import { AnimatePresence, motion } from 'framer-motion'
import { SkinViewer, WalkingAnimation } from 'skinview3d'
import { api } from '@/api/client'
import type { McAccountInfo, SkinHistoryEntry, SkinKind, SkinRef, SkinVariant } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { Button } from '@/components/ui/Button'
import { ButtonSpinner } from '@/components/ui/ButtonSpinner'
import { ModalShell } from '@/components/ui/ModalShell'
import { SkinFace } from '@/components/ui/SkinFace'
import { showError } from '@/stores/useErrorToast'
import { forgetSkinPreview, rememberSkinPreview, skinPreview } from '@/lib/skinCache'
import { fadeVariants, fastTransition } from '@/lib/motion'
import { useT } from '@/i18n'

/**
 * Skins — l'écran unique, pour les comptes Microsoft comme pour les comptes
 * hors ligne.
 *
 * ── Trois sources, et ce qu'elles deviennent ──────────────────────────────
 * Un skin doit être hébergé quelque part pour que les autres joueurs le
 * voient ; nous n'hébergeons rien (voir `commands/account/skin.rs`). D'où :
 *
 *   joueur   le skin de n'importe quel compte premium — Mojang l'héberge déjà
 *   URL      n'importe quel PNG public — son hébergeur s'en charge
 *   fichier  un PNG du disque. Sur un compte Microsoft il est ENVOYÉ à Mojang,
 *            qui l'héberge : il rejoint donc les deux autres. Sur un compte
 *            hors ligne, il n'y a personne à qui l'envoyer, et il ne vit que
 *            dans la base du launcher — ce que l'écran dit avant d'appliquer.
 *
 * ── L'historique est le nôtre ─────────────────────────────────────────────
 * Mojang ne sert que le skin *courant* d'un profil, jamais les précédents.
 * Impossible donc de reconstituer un passé : on enregistre ce qui est appliqué
 * (`db/skin_history.rs`), pour les deux sortes de compte, et on amorce la liste
 * d'un compte Microsoft avec le skin qu'il porte quand on le découvre.
 *
 * ── Mise en page : trois colonnes, aucun défilement ───────────────────────
 * L'écran tenait sur deux colonnes hautes, et l'historique passait sous la
 * ligne de flottaison — on ne savait qu'il existait qu'en faisant défiler.
 * Désormais aperçu · actions · historique tiennent côte à côte, et seule la
 * liste d'historique défile dans son propre cadre quand elle déborde. Le
 * défilement de la page reste possible pour les toutes petites fenêtres, mais
 * ne sert jamais à la taille normale.
 */

type Tab = 'player' | 'url' | 'file'

/** Skin choisi et vérifié, pas encore appliqué. */
interface Candidate {
  kind: SkinKind
  source: string
  variant: SkinVariant
  dataUri: string
  origin: string
  /** Pseudo du joueur d'où il vient, pour l'annoncer à l'écran. */
  fromPlayer?: string
  /** Vrai quand il sort de l'historique : le libellé d'aperçu le dit. */
  fromHistory?: boolean
}

/** Action engageante en attente de confirmation — voir `ConfirmModal`. */
type Pending =
  | { type: 'apply' }
  | { type: 'default' }
  | { type: 'forget'; entry: SkinHistoryEntry }

/**
 * Apparence par défaut du compte, quand aucun skin n'est choisi.
 *
 * Le bouton « retirer le skin » laissait le personnage sans texture, ce qui
 * n'existe pas en jeu : un compte sans skin porte le skin par défaut que
 * Mojang lui attribue. L'aperçu montre donc toujours quelqu'un — ce service
 * sert déjà d'avatar ailleurs dans le launcher, et rend le bon défaut pour un
 * UUID inconnu, ce qui couvre les comptes hors ligne.
 */
const defaultSkinUrl = (uuid: string) => `https://mc-heads.net/skin/${uuid}`

export default function Skins() {
  const t = useT()
  const [params, setParams] = useSearchParams()
  const [accounts, setAccounts] = useState<McAccountInfo[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('player')

  const [current, setCurrent] = useState<SkinRef | null>(null)
  const [currentUri, setCurrentUri] = useState<string | null>(null)
  const [candidate, setCandidate] = useState<Candidate | null>(null)
  const [history, setHistory] = useState<SkinHistoryEntry[]>([])

  const [playerName, setPlayerName] = useState('')
  const [url, setUrl] = useState('')
  const [searching, setSearching] = useState(false)
  const [busy, setBusy] = useState(false)
  const [justApplied, setJustApplied] = useState(false)
  const [pending, setPending] = useState<Pending | null>(null)
  const [showHosting, setShowHosting] = useState(false)
  /** Aperçu du skin de chaque compte, pour les avatars du sélecteur. */
  const [faces, setFaces] = useState<Record<string, string | null>>({})

  useEffect(() => {
    api.mc.accounts()
      .then((accs) => {
        setAccounts(accs)
        // Compte demandé par l'URL (lien depuis l'écran Comptes), à défaut le
        // compte actif, à défaut le premier.
        const wanted = params.get('account')
        const found = accs.find((a) => a.mc_uuid === wanted) ?? accs.find((a) => a.is_active) ?? accs[0]
        setSelected(found?.mc_uuid ?? null)
      })
      .catch((e) => { setAccounts([]); showError(e) })
  }, [])

  const account = useMemo(
    () => accounts?.find((a) => a.mc_uuid === selected) ?? null,
    [accounts, selected],
  )

  const loadHistory = useCallback((uuid: string) => {
    api.skin.history(uuid).then(setHistory).catch(() => setHistory([]))
  }, [])

  // Avatars du sélecteur. Lecture locale (la référence enregistrée et son cache
  // d'aperçu), donc pas d'appel réseau par compte : un compte Microsoft jamais
  // passé par ici n'en a pas, et retombe sur le service d'avatars.
  useEffect(() => {
    if (!accounts) return
    accounts.forEach((a) => {
      skinPreview(a.mc_uuid)
        .then((uri) => setFaces((f) => ({ ...f, [a.mc_uuid]: uri })))
        .catch(() => {})
    })
  }, [accounts])

  // Skin du compte choisi. Pour un compte Microsoft jamais passé par ici, la
  // référence locale est vide alors que Mojang, lui, sert bien un skin : on le
  // demande à Mojang pour que l'écran montre la vérité plutôt qu'un vide — et
  // cet appel amorce du même coup son historique (voir skin_of_account).
  useEffect(() => {
    if (!account) return
    let cancelled = false
    setCurrent(null)
    setCurrentUri(null)
    setCandidate(null)
    setHistory([])
    setJustApplied(false)

    const uuid = account.mc_uuid
    api.skin.current(uuid)
      .then(async (ref) => {
        if (cancelled) return
        if (ref) {
          setCurrent(ref)
          const uri = await skinPreview(uuid).catch(() => null)
          if (!cancelled) setCurrentUri(uri)
        } else if (!account.is_offline) {
          const mojang = await api.skin.ofAccount(uuid).catch(() => null)
          if (!cancelled && mojang) {
            setCurrent({ kind: 'url', source: mojang.url, variant: mojang.variant, origin: 'mojang' })
            setCurrentUri(mojang.data_uri)
          }
        }
        // Après l'amorçage, jamais avant : sinon la liste arriverait sans le
        // skin que Mojang vient de nous apprendre.
        if (!cancelled) loadHistory(uuid)
      })
      .catch(() => {})

    return () => { cancelled = true }
  }, [account?.mc_uuid, account?.is_offline, loadHistory])

  const shown = candidate ?? (current && currentUri ? { ...current, dataUri: currentUri } : null)

  const pickAccount = (uuid: string) => {
    setSelected(uuid)
    // L'URL suit la sélection : revenir sur l'écran ou le recharger garde le
    // compte sous les yeux.
    setParams(uuid ? { account: uuid } : {}, { replace: true })
  }

  const searchPlayer = async () => {
    if (!playerName.trim() || searching) return
    setSearching(true)
    try {
      const found = await api.skin.resolvePlayer(playerName.trim())
      setCandidate({
        kind: 'url',
        source: found.url,
        variant: found.variant,
        dataUri: found.data_uri,
        origin: `player:${found.username}`,
        fromPlayer: found.username,
      })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  const checkUrl = async () => {
    if (!url.trim() || searching) return
    setSearching(true)
    try {
      const checked = await api.skin.checkUrl(url.trim())
      // Rien dans les pixels ne distingue de façon fiable un skin fin d'un skin
      // classique : on part du modèle le plus courant et on laisse choisir.
      setCandidate({ ...checked, dataUri: checked.data_uri, origin: 'url' })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  const pickFile = async () => {
    if (searching) return
    const picked = await openFileDialog({ filters: [{ name: 'Skin Minecraft', extensions: ['png'] }] })
    if (typeof picked !== 'string') return
    setSearching(true)
    try {
      const imported = await api.skin.importFile(picked)
      setCandidate({ ...imported, dataUri: imported.data_uri, origin: 'file' })
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setSearching(false)
    }
  }

  /**
   * Appliquer sur un compte Microsoft change le skin chez Mojang, donc pour
   * tout le monde : ça se confirme. Sur un compte hors ligne, rien ne sort du
   * launcher et un mauvais choix se corrige d'un clic — demander là aussi
   * userait la confirmation jusqu'à ce qu'on ne la lise plus.
   */
  const askApply = () => {
    if (!account || !candidate) return
    if (account.is_offline) void apply()
    else setPending({ type: 'apply' })
  }

  const apply = async () => {
    if (!account || !candidate || busy) return
    setPending(null)
    setBusy(true)
    try {
      const saved = await api.skin.apply(
        account.mc_uuid,
        candidate.kind,
        candidate.source,
        candidate.variant,
        candidate.origin,
      )
      setCurrent(saved)
      setCurrentUri(candidate.dataUri)
      setFaces((f) => ({ ...f, [account.mc_uuid]: candidate.dataUri }))
      // Les autres écrans liront le nouveau skin sans le redemander au Rust.
      rememberSkinPreview(account.mc_uuid, candidate.dataUri)
      setCandidate(null)
      setJustApplied(true)
      loadHistory(account.mc_uuid)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  /**
   * Revenir à l'apparence par défaut.
   *
   * Côté serveur c'est la même opération qu'avant (la référence est effacée, et
   * Mojang remet l'apparence par défaut du compte), mais ce n'est plus présenté
   * comme « retirer » : un compte sans skin choisi n'est pas nu, il porte le
   * skin par défaut que Mojang lui attribue. C'est ce que l'aperçu montre
   * ensuite, et ce que le bouton annonce.
   */
  const useDefault = async () => {
    if (!account || busy) return
    setPending(null)
    setBusy(true)
    try {
      await api.skin.remove(account.mc_uuid)
      setCurrent(null)
      setCurrentUri(null)
      setFaces((f) => ({ ...f, [account.mc_uuid]: null }))
      // À recalculer plutôt qu'à forcer à `null` : sans référence, un compte
      // Microsoft a quand même un skin chez Mojang — celui par défaut.
      forgetSkinPreview(account.mc_uuid)
      setCandidate(null)
      setJustApplied(false)
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const forget = async (entry: SkinHistoryEntry) => {
    if (!account) return
    setPending(null)
    try {
      await api.skin.historyForget(account.mc_uuid, entry.id)
      loadHistory(account.mc_uuid)
    } catch (e) {
      showError(e)
    }
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em] text-txt-primary">
            {t('skins.title')}
          </h1>
          <p className="mt-0.5 text-[11.5px] text-txt-secondary">{t('skins.subtitle')}</p>
        </div>
      </PageHeader>

      {/* `overflow-y-auto` en secours : à la taille normale rien ne défile,
          mais une fenêtre réduite à l'extrême doit rester utilisable. */}
      <div className="min-h-0 flex-1 overflow-y-auto px-7 py-5">
        {accounts === null ? (
          <div className="flex h-full items-center justify-center">
            <ButtonSpinner size={28} color="#818cf8" trackColor="rgba(255,255,255,0.08)" />
          </div>
        ) : accounts.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
            <p className="text-[14px] font-semibold">{t('skins.noAccount')}</p>
            <p className="max-w-sm text-[12.5px] leading-relaxed text-txt-secondary">{t('skins.noAccountHint')}</p>
          </div>
        ) : (
          <div className="mx-auto flex h-full min-h-[500px] w-full max-w-[1180px] flex-col gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <AccountPicker accounts={accounts} selected={selected} faces={faces} onPick={pickAccount} />
              {/* Remplace les phrases d'aide qui vivaient sous chaque onglet :
                  elles répétaient trois fois la même idée et disaient à chaque
                  fois un tiers de l'histoire. Ici, tout est au même endroit,
                  pour qui se pose la question. */}
              <Button variant="ghost" size="sm" onClick={() => setShowHosting(true)}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 16v-4M12 8h.01" />
                </svg>
                {t('skins.hostingButton')}
              </Button>
            </div>

            {/* Trois colonnes égales, dans l'ordre où on les regarde : ce que
                le compte porte, ce qu'il a porté, et de quoi en changer.
                L'historique est voisin de l'aperçu parce qu'ils parlent de la
                même chose — remettre un ancien skin, c'est comparer deux
                images, pas remplir un formulaire.

                Elles s'installent dès 1024 px et pas à `xl` : ce dernier vaut
                1280, soit exactement la largeur par défaut de la fenêtre — la
                mise en page aurait basculé sur un pixel de redimensionnement. */}
            <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 lg:grid-cols-3">
              {/* L'import ouvre la lecture : on arrive ici pour changer de
                  skin, donc on commence par le choisir. L'aperçu et
                  l'historique — ce qu'on porte et ce qu'on a porté — restent
                  côte à côte à sa droite. */}
              <div className="flex min-h-0 flex-col gap-4">
                {/* La méthode passe par un menu déroulant : trois onglets côte
                    à côte réclamaient toute la largeur pour un choix qu'on fait
                    une fois, et chaque option peut ici porter sa description. */}
                <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface-1 p-4">
                  <SourcePicker value={tab} onChange={setTab} />

                  {tab === 'file' ? (
                    <Button onClick={pickFile} loading={searching} fullWidth>{t('skins.chooseFile')}</Button>
                  ) : (
                    <div className="flex gap-2">
                      <input
                        value={tab === 'player' ? playerName : url}
                        onChange={(e) => (tab === 'player' ? setPlayerName(e.target.value) : setUrl(e.target.value))}
                        onKeyDown={(e) => { if (e.key === 'Enter') tab === 'player' ? searchPlayer() : checkUrl() }}
                        placeholder={tab === 'player' ? t('skins.playerPlaceholder') : 'https://.../skin.png'}
                        maxLength={tab === 'player' ? 16 : undefined}
                        className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-black/40 px-3.5 text-[13.5px] text-txt-primary placeholder:text-txt-muted outline-none transition-colors focus:border-accent/50"
                      />
                      <Button
                        onClick={tab === 'player' ? searchPlayer : checkUrl}
                        loading={searching}
                        disabled={!(tab === 'player' ? playerName.trim() : url.trim())}
                      >
                        {tab === 'player' ? t('skins.search') : t('skins.check')}
                      </Button>
                    </div>
                  )}
                </div>

                <PosePicker />
              </div>

              <SkinPreview
                dataUri={shown?.dataUri ?? null}
                fallbackUrl={account ? defaultSkinUrl(account.mc_uuid) : null}
                variant={shown?.variant ?? 'classic'}
                label={
                  candidate
                    ? candidate.fromHistory
                      ? t('skins.previewFromHistory')
                      : candidate.fromPlayer
                        ? t('skins.previewFromPlayer', { name: candidate.fromPlayer })
                        : t('skins.previewPending')
                    : current
                      ? t('skins.previewCurrent')
                      : t('skins.previewNone')
                }
                note={candidate ? null : current ? originLabel(current.origin, t) : t('skins.defaultHint')}
                pending={!!candidate}
                // Discret et en bas : presque personne ne s'en sert, mais ceux
                // qui le cherchent le cherchent là.
                footer={
                  !candidate && current ? (
                    <button
                      onClick={() => setPending({ type: 'default' })}
                      disabled={busy}
                      className="text-[11.5px] font-medium text-txt-muted underline decoration-txt-muted/40 underline-offset-2 transition-colors hover:text-txt-primary disabled:opacity-40"
                    >
                      {t('skins.defaultSkin')}
                    </button>
                  ) : null
                }
              />

              {/* Colonne du milieu : l'historique, ou la validation quand un
                  skin est en attente. Les deux se regardent à côté de l'aperçu
                  — on compare une image à une autre — et ils ne servent jamais
                  en même temps, donc ils partagent la place au lieu de se la
                  disputer. La validation apparaissait auparavant sous l'import,
                  à l'autre bout de l'écran de ce qu'elle décrit. */}
              <AnimatePresence mode="wait">
                {candidate ? (
                  <motion.div
                    key="candidate"
                    variants={fadeVariants}
                    initial="initial"
                    animate="animate"
                    exit="exit"
                    transition={fastTransition}
                    className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto rounded-2xl border border-accent/30 bg-accent/5 p-5"
                  >
                    <VariantPicker
                      value={candidate.variant}
                      onChange={(variant) => setCandidate({ ...candidate, variant })}
                    />

                    {/* `flex-1` + centrage : les explications prennent le milieu
                        de la carte au lieu de flotter entre deux vides. */}
                    <div className="flex min-h-0 flex-1 flex-col justify-center gap-3">
                      {/* Un fichier sur un compte hors ligne est le seul cas qui
                          ne survit pas à l'effacement de la base : il faut le
                          dire avant, pas après. */}
                      {candidate.kind === 'local' && account?.is_offline && (
                        <p className="rounded-xl border border-warning/35 bg-warning/10 p-3.5 text-[13px] leading-relaxed text-txt-secondary">
                          {t('skins.localWarning')}
                        </p>
                      )}

                      <p className="text-[13.5px] leading-relaxed text-txt-secondary">
                        {account?.is_offline
                          ? t('skins.offlineNotice')
                          : candidate.kind === 'local'
                            ? t('skins.fileToMojangNotice')
                            : t('skins.mojangNotice')}
                      </p>
                    </div>

                    <div className="flex gap-2">
                      <Button variant="primary" onClick={askApply} loading={busy} fullWidth>
                        {t('skins.apply')}
                      </Button>
                      <Button variant="ghost" onClick={() => setCandidate(null)} disabled={busy} fullWidth>
                        {t('skins.cancel')}
                      </Button>
                    </div>
                  </motion.div>
                ) : (
                  <motion.div
                    key="history"
                    variants={fadeVariants}
                    initial="initial"
                    animate="animate"
                    exit="exit"
                    transition={fastTransition}
                    className="flex h-full min-h-0 flex-col"
                  >
                    <History
                      entries={history}
                      currentSource={current?.source ?? null}
                      currentVariant={current?.variant ?? null}
                      onRestore={(e) => {
                        setCandidate({
                          kind: e.kind,
                          source: e.source,
                          variant: e.variant,
                          dataUri: e.data_uri!,
                          origin: e.origin,
                          fromHistory: true,
                        })
                        setJustApplied(false)
                      }}
                      onForget={(entry) => setPending({ type: 'forget', entry })}
                    />
                  </motion.div>
                )}
              </AnimatePresence>

            </div>
          </div>
        )}
      </div>

      <AnimatePresence>
        {showHosting && <HostingModal onClose={() => setShowHosting(false)} />}
      </AnimatePresence>

      {/* En popup plutôt qu'en ligne verte sous l'aperçu : le délai de
          propagation chez Mojang est une vraie information — sans elle on
          relance le jeu, on ne voit rien changer, et on croit que ça a raté.
          Une ligne discrète à côté du personnage se lisait après coup, ou pas
          du tout. */}
      <AnimatePresence>
        {justApplied && account && (
          <AppliedModal offline={account.is_offline} onClose={() => setJustApplied(false)} />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {pending && account && (
          <ConfirmModal
            pending={pending}
            offline={account.is_offline}
            onClose={() => setPending(null)}
            onConfirm={() => {
              if (pending.type === 'apply') void apply()
              else if (pending.type === 'default') void useDefault()
              else void forget(pending.entry)
            }}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

/**
 * Confirmation des gestes qui engagent.
 *
 * Trois seulement, et chacun pour une raison précise : poser un skin sur un
 * compte Microsoft le rend visible de tous, revenir au skin par défaut le fait
 * aussi, et oublier un skin importé efface son unique exemplaire. Le reste ne
 * demande rien — une confirmation qu'on voit partout finit par se cliquer sans
 * être lue.
 */
function ConfirmModal({
  pending,
  offline,
  onClose,
  onConfirm,
}: {
  pending: Pending
  offline: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const t = useT()

  const { title, text, action, danger } =
    pending.type === 'apply'
      ? { title: t('skins.confirmApplyTitle'), text: t('skins.confirmApplyText'), action: t('skins.apply'), danger: false }
      : pending.type === 'default'
        ? {
            title: t('skins.confirmDefaultTitle'),
            text: offline ? t('skins.confirmDefaultTextOffline') : t('skins.confirmDefaultTextMojang'),
            action: t('skins.defaultSkinAction'),
            danger: false,
          }
        : {
            title: t('skins.confirmForgetTitle'),
            text: pending.entry.kind === 'local' ? t('skins.confirmForgetTextLocal') : t('skins.confirmForgetTextUrl'),
            action: t('skins.forgetAction'),
            danger: true,
          }

  return (
    <ModalShell title={title} onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">{text}</p>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={onClose} fullWidth>{t('skins.cancel')}</Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} fullWidth>{action}</Button>
        </div>
      </div>
    </ModalShell>
  )
}

/**
 * Positions — boutons seuls, la mécanique viendra après.
 *
 * Rien n'est branché : cliquer une position ne change pas encore l'aperçu, et
 * l'écran le dit plutôt que de laisser croire à une panne. C'est volontaire —
 * les boutons sont là pour arrêter la forme, la pose elle-même se fera dans un
 * second temps.
 *
 * Quand ce sera le moment, `skinview3d` fournit déjà les animations
 * correspondantes (`IdleAnimation`, `WalkingAnimation`, `RunningAnimation`,
 * `FlyingAnimation`) : il n'y aura qu'à les passer à `viewer.animation`, dans
 * `SkinPreview`.
 */
const POSES = ['standing', 'walking', 'running', 'flying', 'sitting', 'waving'] as const

function PosePicker() {
  const t = useT()
  return (
    // `flex-1` : cette carte absorbe la hauteur que l'import laisse libre, et
    // ses boutons s'étirent avec elle. C'est ce qui évite deux cartes tassées
    // en haut d'une colonne vide aux deux tiers.
    <div className="flex min-h-0 flex-1 flex-col gap-2.5 rounded-2xl border border-line bg-surface-1 p-4">
      <div className="flex flex-col gap-0.5">
        <p className="text-[13.5px] font-semibold">{t('skins.poses')}</p>
        <p className="text-[11.5px] leading-relaxed text-txt-muted">{t('skins.posesSoon')}</p>
      </div>

      {/* En lignes plutôt qu'en grille 3 × 2 : étirés en hauteur, six boutons
          devenaient des pavés de 100 px pour un mot, hors de proportion avec
          ce qu'ils font. Une liste occupe la même hauteur sans qu'aucun
          élément n'ait l'air surdimensionné — et c'est la forme qui conviendra
          quand chaque pose aura sa vignette à gauche. */}
      <div className="flex min-h-0 flex-1 flex-col gap-1.5">
        {POSES.map((pose) => (
          <button
            key={pose}
            disabled
            className="flex min-h-[36px] flex-1 items-center rounded-lg border border-line bg-surface-2 px-3.5 text-[12.5px] font-medium text-txt-secondary opacity-50"
          >
            {t(`skins.pose${pose[0].toUpperCase()}${pose.slice(1)}`)}
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Choix de la méthode d'importation.
 *
 * Écrit à la main plutôt qu'un `<select>` : celui du système ne prend pas les
 * couleurs du launcher, et on veut pouvoir décrire chaque méthode en une ligne
 * sous son nom — c'est ce qui remplace les phrases d'aide retirées des onglets.
 */
function SourcePicker({ value, onChange }: { value: Tab; onChange: (tab: Tab) => void }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  // Un clic à côté et Échap referment : les deux sorties qu'on essaie devant
  // un menu ouvert.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open])

  const label = (tab: Tab) => t(`skins.source${tab[0].toUpperCase()}${tab.slice(1)}`)

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className="flex h-11 w-full items-center justify-between gap-2 rounded-xl border border-line bg-surface-2 px-3.5 text-left transition-colors hover:border-accent/45"
      >
        <span className="truncate text-[13px] font-semibold text-txt-primary">{label(value)}</span>
        <svg
          viewBox="0 0 24 24"
          width={15}
          height={15}
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`shrink-0 text-txt-secondary transition-transform ${open ? 'rotate-180' : ''}`}
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      <AnimatePresence>
        {open && (
          <motion.ul
            role="listbox"
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.14 }}
            // Au-dessus de la carte du dessous, qui sinon recouvrirait le menu.
            className="absolute z-20 mt-1.5 w-full overflow-hidden rounded-xl border border-line bg-bg-card p-1 shadow-xl shadow-black/50"
          >
            {(['player', 'url', 'file'] as const).map((m) => (
              <li key={m}>
                <button
                  type="button"
                  role="option"
                  aria-selected={m === value}
                  onClick={() => { onChange(m); setOpen(false) }}
                  className={`w-full rounded-lg px-3 py-2 text-left transition-colors ${
                    m === value ? 'bg-accent/15' : 'hover:bg-surface-2'
                  }`}
                >
                  <span className="block text-[12.5px] font-semibold text-txt-primary">{label(m)}</span>
                  <span className="block text-[11px] leading-snug text-txt-muted">{t(`skins.source${m[0].toUpperCase()}${m.slice(1)}Hint`)}</span>
                </button>
              </li>
            ))}
          </motion.ul>
        )}
      </AnimatePresence>
    </div>
  )
}

/**
 * Confirmation d'application.
 *
 * Elle existe surtout pour le délai : Mojang met quelques minutes à propager
 * un nouveau skin à tous les serveurs. Sans cette phrase, on relance le jeu,
 * on se voit avec l'ancien skin et on conclut que l'application a échoué —
 * puis on recommence.
 */
function AppliedModal({ offline, onClose }: { offline: boolean; onClose: () => void }) {
  const t = useT()
  return (
    <ModalShell title={t('skins.appliedTitle')} onClose={onClose} maxWidth="max-w-md">
      <div className="flex flex-col gap-5">
        <p className="text-[13px] leading-relaxed text-txt-secondary">
          {offline ? t('skins.appliedOffline') : t('skins.appliedMojang')}
        </p>
        <Button variant="primary" onClick={onClose} fullWidth>{t('skins.gotIt')}</Button>
      </div>
    </ModalShell>
  )
}

/**
 * Où vivent les skins, et pourquoi.
 *
 * Une seule question revient sur cet écran — « c'est stocké où ? » — et elle
 * mérite une réponse entière plutôt que trois phrases d'aide qui n'en donnent
 * chacune qu'un tiers. Le cas du fichier sur un compte hors ligne est le seul
 * qui engage l'utilisateur, il est donc distingué visuellement.
 */
function HostingModal({ onClose }: { onClose: () => void }) {
  const t = useT()
  const sections = [
    { key: 'Player', warn: false },
    { key: 'Url', warn: false },
    { key: 'File', warn: true },
    { key: 'History', warn: false },
  ] as const

  return (
    <ModalShell title={t('skins.hostingTitle')} onClose={onClose} maxWidth="max-w-xl">
      <div className="flex flex-col gap-4">
        <p className="text-[13px] leading-relaxed text-txt-secondary">{t('skins.hostingIntro')}</p>

        <div className="flex flex-col gap-2.5">
          {sections.map(({ key, warn }) => (
            <div
              key={key}
              className={`flex flex-col gap-1 rounded-xl border p-3.5 ${
                warn ? 'border-warning/35 bg-warning/10' : 'border-line bg-surface-2'
              }`}
            >
              <p className="text-[12.5px] font-semibold text-txt-primary">{t(`skins.hosting${key}Title`)}</p>
              <p className="text-[12.5px] leading-relaxed text-txt-secondary">{t(`skins.hosting${key}Text`)}</p>
            </div>
          ))}
        </div>

        <Button variant="primary" onClick={onClose} fullWidth>{t('skins.gotIt')}</Button>
      </div>
    </ModalShell>
  )
}

/** Libellé lisible de l'origine (`player:Notch`, `url`, `file`, `mojang`). */
function originLabel(origin: string, t: (k: string, v?: Record<string, string | number>) => string): string {
  if (origin.startsWith('player:')) return t('skins.originPlayer', { name: origin.slice('player:'.length) })
  if (origin === 'mojang') return t('skins.originMojang')
  if (origin === 'file') return t('skins.originFile')
  return t('skins.originUrl')
}

interface HeadSize {
  /** Côté du carré rendu, en pixels — voir `SkinFace`. */
  px: number
  /** Rembourrage de la vignette qui la contient. */
  pad: string
}

const SIZES: Record<'large' | 'medium' | 'small', HeadSize> = {
  large: { px: 104, pad: 'p-3.5' },
  medium: { px: 56, pad: 'p-3' },
  small: { px: 44, pad: 'p-2' },
}

/**
 * Quatre agencements, selon le nombre de skins.
 *
 * Le principe : ne jamais poser une grille là où il n'y a pas de quoi la
 * remplir, et ne jamais cacher derrière une barre de défilement ce qu'on peut
 * annoncer.
 *
 *   1-2    en lignes — une grille de deux cartes laisse trois quarts de cadre
 *          vide ; une ligne pleine largeur montre la tête, le modèle et
 *          l'action sans faire semblant
 *   3-4    grande grille — deux colonnes, deux rangées, généreux
 *   5-11   petite grille — tout tient encore à l'écran
 *   12+    petite grille tronquée + une tuile « +N » qui ouvre le reste en
 *          grand. Rien ne défile dans la colonne : ce qui ne tient pas est
 *          compté et cliquable, pas enfoui
 *
 * Les classes de grille sont écrites en entier : Tailwind lit le source, une
 * classe composée à l'exécution ne serait pas générée.
 */
function layoutFor(count: number): { shape: 'rows' | 'grid'; visible: number; grid: string; size: HeadSize } {
  if (count <= 2) return { shape: 'rows', visible: count, grid: '', size: SIZES.medium }
  // Quatre au plus pour la grande grille : deux colonnes, donc deux rangées de
  // ~175 px. À six, il en faudrait trois et la carte déborderait — c'est la
  // hauteur qui fixe cette borne, pas l'esthétique.
  if (count <= 4) {
    return {
      shape: 'grid',
      visible: count,
      grid: 'place-content-center grid-cols-[repeat(auto-fill,minmax(150px,1fr))]',
      size: SIZES.large,
    }
  }
  if (count <= 11) {
    return {
      shape: 'grid',
      visible: count,
      grid: 'place-content-center grid-cols-[repeat(auto-fill,minmax(104px,1fr))]',
      size: SIZES.small,
    }
  }
  // Onze vignettes plus la tuile « +N » : douze cases, soit quatre rangées de
  // trois dans la colonne, sans débordement.
  return {
    shape: 'grid',
    visible: 11,
    grid: 'content-start grid-cols-[repeat(auto-fill,minmax(104px,1fr))]',
    size: SIZES.small,
  }
}

/**
 * Les skins déjà portés.
 *
 * Une entrée sans aperçu reste affichée : son hébergeur peut être momentanément
 * injoignable, et la faire disparaître donnerait à croire qu'on l'a perdue. Elle
 * n'est simplement pas remettable tant qu'on ne peut pas la montrer.
 */
function History({
  entries,
  currentSource,
  currentVariant,
  onRestore,
  onForget,
}: {
  entries: SkinHistoryEntry[]
  currentSource: string | null
  currentVariant: SkinVariant | null
  onRestore: (entry: SkinHistoryEntry) => void
  onForget: (entry: SkinHistoryEntry) => void
}) {
  const t = useT()
  const [showAll, setShowAll] = useState(false)

  const plan = layoutFor(entries.length)
  const visible = entries.slice(0, plan.visible)
  const hidden = entries.length - visible.length

  return (
    <div className="flex h-full min-h-0 flex-col gap-2.5 rounded-2xl border border-line bg-surface-1 p-4">
      <p className="text-[13.5px] font-semibold">{t('skins.history')}</p>

      {entries.length === 0 ? (
        <p className="py-6 text-center text-[12.5px] leading-relaxed text-txt-secondary">{t('skins.historyEmpty')}</p>
      ) : plan.shape === 'rows' ? (
        // Alignées en haut, sous le titre : centrées, elles flottaient au
        // milieu du cadre sans rien pour les y rattacher.
        <div className="flex min-h-0 flex-1 flex-col gap-2">
          {visible.map((e) => (
            <HistoryRow
              key={e.id}
              entry={e}
              worn={isWorn(e, currentSource, currentVariant)}
              onRestore={onRestore}
              onForget={onForget}
            />
          ))}
        </div>
      ) : (
        <div className={`grid min-h-0 flex-1 gap-2 ${plan.grid}`}>
          {visible.map((e) => (
            <HistoryTile
              key={e.id}
              entry={e}
              worn={isWorn(e, currentSource, currentVariant)}
              size={plan.size}
              onRestore={onRestore}
              onForget={onForget}
            />
          ))}

          {/* Le reste n'est pas relégué sous une barre de défilement qu'on ne
              voit pas : il est annoncé, compté, et s'ouvre en grand. */}
          {hidden > 0 && (
            <button
              onClick={() => setShowAll(true)}
              className="flex flex-col items-center justify-center gap-1 rounded-xl border border-dashed border-line-strong bg-surface-2 p-2 text-txt-secondary transition-colors hover:border-accent/45 hover:text-txt-primary"
            >
              <span className="text-[15px] font-bold">+{hidden}</span>
              <span className="text-[10.5px] font-medium">{t('skins.showMore')}</span>
            </button>
          )}
        </div>
      )}

      <AnimatePresence>
        {showAll && (
          <HistoryModal
            entries={entries}
            currentSource={currentSource}
            currentVariant={currentVariant}
            onRestore={(e) => { onRestore(e); setShowAll(false) }}
            onForget={onForget}
            onClose={() => setShowAll(false)}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

function isWorn(entry: SkinHistoryEntry, source: string | null, variant: SkinVariant | null): boolean {
  return entry.source === source && entry.variant === variant
}

/** Toutes les entrées, quand elles ne tiennent plus dans la colonne. */
function HistoryModal({
  entries,
  currentSource,
  currentVariant,
  onRestore,
  onForget,
  onClose,
}: {
  entries: SkinHistoryEntry[]
  currentSource: string | null
  currentVariant: SkinVariant | null
  onRestore: (entry: SkinHistoryEntry) => void
  onForget: (entry: SkinHistoryEntry) => void
  onClose: () => void
}) {
  const t = useT()
  return (
    <ModalShell title={t('skins.allTitle')} onClose={onClose} maxWidth="max-w-3xl">
      <div className="grid max-h-[60vh] grid-cols-[repeat(auto-fill,minmax(104px,1fr))] content-start gap-2 overflow-y-auto pr-1">
        {entries.map((e) => (
          <HistoryTile
            key={e.id}
            entry={e}
            worn={isWorn(e, currentSource, currentVariant)}
            size={SIZES.small}
            onRestore={onRestore}
            onForget={onForget}
          />
        ))}
      </div>
    </ModalShell>
  )
}

/**
 * Une ligne — la forme des tout petits nombres.
 *
 * À un ou deux skins, une grille pose des cartes minuscules dans un coin d'un
 * cadre vide. Une ligne pleine largeur, elle, montre une tête lisible, le
 * modèle, et l'action, sans prétendre remplir une grille qui n'existe pas.
 */
function HistoryRow({
  entry,
  worn,
  onRestore,
  onForget,
}: {
  entry: SkinHistoryEntry
  worn: boolean
  onRestore: (entry: SkinHistoryEntry) => void
  onForget: (entry: SkinHistoryEntry) => void
}) {
  const t = useT()
  return (
    <div
      className={`group relative flex items-center gap-3 rounded-xl border p-3 transition-colors ${
        worn ? 'border-accent/45 bg-accent/10' : 'border-line bg-surface-2 hover:border-accent/35'
      }`}
    >
      <Head entry={entry} size={SIZES.medium} />

      <div className="flex min-w-0 flex-1 flex-col">
        <span className="text-[13px] font-semibold text-txt-primary">{t(`skins.${entry.variant}`)}</span>
        <span className="truncate text-[11.5px] text-txt-muted">{t(`skins.${entry.variant}Hint`)}</span>
      </div>

      {worn ? (
        <span className="mr-6 shrink-0 text-[12px] font-semibold text-accent-hover">{t('skins.worn')}</span>
      ) : (
        <Button size="sm" variant="ghost" onClick={() => onRestore(entry)} disabled={!entry.data_uri} className="mr-6">
          {t('skins.restore')}
        </Button>
      )}

      <ForgetButton onClick={() => onForget(entry)} />
    </div>
  )
}

/** Une vignette de grille — la forme dès qu'il y en a plusieurs. */
function HistoryTile({
  entry,
  worn,
  size,
  onRestore,
  onForget,
}: {
  entry: SkinHistoryEntry
  worn: boolean
  size: HeadSize
  onRestore: (entry: SkinHistoryEntry) => void
  onForget: (entry: SkinHistoryEntry) => void
}) {
  const t = useT()
  return (
    <div
      className={`group relative rounded-xl border transition-colors ${
        worn ? 'border-accent/45 bg-accent/10' : 'border-line bg-surface-2 hover:border-accent/35'
      }`}
    >
      {/* La vignette entière change de skin : viser un lien de onze pixels pour
          faire le geste le plus courant de l'écran n'avait pas de sens. Portée
          ou sans aperçu, elle n'est plus un bouton — il n'y aurait rien à
          déclencher. */}
      <button
        onClick={() => onRestore(entry)}
        disabled={worn || !entry.data_uri}
        title={worn ? undefined : t('skins.restore')}
        className={`flex w-full flex-col items-center gap-1.5 disabled:cursor-default ${size.pad}`}
      >
        <Head entry={entry} size={size} />
        <span className="text-[11px] text-txt-muted">{t(`skins.${entry.variant}`)}</span>
        {worn ? (
          <span className="text-[11px] font-semibold text-accent-hover">{t('skins.worn')}</span>
        ) : (
          <span className="text-[11px] font-semibold text-txt-secondary transition-colors group-hover:text-txt-primary">
            {t('skins.restore')}
          </span>
        )}
      </button>

      <ForgetButton onClick={() => onForget(entry)} />
    </div>
  )
}

function ForgetButton({ onClick }: { onClick: () => void }) {
  const t = useT()
  return (
    <button
      onClick={onClick}
      title={t('skins.forget')}
      className="absolute right-1 top-1 flex h-5 w-5 items-center justify-center rounded-md text-txt-muted opacity-0 transition-all hover:bg-danger/20 hover:text-danger focus-visible:opacity-100 group-hover:opacity-100"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" width={11} height={11}>
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </button>
  )
}

/** La tête du skin — voir `SkinFace` pour les deux couches à superposer. */
function Head({ entry, size }: { entry: SkinHistoryEntry; size: HeadSize }) {
  const t = useT()
  if (!entry.data_uri) {
    return (
      <div
        style={{ width: size.px, height: size.px }}
        className="flex shrink-0 items-center justify-center whitespace-pre-line rounded-lg bg-surface-4 text-center text-[9px] leading-tight text-txt-muted"
      >
        {t('skins.noPreview')}
      </div>
    )
  }
  return <SkinFace dataUri={entry.data_uri} size={size.px} className="rounded-lg" />
}

/**
 * Le choix du compte.
 *
 * Trois choses le disent, puisque le mot « Compte » ne le dit plus : l'icône
 * de personnage en tête de ligne, la pastille de sélection sur l'avatar du
 * compte actif, et le fait que les autres soient visiblement en retrait. Une
 * rangée de cartes toutes pareilles ne se lit pas comme un choix — elle se lit
 * comme une liste.
 *
 * Les avatars sont ceux du skin porté, pas une initiale : c'est l'écran des
 * skins, montrer le personnage est la façon la plus directe de dire de qui on
 * parle.
 */
/**
 * Au-delà, les cartes passeraient à la ligne et la rangée mangerait la hauteur
 * des trois colonnes du dessous. Le surplus part dans une modale.
 */
const MAX_ACCOUNT_CARDS = 5

function AccountPicker({
  accounts,
  selected,
  faces,
  onPick,
}: {
  accounts: McAccountInfo[]
  selected: string | null
  /** Aperçu du skin par compte — `undefined` tant qu'il n'est pas chargé. */
  faces: Record<string, string | null>
  onPick: (uuid: string) => void
}) {
  const t = useT()
  const [showAll, setShowAll] = useState(false)

  // Le compte choisi est toujours visible, même s'il est loin dans la liste :
  // il prend alors la dernière place. Sans ça, choisir un compte depuis la
  // modale le ferait disparaître aussitôt après l'avoir désigné.
  const visible = accounts.slice(0, MAX_ACCOUNT_CARDS)
  if (selected && !visible.some((a) => a.mc_uuid === selected)) {
    const chosen = accounts.find((a) => a.mc_uuid === selected)
    if (chosen) visible[MAX_ACCOUNT_CARDS - 1] = chosen
  }
  const hidden = accounts.length - visible.length

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="mr-0.5 text-txt-muted" title={t('skins.accountHint')}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" className="h-[18px] w-[18px]">
          <circle cx="12" cy="8" r="3.5" />
          <path d="M5 20c0-3.5 3-5.5 7-5.5s7 2 7 5.5" />
        </svg>
      </span>

      {visible.map((a) => {
        const active = a.mc_uuid === selected
        return (
          <button
            key={a.mc_uuid}
            onClick={() => onPick(a.mc_uuid)}
            aria-pressed={active}
            title={active ? undefined : t('skins.accountHint')}
            className={`flex items-center gap-2.5 rounded-xl border px-3 py-2 text-left transition-all ${
              active
                ? 'border-accent bg-accent/15 shadow-[0_0_20px_rgba(75,63,207,0.18)]'
                : 'border-line bg-surface-1 opacity-60 hover:border-line-strong hover:opacity-100'
            }`}
          >
            <AccountAvatar account={a} face={faces[a.mc_uuid]} size={32} checked={active} />
            <span className="flex flex-col">
              <span className={`text-[13px] font-semibold ${active ? 'text-txt-primary' : 'text-txt-secondary'}`}>
                {a.mc_username}
              </span>
              <span className="text-[11px] text-txt-muted">
                {a.is_offline ? t('skins.offlineBadge') : t('skins.mojangBadge')}
              </span>
            </span>
          </button>
        )
      })}

      {hidden > 0 && (
        <button
          onClick={() => setShowAll(true)}
          title={t('skins.allAccountsTitle')}
          className="flex h-[52px] items-center gap-1.5 rounded-xl border border-dashed border-line-strong bg-surface-1 px-3.5 text-txt-secondary transition-colors hover:border-accent/45 hover:text-txt-primary"
        >
          <span className="text-[15px] font-bold leading-none">+{hidden}</span>
        </button>
      )}

      <AnimatePresence>
        {showAll && (
          <AccountModal
            accounts={accounts}
            selected={selected}
            faces={faces}
            onPick={(uuid) => { onPick(uuid); setShowAll(false) }}
            onClose={() => setShowAll(false)}
          />
        )}
      </AnimatePresence>
    </div>
  )
}

/** Tous les comptes, quand ils ne tiennent plus sur la rangée. */
function AccountModal({
  accounts,
  selected,
  faces,
  onPick,
  onClose,
}: {
  accounts: McAccountInfo[]
  selected: string | null
  faces: Record<string, string | null>
  onPick: (uuid: string) => void
  onClose: () => void
}) {
  const t = useT()
  return (
    <ModalShell title={t('skins.allAccountsTitle')} onClose={onClose} maxWidth="max-w-md">
      <div className="flex max-h-[60vh] flex-col gap-2 overflow-y-auto pr-1">
        {accounts.map((a) => {
          const active = a.mc_uuid === selected
          return (
            <button
              key={a.mc_uuid}
              onClick={() => onPick(a.mc_uuid)}
              className={`flex items-center gap-3 rounded-xl border p-3 text-left transition-colors ${
                active ? 'border-accent bg-accent/15' : 'border-line bg-surface-2 hover:border-accent/40'
              }`}
            >
              <AccountAvatar account={a} face={faces[a.mc_uuid]} size={36} checked={active} />
              <span className="flex min-w-0 flex-col">
                <span className="truncate text-[13.5px] font-semibold text-txt-primary">{a.mc_username}</span>
                <span className="text-[11.5px] text-txt-muted">
                  {a.is_offline ? t('skins.offlineBadge') : t('skins.mojangBadge')}
                </span>
              </span>
            </button>
          )
        })}
      </div>
    </ModalShell>
  )
}

function AccountAvatar({
  account,
  face,
  size,
  checked,
}: {
  account: McAccountInfo
  face: string | null | undefined
  size: number
  checked: boolean
}) {
  return (
    <span className="relative shrink-0">
      {face ? (
        <SkinFace dataUri={face} size={size} className="rounded-lg" />
      ) : (
        <>
          {/* Sans skin enregistré, l'avatar du compte — et pour un compte hors
              ligne, dont l'UUID est inventé, le service rend l'apparence par
              défaut, donc Steve. C'est exactement ce qu'il faut montrer : un
              compte sans skin n'est pas une initiale dans un carré, c'est un
              personnage par défaut, et c'est déjà ce que l'aperçu 3D affiche.
              L'initiale ne reste que pour le cas sans réseau. */}
          <img
            src={`https://mc-heads.net/avatar/${account.mc_uuid}/${size}`}
            alt=""
            style={{ width: size, height: size }}
            className="rounded-lg [image-rendering:pixelated]"
            onError={(e) => {
              e.currentTarget.style.display = 'none'
              const fallback = e.currentTarget.nextElementSibling as HTMLElement | null
              if (fallback) fallback.style.display = 'flex'
            }}
          />
          <span
            style={{ width: size, height: size }}
            className="hidden items-center justify-center rounded-lg bg-accent/40 font-black text-white [font-family:monospace] text-[14px]"
          >
            {account.mc_username[0].toUpperCase()}
          </span>
        </>
      )}

      {/* La pastille dit « c'est celui-là » sans mot et sans couleur à
          interpréter. */}
      {checked && (
        <span className="absolute -bottom-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full border-2 border-bg-primary bg-accent text-white">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round" className="h-2.5 w-2.5">
            <path d="M5 13l4 4L19 7" />
          </svg>
        </span>
      )}
    </span>
  )
}

function VariantPicker({
  value,
  onChange,
}: {
  value: SkinVariant
  onChange: (variant: SkinVariant) => void
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-2">
      <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-secondary">{t('skins.variant')}</p>
      {/* Empilés plutôt que côte à côte : deux cartes dans une demi-largeur
          donnaient un titre de deux mots sur une ligne et son explication sur
          deux, en corps 11. Sur toute la largeur, chaque option tient en une
          ligne lisible, et la pastille dit laquelle est choisie sans qu'on ait
          à comparer deux fonds. */}
      <div className="flex flex-col gap-2">
        {(['classic', 'slim'] as const).map((v) => (
          <button
            key={v}
            onClick={() => onChange(v)}
            className={`flex items-center gap-3 rounded-xl border px-4 py-3 text-left transition-colors ${
              value === v ? 'border-accent/50 bg-accent/15' : 'border-line bg-surface-1 hover:border-line-strong'
            }`}
          >
            <span
              className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border-2 transition-colors ${
                value === v ? 'border-accent-hover' : 'border-line-strong'
              }`}
            >
              {value === v && <span className="h-2 w-2 rounded-full bg-accent-hover" />}
            </span>
            <span className="flex min-w-0 flex-col">
              <span className="text-[14px] font-semibold">{t(`skins.${v}`)}</span>
              <span className="text-[12px] text-txt-muted">{t(`skins.${v}Hint`)}</span>
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Aperçu 3D.
 *
 * Le modèle est imposé, jamais deviné : c'est précisément le réglage qu'on est
 * en train de choisir juste à côté, et laisser `auto-detect` décider ferait
 * mentir l'aperçu sur ce qui sera appliqué. `skinview3d` nomme le modèle
 * classique « default » ; la traduction se fait ici, une seule fois.
 *
 * La zone de rendu prend la hauteur qu'on lui laisse (`flex-1`) au lieu d'une
 * hauteur fixe : c'est elle qui absorbe les écarts de taille de fenêtre, pour
 * que le reste de l'écran n'ait jamais à défiler.
 */
function SkinPreview({
  dataUri,
  fallbackUrl,
  variant,
  label,
  note,
  footer,
  pending,
}: {
  dataUri: string | null
  /** Apparence par défaut du compte, montrée quand aucun skin n'est choisi. */
  fallbackUrl: string | null
  variant: SkinVariant
  label: string
  /** Deuxième ligne : d'où vient le skin porté. */
  note: string | null
  footer: ReactNode
  pending: boolean
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)

  // Aucun skin choisi ne veut pas dire aucun personnage : le compte porte
  // alors son apparence par défaut, et c'est elle qu'on montre. Le modèle
  // repasse en détection automatique dans ce cas — le défaut n'est pas
  // forcément classique, et ce n'est plus un réglage qu'on est en train de
  // choisir.
  const load = useCallback((uri: string | null, fallback: string | null, model: SkinVariant) => {
    const viewer = viewerRef.current
    if (!viewer) return
    const src = uri ?? fallback
    if (!src) {
      viewer.loadSkin(null)
      return
    }
    const options = uri ? { model: model === 'slim' ? ('slim' as const) : ('default' as const) } : { model: 'auto-detect' as const }
    ;(viewer.loadSkin(src, options) as Promise<void> | void)?.catch?.(() => {})
  }, [])

  useEffect(() => {
    if (!canvasRef.current || !boxRef.current) return
    const box = boxRef.current
    const { width, height } = box.getBoundingClientRect()

    // Fond transparent : `skinview3d` crée son contexte WebGL sans `alpha`,
    // et three.js le laisse alors à `false` — son effacement peindrait donc du
    // noir. Créer le contexte avant lui impose nos attributs, la spécification
    // WebGL exigeant que les `getContext` suivants du même type rendent le
    // contexte déjà créé. Même raisonnement que SkinBust.
    canvasRef.current.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvasRef.current.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || 260,
      height: height || 360,
    })
    viewer.background = null
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 0.6
    viewer.zoom = 0.82
    viewer.fov = 55
    viewer.animation = new WalkingAnimation()
    viewer.animation.speed = 0.4
    viewerRef.current = viewer

    const ro = new ResizeObserver(() => {
      const r = box.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) viewer.setSize(r.width, r.height)
    })
    ro.observe(box)

    return () => {
      ro.disconnect()
      viewer.dispose()
      viewerRef.current = null
    }
  }, [])

  useEffect(() => { load(dataUri, fallbackUrl, variant) }, [dataUri, fallbackUrl, variant, load])

  return (
    <div
      className={`flex min-h-0 flex-col gap-2 rounded-2xl border p-4 transition-colors ${
        pending ? 'border-accent/30 bg-accent/5' : 'border-line bg-surface-1'
      }`}
    >
      <div ref={boxRef} className="relative min-h-[200px] w-full flex-1">
        <canvas ref={canvasRef} className="h-full w-full" />
      </div>

      <div className="flex flex-col items-center gap-1 text-center">
        <p className="text-[13px] font-semibold text-txt-primary">{label}</p>
        {note && <p className="text-[11.5px] leading-relaxed text-txt-secondary">{note}</p>}
        {footer}
      </div>
    </div>
  )
}
