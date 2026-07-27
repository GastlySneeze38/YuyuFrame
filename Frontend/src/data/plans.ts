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
        { label: t('plans.features.freeLauncher'), ok: true },
        { label: t('plans.features.free2Accounts'), ok: true },
        { label: t('plans.features.freeCloudSync'), ok: false },
        { label: t('plans.features.freeUnlimitedAccounts'), ok: false },
        { label: t('plans.features.freeStats'), ok: false },
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
        { label: t('plans.features.premiumAllFree'), ok: true },
        { label: t('plans.features.premiumUnlimitedAccounts'), ok: true },
        { label: t('plans.features.premiumCloudSync'), ok: true },
        { label: t('plans.features.premiumStats'), ok: true },
        { label: t('plans.features.premiumOneClickServer'), ok: false },
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
        { label: t('plans.features.ultimateAllPremium'), ok: true },
        { label: t('plans.features.ultimateCloudSync'), ok: true },
        { label: t('plans.features.ultimateOneClickServer'), ok: true },
        { label: t('plans.features.ultimateTunnel'), ok: true },
        { label: t('plans.features.ultimateEarlyAccess'), ok: true },
      ],
    },
  ]
}
