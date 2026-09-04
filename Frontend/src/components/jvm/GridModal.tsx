import { useState } from 'react'
import { formatRam } from '@/lib/format'
import { autoVendorFor, familyFor, type JvmFamily } from '@/lib/jvmFlags'
import { JvmModal } from './Modal'
import { Field, Segmented, Warn } from './controls'
import { RamPicker } from '@/components/ui/RamPicker'
import type { Instance, JvmArgsMode, JvmProfile, JvmVendor } from '@/types'

const VENDORS: { id: JvmVendor; label: string }[] = [
  { id: 'auto', label: 'Auto' },
  { id: 'temurin', label: 'Temurin' },
  { id: 'openj9', label: 'OpenJ9' },
  { id: 'graal', label: 'GraalVM' },
  { id: 'custom', label: 'Personnalisé' },
]

const HOTSPOT_GC = [
  { id: 'auto', label: 'Auto' },
  { id: 'g1', label: 'G1GC' },
  { id: 'zgc', label: 'ZGC' },
]

// `metronome` volontairement absent : la JVM OpenJ9 de Windows le refuse au
// démarrage (JVMJ9VM007E), il n'existe que sur les builds temps réel.
const OPENJ9_GC = [
  { id: 'auto', label: 'Auto (gencon)' },
  { id: 'gencon', label: 'gencon' },
  { id: 'optthruput', label: 'optthruput' },
  { id: 'optavgpause', label: 'optavgpause' },
  { id: 'balanced', label: 'balanced' },
]

const FAMILY_LABEL: Record<JvmFamily, string> = {
  hotspot: 'HotSpot',
  openj9: 'OpenJ9',
  graal: 'GraalVM (HotSpot + Graal)',
}

export const VENDOR_SHORT: Record<string, string> = {
  auto: 'Auto', temurin: 'Temurin', openj9: 'OpenJ9', graal: 'GraalVM', custom: 'JVM perso.',
}

/**
 * Réglages de la JVM — l'ancienne colonne de gauche, devenue une modale.
 *
 * Elle occupait en permanence un quart de l'écran pour six réglages qu'on
 * touche une fois et qu'on ne rouvre plus, pendant que la zone d'édition, elle,
 * manquait de place. Elle est maintenant résumée en une ligne (voir
 * `GridSummary`) et ne s'ouvre que quand on veut la changer.
 *
 * Le chemin `java.exe` et le mode de fusion sont repliés derrière "Avancé" :
 * ce sont les deux seuls réglages ici dont la mauvaise valeur empêche le jeu de
 * démarrer, et ils n'ont rien à faire au premier coup d'œil.
 */
export function GridModal({ draft, onChange, instanceRam, linked, onManageLinks, onClose }: {
  draft: JvmProfile
  onChange: <K extends keyof JvmProfile>(key: K, value: JvmProfile[K]) => void
  /** RAM de l'instance servant de référence quand la config n'impose rien. */
  instanceRam: number
  linked: Instance[]
  onManageLinks: () => void
  onClose: () => void
}) {
  const [advanced, setAdvanced] = useState(false)
  const ram = draft.ram_mb ?? instanceRam
  const family = familyFor(draft.jvm_vendor, ram)
  const gcOptions = family === 'openj9' ? OPENJ9_GC : HOTSPOT_GC
  const autoVendor = autoVendorFor(ram)

  return (
    <JvmModal
      title="Réglages de la JVM"
      sub="La base que le launcher pose avant vos drapeaux. Elle décide aussi des drapeaux qui ont un sens."
      onClose={onClose}
      width={560}
      footer={
        <button
          onClick={onClose}
          className="h-[30px] rounded-lg border border-[rgba(75,63,207,0.7)] bg-[rgba(75,63,207,0.4)] px-4 text-[11px] font-bold text-white transition-colors hover:bg-[rgba(75,63,207,0.55)]"
        >
          Terminé
        </button>
      }
    >
      <div className="flex flex-col gap-5 px-5 py-4">
        <Field
          label="Mémoire"
          hint={draft.ram_mb
            ? 'Cette config impose son tas à toutes les instances reliées.'
            : "La RAM choisie sur chaque instance est conservée."}
        >
          <Segmented
            options={[{ id: 'inherit', label: "Celle de l'instance" }, { id: 'fixed', label: 'Imposée' }]}
            value={draft.ram_mb ? 'fixed' : 'inherit'}
            onChange={(v) => onChange('ram_mb', v === 'fixed' ? instanceRam : null)}
          />
          {draft.ram_mb !== null && <RamPicker value={draft.ram_mb} onChange={(v) => onChange('ram_mb', v)} />}
        </Field>

        <Field
          label="Vendeur JVM"
          hint={draft.jvm_vendor === 'auto'
            ? `Auto choisit ${autoVendor === 'openj9' ? 'OpenJ9' : 'Temurin'} pour ${formatRam(ram)}. Famille obtenue : ${FAMILY_LABEL[family]}.`
            : `Famille obtenue : ${FAMILY_LABEL[family]}.`}
        >
          <Segmented options={VENDORS} value={draft.jvm_vendor} onChange={(v) => onChange('jvm_vendor', v)} />
          {family === 'openj9' && (
            <Warn>
              Le JIT d'OpenJ9 plafonne bien plus bas que celui d'HotSpot sur Minecraft — aucune policy GC ne rattrape
              l'écart.
            </Warn>
          )}
        </Field>

        <Field label="Ramasse-miettes">
          <Segmented options={gcOptions} value={draft.gc_policy} onChange={(v) => onChange('gc_policy', v)} />
          {draft.gc_policy === 'zgc' && ram < 6144 && (
            <Warn>ZGC exige 6 Go et Java 21+ — remplacé automatiquement par G1GC en dessous.</Warn>
          )}
        </Field>

        <div className="flex flex-col gap-3 border-t border-[rgba(255,255,255,0.07)] pt-4">
          <button
            onClick={() => setAdvanced((v) => !v)}
            className="flex items-center gap-1.5 self-start text-[11px] font-semibold text-[rgba(255,255,255,0.4)] transition-colors hover:text-[rgba(255,255,255,0.75)]"
          >
            <svg
              viewBox="0 0 10 6" fill="currentColor" width={8} height={5}
              className={`transition-transform duration-150 ${advanced ? '' : '-rotate-90'}`}
            >
              <path d="M0 0l5 6 5-6z" />
            </svg>
            Avancé
          </button>

          {advanced && (
            <>
              <Field
                label={`Chemin java.exe ${draft.jvm_vendor === 'custom' ? '(requis)' : '(optionnel)'}`}
                hint="Épingle une install précise. Prioritaire sur toute la résolution automatique."
              >
                <input
                  type="text"
                  placeholder="C:\...\bin\java.exe"
                  value={draft.jvm_custom_path ?? ''}
                  onChange={(e) => onChange('jvm_custom_path', e.target.value || null)}
                  className="h-[32px] w-full rounded-xl border border-[rgba(255,255,255,0.1)] bg-[rgba(0,0,0,0.4)] px-2.5 font-mono text-[11px] text-white outline-none focus:border-[rgba(75,63,207,0.6)]"
                />
              </Field>

              <Field
                label="Fusion avec la base"
                hint={draft.args_mode === 'append'
                  ? "Le tuning du launcher est conservé, vos drapeaux écrasent leurs homologues."
                  : "Seuls -Xmx/-Xms et les library path survivent. Tout le reste vient de vous."}
              >
                <Segmented
                  options={[
                    { id: 'append' as JvmArgsMode, label: 'Compléter la base' },
                    { id: 'replace' as JvmArgsMode, label: 'Remplacer la base' },
                  ]}
                  value={draft.args_mode}
                  onChange={(v) => onChange('args_mode', v)}
                />
              </Field>
            </>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-1.5 border-t border-[rgba(255,255,255,0.07)] pt-4">
          <span className="text-[10px] font-semibold uppercase tracking-[0.1em] text-[rgba(255,255,255,0.35)]">
            {linked.length > 0 ? `Reliée à ${linked.length} instance${linked.length > 1 ? 's' : ''}` : 'Reliée à aucune instance'}
          </span>
          {linked.map((i) => (
            <span key={i.id} className="rounded-md border border-[rgba(75,63,207,0.5)] bg-[rgba(75,63,207,0.2)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(200,195,255,0.9)]">
              {i.name}
            </span>
          ))}
          <button
            onClick={onManageLinks}
            className="ml-auto text-[10px] font-semibold text-[rgba(150,140,240,0.85)] hover:text-[rgba(190,183,255,1)]"
          >
            Gérer les liens →
          </button>
        </div>
      </div>
    </JvmModal>
  )
}

/** Résumé d'une ligne des réglages ci-dessus, seule trace qu'ils laissent sur
 * la page tant qu'on ne les ouvre pas. */
export function GridSummary({ draft, instanceRam, onOpen }: {
  draft: JvmProfile
  instanceRam: number
  onOpen: () => void
}) {
  const ram = draft.ram_mb ?? instanceRam
  const vendor = draft.jvm_vendor === 'auto'
    ? `Auto → ${autoVendorFor(ram) === 'openj9' ? 'OpenJ9' : 'Temurin'}`
    : VENDOR_SHORT[draft.jvm_vendor]
  // Les policies OpenJ9 (`gencon`, `optthruput`...) sont des noms propres en
  // minuscules, seuls les sigles HotSpot se mettent en capitales.
  const gc = draft.gc_policy === 'auto' ? 'GC auto'
    : draft.gc_policy === 'g1' ? 'G1GC'
      : draft.gc_policy === 'zgc' ? 'ZGC' : draft.gc_policy

  return (
    <button
      onClick={onOpen}
      className="flex w-full items-center gap-2 rounded-xl border border-[rgba(255,255,255,0.07)] bg-[rgba(255,255,255,0.025)] px-3.5 py-2 text-left transition-colors hover:border-[rgba(255,255,255,0.16)]"
    >
      <span className="text-[11px] font-semibold text-[rgba(255,255,255,0.6)]">{vendor}</span>
      <Dot /><span className="text-[11px] text-[rgba(255,255,255,0.45)]">{gc}</span>
      <Dot /><span className="text-[11px] text-[rgba(255,255,255,0.45)]">
        {draft.ram_mb ? formatRam(draft.ram_mb) : "RAM de l'instance"}
      </span>
      {draft.args_mode === 'replace' && (
        <span className="rounded-md bg-[rgba(240,180,90,0.14)] px-1.5 py-0.5 text-[10px] font-semibold text-[rgba(240,180,90,0.85)]">
          Remplace la base
        </span>
      )}
      <span className="ml-auto flex-shrink-0 text-[11px] font-semibold text-[rgba(150,140,240,0.85)]">Réglages →</span>
    </button>
  )
}

function Dot() {
  return <span className="text-[rgba(255,255,255,0.18)]">·</span>
}
