/**
 * Fond des pages « vitrine » : trois lueurs radiales fixes et une grille très
 * discrète, comme sur le site (Server/Website/Frontend, `GlowBackground`).
 *
 * Volontairement sans flou ni animation : une tache `blur-[140px]` qui bouge
 * force le navigateur à recalculer un flou plein écran à chaque image, et le
 * défilement devient saccadé — le site est déjà passé par là.
 */
export function PageGlow() {
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
      style={{
        backgroundImage: [
          'radial-gradient(40rem 30rem at 0% 0%, rgb(75 63 207 / 0.18), transparent 70%)',
          'radial-gradient(44rem 34rem at 100% 35%, rgb(75 63 207 / 0.12), transparent 70%)',
          'radial-gradient(36rem 28rem at 30% 100%, rgb(96 84 230 / 0.10), transparent 70%)',
        ].join(', '),
      }}
    >
      <div className="absolute inset-0 bg-grid" />
    </div>
  )
}
