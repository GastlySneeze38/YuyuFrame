package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

import java.util.function.Consumer;

/**
 * Slider numérique glissable — tout l'état de drag est géré dans
 * pollContinuous (appelé CHAQUE frame par UiScreenBase/UiScrollContainer, pas
 * seulement au clic), pour que le glissement continue même quand le curseur
 * sort des bounds du widget pendant le drag (UX standard d'un slider).
 */
public class UiSlider extends UiWidget {

    private static final float TRACK_H = 4f;
    private static final float KNOB_R = 6f;

    private final float min, max, step;
    private float value;
    private final Consumer<Float> onChange;
    private boolean dragging;

    public UiSlider(float x, float y, float w, float min, float max, float step, float initial, Consumer<Float> onChange) {
        super(x, y, w, 16f);
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clamp(initial);
        this.onChange = onChange;
    }

    public float value() { return value; }

    private float clamp(float v) {
        float c = Math.max(min, Math.min(max, v));
        if (step > 0f) c = min + Math.round((c - min) / step) * step;
        return c;
    }

    private float ratio() { return max > min ? (value - min) / (max - min) : 0f; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float cy = y + h / 2f;
        renderer.drawRoundedRect(x, cy - TRACK_H / 2f, x + w, cy + TRACK_H / 2f, TRACK_H / 2f, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        float knobX = x + ratio() * w;
        renderer.drawRoundedRect(x, cy - TRACK_H / 2f, knobX, cy + TRACK_H / 2f, TRACK_H / 2f, UiTheme.ACCENT, vpWidth, vpHeight);
        renderer.drawRoundedRect(knobX - KNOB_R, cy - KNOB_R, knobX + KNOB_R, cy + KNOB_R, KNOB_R, UiTheme.TEXT_PRIMARY, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!dragging) {
            if (input.leftClicked && contains(input.mouseX, input.mouseY)) dragging = true;
            else return;
        }
        if (!input.leftDown) { dragging = false; return; }

        float r = (float) ((input.mouseX - x) / w);
        r = Math.max(0f, Math.min(1f, r));
        float newValue = clamp(min + r * (max - min));
        if (newValue != value) {
            value = newValue;
            if (onChange != null) onChange.accept(value);
        }
    }
}
