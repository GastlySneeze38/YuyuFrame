/** Icônes de la barre de l'écran des mods (chemins SVG en 24 × 24), partagées
 *  par la barre du PC (`pages/Mods.tsx`) et celle du téléphone
 *  (`MobileModsToolbar`) : un même écran a la même icône partout. */
export const TOOLBAR_ICON = {
  mods: 'M21 16.5c0 .38-.21.71-.53.88l-7.9 4.44c-.16.12-.36.18-.57.18s-.41-.06-.57-.18l-7.9-4.44A1 1 0 013 16.5v-9c0-.38.21-.71.53-.88l7.9-4.44c.16-.12.36-.18.57-.18s.41.06.57.18l7.9 4.44c.32.17.53.5.53.88v9z',
  search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  modpack: 'M12 2L1 9l11 7 9-5.73V17h2V9L12 2zM3 13.18v4.91L12 23l9-4.91v-4.91l-9 5.73-9-5.73z',
  list: 'M3 5h18v2H3V5zm0 6h18v2H3v-2zm0 6h12v2H3v-2z',
  grid: 'M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z',
  star: 'M12 3l2.09 6.26L20.5 9.5l-5 3.8 1.9 6.2L12 15.8 6.6 19.5l1.9-6.2-5-3.8 6.41-.24L12 3z',
  sliders: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
} as const
