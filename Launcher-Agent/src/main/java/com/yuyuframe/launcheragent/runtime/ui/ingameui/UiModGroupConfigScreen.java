package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleGroup;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Les réglages d'un {@link ModuleGroup} : même cadre que {@link UiModConfigScreen}
 * ({@link UiConfigScreenBase}), avec UNE SECTION PAR {@link ModuleGroup.Tab}.
 * Chaque section porte l'interrupteur d'activation de ses modules (un module
 * groupé n'a pas de carte à lui pour le porter) et leurs réglages habituels.
 *
 * <p>Un onglet à un seul module met son interrupteur et son cœur de favori
 * dans l'en-tête de section ; un onglet qui en cumule plusieurs donne à chacun
 * une ligne de titre portant les siens.
 */
public class UiModGroupConfigScreen extends UiConfigScreenBase {

    private final ModuleGroup group;
    /**
     * Module sur lequel se placer à l'ouverture (ouvert depuis la carte d'un
     * module groupé favori), ou {@code null}. Appliqué UNE fois : un
     * redimensionnement ne doit pas ramener de force à ce module.
     */
    private final String focusModuleId;
    private boolean focusApplied;
    /** Ancre par MODULE (en plus de celles par onglet) : c'est elle qui permet d'ouvrir sur un module précis. */
    private LinkedHashMap<String, Float> moduleAnchors = new LinkedHashMap<>();

    public UiModGroupConfigScreen(Object lastScreen, ModuleGroup group) {
        this(lastScreen, group, null);
    }

    /** @param focusModule module sur lequel se positionner à l'ouverture, ou {@code null}. */
    public UiModGroupConfigScreen(Object lastScreen, ModuleGroup group, LauncherModule focusModule) {
        super(group.name, lastScreen);
        this.group = group;
        this.focusModuleId = focusModule != null ? focusModule.id : null;
    }

    @Override
    protected LinkedHashMap<String, Float> buildContent(UiScrollContainer scroll, float rowX, float rowW) {
        float rowH = px(34f), rowGap = px(UiTheme.Space.S8);
        float titleScale = px(UiTheme.Text.LABEL);
        float headerH = ConfigScreenBuilder.sectionHeaderHeight();
        float sectionGapBefore = ConfigScreenBuilder.sectionGapBeforeHeight();
        float headerToRowGap = ConfigScreenBuilder.headerToRowGapHeight();

        LinkedHashMap<String, Float> anchors = new LinkedHashMap<>();
        moduleAnchors = new LinkedHashMap<>();
        List<UiWidget> combined = new ArrayList<>();
        float globalCursor = 0f;
        boolean firstTab = true;

        for (ModuleGroup.Tab tab : group.tabs) {
            List<UiWidget> rows = new ArrayList<>();
            // Ancres de module en coordonnées locales à l'onglet, décalées comme les lignes plus bas.
            LinkedHashMap<String, Float> memberAnchorsLocal = new LinkedHashMap<>();
            float cursor = 0f;
            boolean multi = tab.modules.size() > 1;
            LauncherModule soloMember = (!multi && !tab.modules.isEmpty()) ? tab.modules.get(0) : null;

            for (LauncherModule member : tab.modules) {
                if (member == null) continue;
                if (multi) {
                    // Nom du module, cœur et interrupteur sur la même ligne : elle sert de titre au bloc.
                    float rowY = cursor - rowH;
                    rows.add(new UiLabel(rowX, UiText.centeredBaseline(UiFont.REGULAR, rowY, rowH, titleScale), Lang.tr(member.name),
                        UiTheme.TEXT_PRIMARY, titleScale));
                    float toggleX = rowX + rowW - px(44f) - px(UiTheme.Space.S12);
                    rows.add(new UiToggle(toggleX, rowY + (rowH - px(UiTheme.Space.S24)) / 2f, member.isEnabled(),
                        v -> { member.setEnabled(v); HudConfigStore.save(); }).boundTo(member::isEnabled));
                    float heartSize = px(UiTheme.Space.S20);
                    rows.add(new UiToggle(toggleX - heartSize - px(UiTheme.Space.S12), rowY + (rowH - heartSize) / 2f,
                        heartSize, heartSize, member.favorite,
                        v -> { member.favorite = v; HudConfigStore.save(); ModuleRegistry.markFavoritesChanged(); })
                        .heartStyle().boundTo(() -> member.favorite));
                    memberAnchorsLocal.put(member.id, rowY + rowH);
                    cursor = rowY - rowGap;
                }

                // Chaque catégorie du module part d'un curseur à 0 : on la
                // décale sous ce qui précède, puis on avance jusqu'à son bas.
                LinkedHashMap<String, List<UiWidget>> memberCategories = ConfigScreenBuilder.build(member, rowX, rowW);
                for (List<UiWidget> categoryRows : memberCategories.values()) {
                    if (categoryRows.isEmpty()) continue;
                    float shift = cursor;
                    float minY = Float.MAX_VALUE;
                    for (UiWidget w : categoryRows) {
                        w.y += shift;
                        if (w.y < minY) minY = w.y;
                    }
                    rows.addAll(categoryRows);
                    cursor = minY - rowGap;
                }
                // Entre deux modules d'un même onglet, un écart de plus.
                if (multi) cursor -= rowGap;
            }

            if (rows.isEmpty() && soloMember == null) continue;
            if (!firstTab) globalCursor -= sectionGapBefore;
            firstTab = false;

            globalCursor -= headerH;
            float headerY = globalCursor;
            UiWidget header = soloMember != null
                ? ConfigScreenBuilder.sectionHeaderWithToggleAndFavorite(rowX, headerY, rowW, headerH, tab.label,
                    soloMember::isEnabled, v -> { soloMember.setEnabled(v); HudConfigStore.save(); },
                    () -> soloMember.favorite, v -> { soloMember.favorite = v; HudConfigStore.save(); ModuleRegistry.markFavoritesChanged(); })
                : ConfigScreenBuilder.sectionHeader(rowX, headerY, rowW, headerH, tab.label);
            combined.add(header);
            anchors.put(tab.label, headerY + headerH);
            if (soloMember != null) moduleAnchors.put(soloMember.id, headerY + headerH);

            globalCursor -= headerToRowGap;
            float shift = globalCursor;
            for (UiWidget w : rows) {
                w.y += shift;
                combined.add(w);
            }
            for (Map.Entry<String, Float> e : memberAnchorsLocal.entrySet()) moduleAnchors.put(e.getKey(), e.getValue() + shift);
            globalCursor = shift + cursor;
        }

        for (UiWidget w : combined) scroll.add(w);
        return anchors;
    }

    @Override
    protected void afterBuild() {
        if (focusApplied || focusModuleId == null || scroll == null) return;
        Float anchor = moduleAnchors.get(focusModuleId);
        if (anchor != null) {
            scroll.scrollToAnchor(anchor);
            focusApplied = true;
        }
    }
}
