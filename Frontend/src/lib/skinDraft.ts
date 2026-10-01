import type { SkinVariant } from '@/api/client'

/**
 * Passage de main entre l'éditeur et l'écran Skins.
 *
 * L'éditeur ne modifie aucun compte — comme le catalogue, il désigne, et c'est
 * l'écran Skins qui applique, avec sa confirmation. Il faut donc lui
 * transmettre le skin qui vient d'être dessiné.
 *
 * Pas par l'URL : un PNG ne tient pas dans une adresse, et le skin est déjà
 * rangé côté Rust au moment où l'on quitte l'éditeur. On ne passe donc que sa
 * référence, par ce module — les deux écrans vivent dans la même page, leur
 * état de module survit à la navigation, et ça évite un aller-retour de plus
 * pour relire ce qu'on vient d'écrire.
 */
export interface SkinDraft {
  /** Nom du fichier rangé par `skin_import_bytes`. */
  source: string
  variant: SkinVariant
  dataUri: string
}

let pending: SkinDraft | null = null

export function putSkinDraft(draft: SkinDraft): void {
  pending = draft
}

/** Se consomme une fois : revenir sur l'écran ne doit pas reproposer un skin
 *  qu'on a déjà écarté. */
export function takeSkinDraft(): SkinDraft | null {
  const draft = pending
  pending = null
  return draft
}
