import type { t as tFn } from '@/i18n'

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
  /** Vendu sur le site comme "Bientôt disponible" — pas encore achetable. */
  comingSoon?: boolean
  /** Mis en avant visuellement, comme le badge "Populaire" du site. */
  featured?: boolean
}

export function getPlans(t: typeof tFn): PlanMeta[] {
  return [
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
        { label: t('plans.features.freeLoaders'), ok: true },
        { label: t('plans.features.freeUnlimitedInstances'), ok: true },
        { label: t('plans.features.freeModManagement'), ok: true },
        { label: t('plans.features.freeConsole'), ok: true },
      ],
    },
    {
      id: 'premium',
      name: 'Premium',
      price: '3.99',
      color: '#818cf8',
      borderColor: 'rgba(129,140,248,0.35)',
      badgeBg: 'rgba(75,63,207,0.25)',
      badgeColor: '#818cf8',
      glowColor: 'rgba(75,63,207,0.08)',
      featured: true,
      features: [
        { label: t('plans.features.premiumAllFree'), ok: true },
        { label: t('plans.features.premiumCloudSync'), ok: true },
        { label: t('plans.features.premiumStats'), ok: true },
        { label: t('plans.features.premiumBadge'), ok: true },
        { label: t('plans.features.premiumOneClickServer'), ok: false },
      ],
    },
    {
      id: 'ultimate',
      name: 'Ultimate',
      price: '7.99',
      color: '#f59e0b',
      borderColor: 'rgba(245,158,11,0.35)',
      badgeBg: 'rgba(245,158,11,0.15)',
      badgeColor: '#f59e0b',
      glowColor: 'rgba(245,158,11,0.06)',
      comingSoon: true,
      features: [
        { label: t('plans.features.ultimateAllPremium'), ok: true },
        { label: t('plans.features.ultimateCloudSync'), ok: true },
        { label: t('plans.features.ultimateSyncedInstances'), ok: true },
        { label: t('plans.features.ultimateOneClickServer'), ok: true },
      ],
    },
  ]
}
