package com.mojang.authlib;

/**
 * Stub compile-only — {@code com.mojang.authlib.GameProfile}, bibliothèque
 * fournie avec le jeu (présente au runtime, absente du classpath de
 * compilation de l'agent).
 *
 * <p>Créé le 2026-08-27 pour deux raisons liées :
 * <ol>
 *   <li>{@code Player.getGameProfile()} renvoie ce type — le stub de
 *       {@code LocalPlayer} le déclarait en {@code Object}, ce qui produit un
 *       descripteur d'appel {@code ()Ljava/lang/Object;} introuvable au
 *       runtime ({@code NoSuchMethodError}), même mode de panne que
 *       {@code SoundManager.play} ;</li>
 *   <li>faute de ce type, {@code MumbleLinkModule} lisait le pseudo par
 *       réflexion ({@code getClass().getMethod("getName")}) — c'était la
 *       dernière réflexion du module, elle disparaît avec ce stub.</li>
 * </ol>
 *
 * <p>Signature vérifiée sur {@code com/mojang/authlib/GameProfile.class} —
 * dans {@code libraries/com/mojang/authlib/9.0.75/authlib-9.0.75.jar}, PAS
 * dans le jar client : authlib est une bibliothèque distincte.
 */
public final class GameProfile {
    private GameProfile() {}

    /**
     * ATTENTION : {@code name()}, PAS {@code getName()} — {@code GameProfile}
     * est devenu un RECORD (champs {@code id}/{@code name}/{@code properties},
     * vérifié sur {@code authlib-9.0.75.jar}). L'ancien code réflexif de
     * {@code MumbleLinkModule} cherchait {@code getName()} et échouait donc
     * silencieusement : le pseudo transmis à Mumble était toujours nul.
     */
    public String name() { return null; }
}
