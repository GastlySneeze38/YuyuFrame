package net.minecraft.client.sound;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code iqo}) — présente UNIQUEMENT pour
 * porter sa classe imbriquée {@link PlayResult}, type de retour de
 * {@link SoundManager#play}.
 *
 * <p>Ce type de retour n'est jamais lu ; il doit néanmoins être déclaré
 * exactement, parce que le DESCRIPTEUR fait partie de l'identité d'une méthode
 * pour la JVM. Déclarer {@code play} {@code void} produisait un appel
 * {@code (Lipm;)V} introuvable à l'exécution — l'erreur aurait été un
 * {@code NoSuchMethodError} au premier son.
 */
public class SoundSystem {

    protected SoundSystem() {
    }

    /** Nom Yarn {@code iqo$b} — poignée opaque, jamais lue. */
    public static class PlayResult {
        protected PlayResult() {
        }
    }
}
