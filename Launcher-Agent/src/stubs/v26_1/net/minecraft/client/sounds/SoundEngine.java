package net.minecraft.client.sounds;

/**
 * Stub compile-only (26.1+) — existe UNIQUEMENT pour porter le type de retour
 * de {@link SoundManager#play}.
 *
 * <p>BUG TROUVÉ (2026-08-27, log de jeu) : le stub de {@code SoundManager}
 * déclarait {@code void play(SoundInstance)} alors que la vraie méthode
 * renvoie {@code SoundEngine$PlayResult}. Le type de retour fait partie du
 * descripteur d'un {@code invokevirtual} — l'appel compilé cherchait donc
 * {@code play(...)V}, qui n'existe pas, d'où :
 * <pre>NoSuchMethodError: 'void net.minecraft.client.sounds.SoundManager.play(...SoundInstance)'</pre>
 * Le ping de mention de {@code ChatEnhancementsModule} ne fonctionnait que
 * grâce au repli réflexif (qui, lui, résout par nom + paramètres seulement) ;
 * sa suppression a rendu la panne visible.
 *
 * <p>Classe imbriquée réelle vérifiée dans le jar client 26.1.2
 * ({@code net/minecraft/client/sounds/SoundEngine$PlayResult.class}). Sa
 * nature exacte (enum ou classe) est sans importance ici : seul le NOM du
 * type entre dans le descripteur.
 */
public abstract class SoundEngine {

    /** Résultat de {@link SoundManager#play} — jamais lu par l'agent, présent pour que le descripteur d'appel soit exact. */
    public static final class PlayResult {
        private PlayResult() {}
    }
}
