import { useT } from '@/i18n'

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  const t = useT()
  return (
    <div className="flex h-40 flex-col items-center justify-center gap-3">
      <span className="text-[13px] text-[rgba(255,255,255,0.3)]">{message}</span>
      <button onClick={onRetry} className="text-[12px] text-[#7872e8] underline">{t('common.retry')}</button>
    </div>
  )
}
