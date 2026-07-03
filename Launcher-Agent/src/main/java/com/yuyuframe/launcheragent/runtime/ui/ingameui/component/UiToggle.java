package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiAnimatedFloat;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.function.Consumer;

/** Switch booléen style OneConfig — piste arrondie + bouton rond qui glisse, transition animée (voir UiAnimatedFloat). */
public class UiToggle extends UiWidget {

    private static final float ANIM_SPEED = 14f;

    private boolean value;
    private final Consumer<Boolean> onChange;
    private final UiAnimatedFloat anim;

    public UiToggle(float x, float y, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, 44f, 24f);
        this.value = initial;
        this.onChange = onChange;
        this.anim = new UiAnimatedFloat(initial ? 1f : 0f, ANIM_SPEED);
    }

    public boolean value() { return value; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float t = anim.get();
        UiColor track = UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.ACCENT, t);
        renderer.drawRoundedRect(x, y, x + w, y + h, h / 2f, track, vpWidth, vpHeight);

        float knobD = h - 4f;
        float knobXOff = 2f + t * (w - knobD - 4f); // 2f (position OFF) -> w-knobD-2f (position ON)
        renderer.drawRoundedRect(x + knobXOff, y + 2f, x + knobXOff + knobD, y + h - 2f, knobD / 2f, UiTheme.TEXT_PRIMARY, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        value = !value;
        anim.setTarget(value ? 1f : 0f);
        if (onChange != null) onChange.accept(value);
    }
}
