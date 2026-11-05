import { useRef } from 'react'
import type { Instance } from '@/types'
import { InstanceIcon } from '@/components/instances/InstanceIcon'
import { ActionSheet, SHEET_ICONS } from '@/mobile/ui/ActionSheet'
import { useT } from '@/i18n'

/**
 * Les actions d'une instance : le menu à trois points de la carte du PC.
 * Mêmes gestes — Paramètres, Dupliquer, Supprimer — sauf « Ouvrir le
 * dossier » : un téléphone n'a pas d'explorateur de fichiers à qui le
 * confier. La suppression se confirme dans la feuille.
 */
export function InstanceActionSheet({ instance, onClose, onSettings, onDuplicate, onDelete }: {
  instance: Instance | null
  onClose: () => void
  onSettings: (instance: Instance) => void
  onDuplicate: (instance: Instance) => void
  onDelete: (id: string) => void
}) {
  const t = useT()
  // La dernière instance visée reste affichée pendant que la feuille repart :
  // sans elle, l'en-tête et les actions disparaîtraient en pleine sortie.
  const last = useRef(instance)
  if (instance) last.current = instance
  const shown = last.current
  return (
    <ActionSheet
      open={!!instance}
      onClose={onClose}
      header={shown && (
        <>
          <InstanceIcon instance={shown} size={36} />
          <span className="min-w-0 flex-1 truncate text-[15px] font-bold text-txt-primary">{shown.name}</span>
        </>
      )}
      actions={shown ? [
        { id: 'settings', label: t('instancesPage.settings'), icon: SHEET_ICONS.settings, onSelect: () => onSettings(shown) },
        { id: 'duplicate', label: t('instancesPage.duplicate'), icon: SHEET_ICONS.duplicate, onSelect: () => onDuplicate(shown) },
        {
          id: 'delete',
          label: t('instancesPage.deleteForever'),
          icon: SHEET_ICONS.delete,
          danger: true,
          confirm: t('instancesPage.confirmDeleteInstance'),
          onSelect: () => onDelete(shown.id),
        },
      ] : []}
    />
  )
}
