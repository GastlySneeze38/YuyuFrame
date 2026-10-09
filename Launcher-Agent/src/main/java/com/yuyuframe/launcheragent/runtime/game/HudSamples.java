package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apigraphic.hud.HudSampleIcon;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.data.PlayerEffect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les données d'exemple de l'éditeur de HUD : ce que {@link PlayerData} rend
 * à la place du vide tant que l'aperçu est levé
 * ({@code apigraphic.hud.HudPreview}) — une armure complète, quelques effets,
 * un ping, une position. De quoi voir chaque élément à sa taille réelle sans
 * avoir à s'équiper, ni même à être dans un monde.
 *
 * <p>Les icônes des objets sont les vraies textures du jeu, lues par le
 * gestionnaire de ressources (elles suivent donc le pack actif) : un objet
 * d'exemple n'a pas de pile du jeu à dessiner.
 */
final class HudSamples {

    private HudSamples() {
    }

    static final int PING = 42;

    /** Quelque part dans des plaines, face au nord. */
    static final double[] POSITION = { 128.5, 64.0, -256.5 };
    static final float YAW = 180f;

    // ── Équipement ───────────────────────────────────────────────────────────

    private static ItemInfo[] equipment;

    /** Tête, torse, jambes, pieds, main principale, main secondaire — l'ordre de {@code PlayerData.equipment()}. */
    static ItemInfo[] equipment() {
        if (equipment == null) {
            equipment = new ItemInfo[]{
                item("diamond_helmet", 363, 41),
                item("diamond_chestplate", 528, 96),
                // Presque cassées : montre aussi la couleur d'alerte de la durabilité.
                item("diamond_leggings", 495, 431),
                item("diamond_boots", 429, 12),
                item("diamond_sword", 1561, 320),
                item("bow", 384, 150),
            };
        }
        return equipment;
    }

    private static ItemInfo item(String name, int maxDamage, int damage) {
        return new ItemInfo(new HudSampleIcon("yf-sample-item:" + name, () -> texture(name)), false, 1, true, damage, maxDamage);
    }

    /** Textures déjà lues ; une texture introuvable est retenue comme telle ({@code null}) pour ne pas être relue à chaque image. */
    private static final Map<String, BufferedImage> TEXTURES = new HashMap<>();

    private static BufferedImage texture(String name) {
        if (TEXTURES.containsKey(name)) {
            return TEXTURES.get(name);
        }
        BufferedImage image = null;
        try {
            // « item » depuis la 1.13, « items » avant.
            byte[] bytes = ClientData.resourceBytes("minecraft", "textures/item/" + name + ".png");
            if (bytes == null) {
                bytes = ClientData.resourceBytes("minecraft", "textures/items/" + name + ".png");
            }
            if (bytes != null) {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            }
        } catch (Throwable error) {
            LauncherLog.err("[HudSamples] texture d'exemple illisible : " + name, error);
        }
        TEXTURES.put(name, image);
        return image;
    }

    // ── Effets ───────────────────────────────────────────────────────────────

    private static List<PlayerEffect> effects;

    static List<PlayerEffect> effects() {
        if (effects == null) {
            List<PlayerEffect> list = new ArrayList<>();
            // Couleurs de vanilla pour ces effets ; durées en ticks.
            list.add(new PlayerEffect("Speed", 1, 90 * 20, false, 0x33EBFF, true, "minecraft:speed"));
            list.add(new PlayerEffect("Strength", 0, 45 * 20, false, 0xFFC700, true, "minecraft:strength"));
            list.add(new PlayerEffect("Regeneration", 0, 12 * 20, false, 0xCD5CAB, true, "minecraft:regeneration"));
            list.add(new PlayerEffect("Poison", 0, 8 * 20, false, 0x87A363, false, "minecraft:poison"));
            effects = Collections.unmodifiableList(list);
        }
        return effects;
    }
}
