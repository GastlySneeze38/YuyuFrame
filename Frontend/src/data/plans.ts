export interface PlanFeature {
  label: string
  ok: boolean
}

export interface PlanMeta {
  id: string
  name: string
  price: string | null
  color: string
  borderColor: string
  badgeBg: string
  badgeColor: string
  glowColor: string
  features: PlanFeature[]
}

export const PLANS: PlanMeta[] = [
  {
    id: 'free',
    name: 'Free',
    price: null,
    color: 'rgba(255,255,255,0.12)',
    borderColor: 'rgba(255,255,255,0.1)',
    badgeBg: 'rgba(255,255,255,0.08)',
    badgeColor: 'rgba(255,255,255,0.5)',
    glowColor: 'rgba(255,255,255,0.03)',
    features: [
      { label: 'Launcher & gestion des mods', ok: true },
      { label: '2 comptes Minecraft', ok: true },
      { label: 'Sync cloud', ok: false },
      { label: 'Comptes illimités', ok: false },
      { label: 'Stats & analytics', ok: false },
    ],
  },
  {
    id: 'premium',
    name: 'Premium',
    price: '7.99',
    color: '#818cf8',
    borderColor: 'rgba(129,140,248,0.35)',
    badgeBg: 'rgba(75,63,207,0.25)',
    badgeColor: '#818cf8',
    glowColor: 'rgba(75,63,207,0.08)',
    features: [
      { label: 'Tout le plan Free', ok: true },
      { label: 'Comptes Minecraft illimités', ok: true },
      { label: 'Sync cloud (3 saves)', ok: true },
      { label: 'Stats & analytics', ok: true },
      { label: 'Serveur en 1 clic', ok: false },
    ],
  },
  {
    id: 'ultimate',
    name: 'Ultimate',
    price: '15.99',
    color: '#f59e0b',
    borderColor: 'rgba(245,158,11,0.35)',
    badgeBg: 'rgba(245,158,11,0.15)',
    badgeColor: '#f59e0b',
    glowColor: 'rgba(245,158,11,0.06)',
    features: [
      { label: 'Tout le plan Premium', ok: true },
      { label: 'Sync cloud (10 saves)', ok: true },
      { label: 'Serveur Minecraft en 1 clic', ok: true },
      { label: 'Tunnel proxy & URL custom', ok: true },
      { label: 'Accès anticipé', ok: true },
    ],
  },
]
