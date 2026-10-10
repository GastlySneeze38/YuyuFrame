package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Compteur d'images de {@code MinecraftClient} sur 1.21.11 — pour
 * {@code FpsModule}, via {@code AccessPoint.CLIENT_FPS}.
 *
 * <p>C'est le SEUL champ de cette classe qui demande un accessor ici : sur
 * cette version, {@code player}, {@code world}, {@code options}, {@code
 * currentScreen}, {@code inGameHud} et {@code mouse} sont PUBLICS (vérifié
 * javap), et {@code window}/{@code session}/{@code resourceManager} ont des
 * getters publics — {@code AccessorBindings1211} les atteint donc en code
 * TYPÉ, sans mixin. La 26.1.2, elle, a besoin d'un accessor pour tous.
 *
 * <p>{@code static} REQUIS : le champ l'est ({@code private static int
 * currentFps}), et Sponge exige la même staticité pour l'accesseur. Type
 * primitif, donc rien à typer contre une classe obfusquée — même contrainte
 * que {@code CameraAccessor1211}.
 *
 * <p>{@code @YarnNamed} : les accessors du changement de compte portent des
 * types du jeu ({@code Session}, {@code ProfileKeys}), que Mixin compare à la
 * lettre au type du champ obfusqué — voir {@code MinecraftClientAccessor189}.
 */
@com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface MinecraftClientAccessor1211 {

    @Accessor("currentFps")
    static int la$fps() { throw new AssertionError("MinecraftClientAccessor1211 non tissé"); }

    // ── Changement de compte en jeu (voir AccountSwitch1211) ───────────────
    // Quatre champs privés « final » que le jeu remplit une fois au démarrage,
    // à partir du compte : @Mutable lève le « final » au tissage. Chacun a son
    // entrée dans LauncherMixinService.REFMAP_ENTRIES ; aucun n'a d'homonyme
    // de même type à la casse près dans le jar (javap : W, av, aI, J).

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("session")
    void la$setSession(net.minecraft.client.session.Session session);

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("userApiService")
    void la$setUserApiService(com.mojang.authlib.minecraft.UserApiService service);

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("profileKeys")
    void la$setProfileKeys(net.minecraft.client.session.ProfileKeys keys);

    @org.spongepowered.asm.mixin.Mutable
    @Accessor("gameProfileFuture")
    void la$setGameProfileFuture(java.util.concurrent.CompletableFuture future);
}
