package com.yuyuframe.launcheragent.apimixin.v26_1_2.core;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor Sponge pour {@code Options.fov}/{@code Options.sensitivity}/{@code Options.gamma} (privés) — voir {@code ZoomModule}/{@code FullbrightModule}. */
@Mixin(targets = "net.minecraft.client.Options")
public interface OptionsAccessor261 {
    @Accessor("fov")
    OptionInstance la$fov();

    @Accessor("sensitivity")
    OptionInstance la$sensitivity();

    /** Ajouté pour {@code FullbrightModule} (2026-08-26, §22) — {@code OptionInstance<Double>} (vérifié javap), même famille que fov/sensitivity. */
    @Accessor("gamma")
    OptionInstance la$gamma();

    /**
     * {@code true} quand le joueur a masqué l'interface (F1).
     *
     * <p>Ajouté le 2026-08-31 pour {@code HudOverlayRenderer.vanillaHudHidden},
     * qui le lisait par réflexion à chaque frame — dernier accès réflexif du
     * chemin de rendu du HUD. Le champ est PUBLIC et un accès direct
     * compilerait ; il passe quand même par un accessor, comme
     * {@code Minecraft.player} et {@code level}, pour que tout l'accès à
     * l'état du jeu traverse une seule interface par bracket.
     *
     * <p>Nom réel {@code hideGui} (vérifié sur {@code Options.class} du jar
     * 26.1.2) — Yarn l'appelait {@code hudHidden}.
     */
    @Accessor("hideGui")
    boolean la$hideGui();
}
