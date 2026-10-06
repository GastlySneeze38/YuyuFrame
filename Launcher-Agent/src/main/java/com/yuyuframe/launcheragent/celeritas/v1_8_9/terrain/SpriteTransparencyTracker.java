package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain;

import com.yuyuframe.launcheragent.celeritas.util.color.ColorARGB;
import com.yuyuframe.launcheragent.celeritas.chunk.sprite.SpriteTransparencyLevel;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.mixin.MinecraftAccessor;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.Arrays;

public class SpriteTransparencyTracker {
    private static final int SPRITE_COUNT = 256;
    private static final int DEFAULT_SPRITE_WIDTH = 16;
    private static final int NUM_SPRITES_PER_ROW = 16;

    //? if <1.5 {

    private final SpriteTransparencyLevel[] levels = new SpriteTransparencyLevel[SPRITE_COUNT];

    public SpriteTransparencyTracker() {
        //? if >=1.3 {
        //var selectedPack = MinecraftAccessor.celeritas$getInstance().texturePacks.getSelected();
        //?} else
        var selectedPack = MinecraftAccessor.celeritas$getInstance().texturePacks.selected;
        try(var terrainFile = selectedPack.getResource("/terrain.png")) {
            var terrainImage = ImageIO.read(terrainFile);
            int[] contents = terrainImage.getRGB(0, 0, terrainImage.getWidth(), terrainImage.getHeight(), null, 0, terrainImage.getWidth());
            analyzeContents(contents, terrainImage.getWidth(), terrainImage.getHeight());
        } catch (IOException e) {
            e.printStackTrace();
            Arrays.fill(levels, SpriteTransparencyLevel.TRANSPARENT);
        }
    }

    private void analyzeContents(int[] contents, int atlasWidth, int atlasHeight) {
        int spriteWidth = atlasWidth / DEFAULT_SPRITE_WIDTH;
        for (int i = 0; i < SPRITE_COUNT; i++) {
            int ptr = (i / DEFAULT_SPRITE_WIDTH) * (atlasWidth * spriteWidth) + (i % DEFAULT_SPRITE_WIDTH) * spriteWidth;
            SpriteTransparencyLevel level = SpriteTransparencyLevel.OPAQUE;
            for (int y = 0; y < spriteWidth; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    int alpha = ColorARGB.unpackAlpha(contents[ptr]);
                    if (alpha < 255) {
                        level = level.chooseNextLevel(alpha == 0 ? SpriteTransparencyLevel.TRANSPARENT : SpriteTransparencyLevel.TRANSLUCENT);
                    }
                    ptr++;
                }
                ptr += atlasWidth - spriteWidth;
            }
            levels[i] = level;
        }
    }

    public SpriteTransparencyLevel getTransparencyLevel(int spriteId) {
        return levels[spriteId];
    }

    //?}
}
