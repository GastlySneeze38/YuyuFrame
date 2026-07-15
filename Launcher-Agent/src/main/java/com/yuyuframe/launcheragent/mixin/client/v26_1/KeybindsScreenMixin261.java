package com.yuyuframe.launcheragent.mixin.client.v26_1;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Portage du bracket 1.21.11 (voir {@code KeybindsScreenMixin}, javadoc de
 * tête pour le pourquoi complet du point d'injection en TAIL du
 * constructeur) pour MC 26.1+ :
 * {@code net.minecraft.client.gui.screen.option.KeybindsScreen} devient
 * {@code net.minecraft.client.gui.screens.options.controls.KeyBindsScreen}
 * (renommage ET changement de package plus profond — "screen"→"screens",
 * "KeybindsScreen"→"KeyBindsScreen" avec un B majuscule, dossier
 * "options/controls/" ajouté) ; {@code GameOptions} devient {@code Options}.
 * Constructeur vérifié via {@code javap} sur le jar client 26.1.2 réel :
 * {@code public KeyBindsScreen(Screen, Options);}.
 *
 * BUG TROUVÉ (crash rapporté par un beta-testeur, 26.1.2) : {@code
 * CustomKeybindsScreen} (construit ci-dessous jusqu'à ce correctif) dépend
 * ENTIÈREMENT de {@code ScreenHelper}/{@code KeybindReflect} — l'ANCIEN
 * système (voir la javadoc de {@code ModrinthContentScreen}, qui documente
 * exactement le même piège déjà rencontré et abandonné pour 1.8.9 :
 * "ClassNotFoundException('yh') dans ScreenHelper.literal()") qui code en
 * DUR les lettres obfusquées OFFICIELLES du bracket 1.21.11 (ex: {@code
 * CLS_TEXT="yh"}, {@code CLS_MINECRAFT_CLIENT="gfj"}) SANS repli vers les
 * vrais noms Mojang — cassé sur TOUT bracket où le jeu n'est pas obfusqué
 * de cette façon précise, dont 26.1.2 (non obfusqué du tout, voir
 * VersionBracketRegistry § bracket "E"). Résultat en jeu : {@code
 * ClassNotFoundException} en cascade ("yh"/"gfj"/"gfh"/"gfh$a"), écran des
 * touches vide (0 catégorie, 0 touche) voire blocage selon le moment où
 * l'exception survient dans le cycle de vie de l'écran (certains appels ne
 * sont PAS protégés par le try/catch ci-dessous, notamment {@code init()}
 * appelé PLUS TARD par vanilla, hors de ce hook). Le seul autre bracket qui
 * atteint ce code (1.21.11, via {@code KeybindsScreenMixin} — voir sa
 * javadoc) reste inchangé : {@code ScreenHelper} y fonctionne bel et bien,
 * ses lettres correspondent EXACTEMENT à cette version. La javadoc "Vérifié
 * en jeu (26.1.2)" ci-dessus était donc soit obsolète soit basée sur une
 * version antérieure de {@code CustomKeybindsScreen} pas encore couplée à
 * {@code ScreenHelper} de cette façon.
 *
 * Fix IMMÉDIAT (pas une réécriture complète de CustomKeybindsScreen vers le
 * pipeline UiScreenBase/UiWidget — hors scope d'un correctif de crash,
 * voir ModrinthContentScreen pour cette migration déjà faite ailleurs) :
 * ce hook ne fait PLUS RIEN sur ce bracket — l'écran Contrôles VANILLA
 * (jamais cassé, juste moins personnalisé) reste affiché tel quel au lieu
 * d'un remplacement qui plantait.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.options.controls.KeyBindsScreen")
public abstract class KeybindsScreenMixin261 {

    @Inject(
        method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;)V",
        at = @At("TAIL")
    )
    private void la$onInit(CallbackInfo ci) {
        // Désactivé — voir javadoc de classe (ScreenHelper/KeybindReflect
        // non portés pour ce bracket, CustomKeybindsScreen cassé sur 26.1.2).
    }
}
