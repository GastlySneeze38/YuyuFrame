package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Slider numérique glissable — tout l'état de drag est géré dans
 * pollContinuous (appelé CHAQUE frame par UiScreenBase/UiScrollContainer, pas
 * seulement au clic), pour que le glissement continue même quand le curseur
 * sort des bounds du widget pendant le drag (UX standard d'un slider).
 *
 * La piste occupe {@code w - READOUT_W} : le reste est réservé à l'affichage
 * de la valeur courante (entier si step>=1, sinon 1 décimale), pour ne pas
 * avoir à deviner la position exacte du curseur visuellement.
 */
public class UiSlider extends UiWidget {

    // Non static (contrairement à l'habitude) — dépendent de UiTheme.UI_SCALE
    // au moment de la construction (voir GlobalUiSettings, réglage "Taille de
    // l'interface"), donc figées PAR INSTANCE plutôt que partagées telles
    // quelles à tous les sliders sans distinction d'échelle.
    private final float TRACK_H = UiTheme.scaled(5f);
    private final float KNOB_R = UiTheme.scaled(8f);
    private final float READOUT_W = UiTheme.scaled(44f);
    private final float textScale = UiTheme.scaled(0.48f);

    private final float min, max, step;
    private float value;
    private final Consumer<Float> onChange;
    private boolean dragging;
    // Hover animé ajouté (voir audit runtime/ui/ : seul composant de réglage
    // à n'avoir aucune trace d'animation) — le curseur grossit légèrement et
    // la piste remplie s'éclaircit au survol/pendant le glissement, même
    // motif que UiButton/UiDropdown.
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    /**
     * {@code null} (défaut) = toujours actif. Sinon interrogé À CHAQUE
     * frame (voir {@link ConfigScreenBuilder}, "Sensibilité personnalisée"
     * du freelook grisée tant que "Sensibilité" n'est pas réglée sur
     * "Personnalisée") — permet au curseur de refléter LIVE un changement
     * fait ailleurs dans le MÊME écran (ex: un dropdown juste au-dessus),
     * sans reconstruire tout l'écran de config.
     */
    private final BooleanSupplier enabledSupplier;

    public UiSlider(float x, float y, float w, float min, float max, float step, float initial, Consumer<Float> onChange) {
        this(x, y, w, min, max, step, initial, onChange, null);
    }

    public UiSlider(float x, float y, float w, float min, float max, float step, float initial, Consumer<Float> onChange,
                     BooleanSupplier enabledSupplier) {
        super(x, y, w, UiTheme.scaled(20f));
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clamp(initial);
        this.onChange = onChange;
        this.enabledSupplier = enabledSupplier;
    }

    private boolean enabled() { return enabledSupplier == null || enabledSupplier.getAsBoolean(); }

    public float value() { return value; }

    private float clamp(float v) {
        float c = Math.max(min, Math.min(max, v));
        if (step > 0f) c = min + Math.round((c - min) / step) * step;
        return c;
    }

    private float trackW() { return w - READOUT_W; }

    private float ratio() { return max > min ? (value - min) / (max - min) : 0f; }

    private String formatValue() {
        return step >= 1f ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        boolean enabled = enabled();
        hoverAnim.setTarget(enabled && (dragging || contains(mouseX, mouseY)) ? 1f : 0f);
        float hoverT = hoverAnim.get();

        float trackW = trackW();
        float cy = y + h / 2f;
        renderer.drawRoundedRect(x, cy - TRACK_H / 2f, x + trackW, cy + TRACK_H / 2f, TRACK_H / 2f, UiTheme.TRACK_OFF, vpWidth, vpHeight);
        float knobX = x + ratio() * trackW;
        // Grisé (voir #enabledSupplier) : piste remplie ET curseur passent
        // en TEXT_MUTED au lieu de l'accent/blanc habituels — même signal
        // visuel qu'un contrôle HTML disabled, pas juste un survol qui ne
        // répond plus (aurait pu passer pour un bug plutôt qu'un état voulu).
        UiColor fillColor = enabled ? UiColor.lerp(UiTheme.ACCENT, UiTheme.accentLight(), hoverT) : UiTheme.TRACK_OFF;
        renderer.drawRoundedRect(x, cy - TRACK_H / 2f, knobX, cy + TRACK_H / 2f, TRACK_H / 2f, fillColor, vpWidth, vpHeight);
        float knobR = KNOB_R * (1f + hoverT * 0.25f);
        renderer.drawRoundedRect(knobX - knobR, cy - knobR, knobX + knobR, cy + knobR, knobR,
            enabled ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, vpWidth, vpHeight);

        String text = formatValue();
        float tw = renderer.textWidth(text, textScale);
        renderer.drawText(text, x + w - tw, cy - UiTheme.scaled(5f), enabled ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_MUTED, textScale, vpWidth, vpHeight);
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!enabled()) { dragging = false; return; }
        if (!dragging) {
            if (input.leftClicked && contains(input.mouseX, input.mouseY)) dragging = true;
            else return;
        }
        if (!input.leftDown) { dragging = false; return; }

        float trackW = trackW();
        float r = (float) ((input.mouseX - x) / trackW);
        r = Math.max(0f, Math.min(1f, r));
        float newValue = clamp(min + r * (max - min));
        if (newValue != value) {
            value = newValue;
            if (onChange != null) onChange.accept(value);
        }
    }
}
