import { create } from 'zustand'

/** Ouverture du menu latéral du téléphone (`mobile/ui/NavDrawer`). Un seul
 *  menu pour toute l'appli, ouvert par le bouton ☰ de n'importe quel en-tête. */
export const useNavDrawer = create<{ open: boolean; setOpen: (open: boolean) => void }>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
}))
