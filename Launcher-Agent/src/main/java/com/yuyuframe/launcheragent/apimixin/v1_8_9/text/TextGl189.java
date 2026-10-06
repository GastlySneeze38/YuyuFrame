package com.yuyuframe.launcheragent.apimixin.v1_8_9.text;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;

/**
 * Refait l'appel à {@code GlStateManager.color} que
 * {@link TextRendererBatchMixin189} a détourné. Code typé, traduit au
 * chargement : ni le mixin ni {@link TextBatch189} ne nomment de type du jeu.
 */
@YarnNamed
public final class TextGl189 {

    private TextGl189() {
    }

    public static void color(float red, float green, float blue, float alpha) {
        GlStateManager.color(red, green, blue, alpha);
    }
}
