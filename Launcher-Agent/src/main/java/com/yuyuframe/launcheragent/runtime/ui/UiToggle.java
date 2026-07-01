package com.yuyuframe.launcheragent.runtime.ui;

import java.util.function.Consumer;

/** Switch booléen style OneConfig — piste arrondie + bouton rond qui glisse. */
public class UiToggle extends UiWidget {

    private boolean value;
    private final Consumer<Boolean> onChange;

    public UiToggle(float x, float y, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, 34f, 18f);
        this.value = initial;
        this.onChange = onChange;
    }

    public boolean value() { return value; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        UiColor track = value ? UiTheme.ACCENT : UiTheme.TRACK_OFF;
        renderer.drawRoundedRect(x, y, x + w, y + h, h / 2f, track, vpWidth, vpHeight);

        float knobD = h - 4f;
        float knobX = value ? (x + w - knobD - 2f) : (x + 2f);
        renderer.drawRoundedRect(knobX, y + 2f, knobX + knobD, y + h - 2f, knobD / 2f, UiTheme.TEXT_PRIMARY, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        value = !value;
        if (onChange != null) onChange.accept(value);
    }
}
