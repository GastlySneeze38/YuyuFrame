package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recherche/installation de resource packs Modrinth — dessiné avec NOTRE
 * pipeline graphique (UiScreenBase/UiRenderer/UiWidget), PAS l'ancien
 * {@code screen.ResourcePackSearchScreen} (celui-là compile contre les stubs
 * Screen/Text vanilla et s'appuie sur {@code ScreenHelper}, qui code en dur
 * les noms de classes obfusquées 1.21.11 — cassé sur 1.8.9, confirmé en jeu :
 * NoSuchMethodError sur le constructeur ET ClassNotFoundException("yh") dans
 * ScreenHelper.literal(). Notre propre pipeline est DÉJÀ version-générique
 * (UiMainMenuScreen tourne identiquement sur 1.8.9 et 1.21.11), donc écrire
 * l'écran ainsi évite tout ce problème de façon définitive plutôt que de
 * porter ScreenHelper vers 1.8.9.
 *
 * L'ancien écran ({@code screen.ResourcePackSearchScreen}/{@code ResourcePackDetailScreen})
 * reste en place pour l'instant (ouvert depuis PackScreen vanilla via
 * PackScreenMixin) mais est amené à être supprimé plus tard — ce fichier est
 * volontairement dans {@code runtime/module/} (pas {@code runtime/ui/ingameui/}
 * comme les autres écrans) à la demande explicite de l'utilisateur, pour
 * marquer que c'est CE remplacement-ci qui doit devenir la seule voie d'accès.
 *
 * v1 volontairement plus simple que l'ancien écran : pas d'icônes (le
 * téléchargement/enregistrement de texture d'icône vanilla est lui aussi
 * version-spécifique côté rendu — hors scope ici), recherche/installation
 * sur un thread daemon séparé, résultat consommé une seule fois par le
 * thread de rendu via {@link #pendingResults} (volatile) pour ne jamais
 * muter les listes de widgets depuis un thread autre que celui qui les
 * itère dans uiDraw()/uiPollInput().
 */
public class ModrinthResourcePackScreen extends UiScreenBase {

    private static final int MAX_RESULTS = 24;

    private final Object lastScreen;
    private final Path resourcePacksDir;

    private UiTextField searchField;
    private UiScrollContainer results;
    private boolean layoutBuilt;

    private volatile boolean busy;
    private volatile String statusText = "";
    private volatile List<ModrinthJson.Hit> pendingResults;
    private List<ModrinthJson.Hit> shownResults = Collections.emptyList();
    // projectId en cours de téléchargement — piloté par le thread d'install,
    // lu par ResultCard.draw() (thread de rendu) pour animer le spinner. Un
    // seul projectId à la fois (busy bloque déjà tout nouveau déclenchement
    // pendant qu'une install est en cours, voir triggerInstall).
    private volatile String installingProjectId;

    public ModrinthResourcePackScreen(Object lastScreen) {
        super("Modrinth");
        this.lastScreen = lastScreen;
        // Même convention que HudConfigStore/launcher.rs (user.dir = dossier
        // de travail du process Java = dossier de l'instance) — pas besoin de
        // lire un champ Path sur un écran vanilla PackScreen qui n'existe pas
        // ici (voir javadoc de la classe).
        this.resourcePacksDir = Paths.get(System.getProperty("user.dir", "."), "resourcepacks");
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (results != null) results.pollInput(input);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (!layoutBuilt && screenWidth > 0 && screenHeight > 0) {
            buildLayout();
            layoutBuilt = true;
        }
        // Consommé UNE SEULE FOIS par le thread de rendu — jamais muté
        // directement depuis le thread de recherche (voir javadoc de la classe).
        List<ModrinthJson.Hit> results0 = pendingResults;
        if (results0 != null) {
            pendingResults = null;
            showResults(results0);
        }

        super.uiDraw(mouseX, mouseY);
        try {
            UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
            renderer.drawText(UiFont.BOLD, "Modrinth — Resource Packs", MARGIN, screenHeight - 48,
                UiTheme.TEXT_PRIMARY, 0.8f, screenWidth, screenHeight);
            String status = statusText;
            if (!status.isEmpty()) {
                // 40px sous la barre de recherche (searchY = screenHeight-120-SEARCH_H),
                // encore 40px au-dessus du haut de la liste (screenHeight-HEADER_H) —
                // marge généreuse des deux côtés (voir HEADER_H, revu suite au
                // chevauchement statut/1re carte confirmé en jeu).
                renderer.drawText(status, MARGIN, screenHeight - 190, UiTheme.TEXT_SECONDARY, 0.42f, screenWidth, screenHeight);
            }
            if (results != null) results.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthResourcePackScreen] uiDraw: " + t);
        }
    }

    // Constantes d'agencement — regroupées ici (au lieu de littéraux éparpillés)
    // suite au premier jet jugé "pas beau"/trop tassé en haut à gauche (titre/
    // Retour/recherche qui se chevauchaient, texte minuscule sur un écran
    // entier vide) : tout est maintenant nettement plus grand et espacé.
    private static final float MARGIN = 28f;
    // Titre / barre de recherche / statut, réservé au-dessus de la liste — vu en
    // jeu que 168 était trop juste une fois les textes agrandis (chevauchement
    // statut/1re carte), largement augmenté avec des bandes bien séparées.
    private static final float HEADER_H = 230f;
    private static final float BACK_W = 110f, BACK_H = 34f;
    private static final float SEARCH_H = 40f;
    private static final float SEARCH_BTN_W = 130f;
    // Largeur de la scrollbar (6) + sa marge (4, voir UiScrollContainer) +
    // marge supplémentaire pour ne pas la coller au bord de la carte.
    private static final float SCROLLBAR_CLEARANCE = 24f;

    private void buildLayout() {
        widgets.clear();
        // "Retour" en HAUT-DROITE — l'ancien placement (haut-gauche, sous le
        // titre) chevauchait littéralement le titre (confirmé en jeu).
        widgets.add(new UiButton(screenWidth - MARGIN - BACK_W, screenHeight - MARGIN - BACK_H, BACK_W, BACK_H,
            "Retour", () -> closeTo(lastScreen)));

        // Bandes clairement séparées : titre ~60px, marge, recherche ~40px,
        // marge, statut ~20px, MARGE GÉNÉREUSE avant le haut de la liste.
        float searchY = screenHeight - 120 - SEARCH_H;
        float searchW = Math.min(520f, screenWidth - MARGIN * 2 - SEARCH_BTN_W - 16f);
        searchField = new UiTextField(MARGIN, searchY, searchW, SEARCH_H, "Rechercher un resource pack...", null);
        widgets.add(searchField);
        widgets.add(new UiButton(MARGIN + searchW + 16f, searchY, SEARCH_BTN_W, SEARCH_H, "Chercher", this::triggerSearch));

        results = new UiScrollContainer(MARGIN, MARGIN, screenWidth - MARGIN * 2, screenHeight - MARGIN - HEADER_H);
        triggerSearch();
    }

    // ── Recherche ──────────────────────────────────────────────────────────────

    private void triggerSearch() {
        if (busy) return;
        String query = searchField != null ? searchField.text().trim() : "";
        busy = true;
        statusText = query.isEmpty() ? "Recommandations en cours..." : "Recherche en cours...";

        Thread t = new Thread(() -> doSearch(query), "LauncherAgent-ModrinthSearch");
        t.setDaemon(true);
        t.start();
    }

    private void doSearch(String query) {
        try {
            if (!ContentBridge.ensureLoaded()) {
                statusText = "content_core.dll indisponible";
                return;
            }
            String json = ContentBridge.searchModrinth(query, "resourcepack");
            String error = ModrinthJson.jsonString(json, "error");
            if (error != null) {
                statusText = "Erreur Modrinth : " + error;
                return;
            }
            List<ModrinthJson.Hit> hits = ModrinthJson.parseHits(json, MAX_RESULTS);
            pendingResults = hits;
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthResourcePackScreen] doSearch: " + t);
            statusText = "Erreur recherche : " + t;
        } finally {
            busy = false;
        }
    }

    /** Reconstruit la liste de widgets résultat — appelé UNIQUEMENT depuis le thread de rendu (voir uiDraw). */
    private void showResults(List<ModrinthJson.Hit> hits) {
        shownResults = hits;
        statusText = hits.isEmpty() ? "Aucun résultat" : hits.size() + " résultat(s)";

        results.clear();
        Set<String> installed = scanInstalledNormalizedNames();
        // rowH/rowGap augmentés — la description (près du bas de la carte)
        // débordait dans l'écart entre cartes avec les anciennes valeurs plus
        // serrées (chevauchement confirmé en jeu).
        float rowH = 128f;
        float rowGap = 26f;
        // La carte occupait TOUTE la largeur du viewport, jusque dans la zone
        // où UiScrollContainer dessine sa scrollbar (tx = vx+vw-10) — les deux
        // se chevauchaient forcément (confirmé en jeu). SCROLLBAR_CLEARANCE
        // réserve la place, que la scrollbar soit visible ou non (peu de
        // résultats = pas de scrollbar, mais la carte doit rester à la même
        // largeur pour ne pas "sauter" visuellement selon le nombre de résultats).
        float rowW = screenWidth - MARGIN * 2 - SCROLLBAR_CLEARANCE;
        float top = -8f;
        for (int i = 0; i < hits.size(); i++) {
            ModrinthJson.Hit hit = hits.get(i);
            float rowY = top - i * (rowH + rowGap) - rowH;
            boolean already = isInstalled(hit, installed);
            results.add(new ResultCard(MARGIN, rowY, rowW, rowH, hit, already));
        }
    }

    private static String formatDownloads(long n) {
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    /** Tronque {@code text} (avec "...") pour tenir dans {@code maxWidth} pixels à l'échelle donnée — sinon un titre/description long déborde par-dessus le bouton Installer. */
    private static String truncate(UiRenderer renderer, String text, float scale, float maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0 || renderer.textWidth(text, scale) <= maxWidth) return text;
        String ellipsis = "...";
        int len = text.length();
        while (len > 0 && renderer.textWidth(text.substring(0, len) + ellipsis, scale) > maxWidth) len--;
        return len <= 0 ? ellipsis : text.substring(0, len) + ellipsis;
    }

    /**
     * Carte de résultat façon page Modrinth (icône, titre, auteur/stats,
     * description, bouton Installer/Installé) — remplace les simples lignes
     * de texte de la v1 (jugées "pas belles"). Le bouton est la SEULE zone
     * cliquable de la carte (voir contains()) : pas d'action de clic sur le
     * reste de la carte pour l'instant, juste un fond décoratif cohérent
     * avec ModCard (UiMainMenuScreen).
     */
    private final class ResultCard extends UiWidget {
        private static final float BTN_W = 130f, BTN_H = 34f;
        private final ModrinthJson.Hit hit;
        private final boolean alreadyInstalled;

        ResultCard(float x, float y, float w, float h, ModrinthJson.Hit hit, boolean alreadyInstalled) {
            super(x, y, w, h);
            this.hit = hit;
            this.alreadyInstalled = alreadyInstalled;
        }

        private float btnX() { return x + w - BTN_W - 12f; }
        private float btnY() { return y + (h - BTN_H) / 2f; }
        private boolean installing() { return hit.projectId.equals(installingProjectId); }

        @Override
        public boolean contains(double mx, double my) {
            if (alreadyInstalled || installing()) return false;
            float bx = btnX(), by = btnY();
            return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
        }

        @Override
        public void onClick() {
            if (!alreadyInstalled && !installing()) triggerInstall(hit);
        }

        @Override
        public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, UiTheme.CARD_BG, vpWidth, vpHeight);

            // Icône plafonnée (pas juste h-24) — sinon elle grossit à l'infini
            // avec la hauteur de carte et écrase visuellement le texte.
            float iconSize = Math.min(h - 32f, 84f);
            float iconY = y + (h - iconSize) / 2f;
            renderer.drawRoundedRect(x + 14, iconY, x + 14 + iconSize, iconY + iconSize,
                UiTheme.RADIUS_SM, UiTheme.ACCENT_DIM, vpWidth, vpHeight);
            String initial = (hit.title == null || hit.title.isEmpty()) ? "?" : hit.title.substring(0, 1).toUpperCase(Locale.ROOT);
            float iw = renderer.textWidth(initial, 0.75f);
            renderer.drawText(initial, x + 14 + (iconSize - iw) / 2f, iconY + iconSize / 2f - 9f,
                UiTheme.ACCENT, 0.75f, vpWidth, vpHeight);

            float textX = x + 14 + iconSize + 18;
            float textMaxW = btnX() - 16f - textX;

            // Titre/meta/description nettement plus espacés (chevauchement
            // confirmé en jeu avec les anciens écarts, trop serrés une fois
            // les échelles de texte agrandies) — répartis sur toute la
            // hauteur désormais disponible (rowH=128) plutôt que tassés en
            // haut/bas de la carte.
            renderer.drawText(truncate(renderer, hit.title, 0.56f, textMaxW), textX, y + h - 34,
                UiTheme.TEXT_PRIMARY, 0.56f, vpWidth, vpHeight);

            String meta = (hit.author != null && !hit.author.isEmpty() ? hit.author + "  ·  " : "")
                + formatDownloads(hit.downloads) + " téléchargements";
            renderer.drawText(truncate(renderer, meta, 0.42f, textMaxW), textX, y + h - 62,
                UiTheme.TEXT_SECONDARY, 0.42f, vpWidth, vpHeight);

            if (hit.description != null && !hit.description.isEmpty()) {
                renderer.drawText(truncate(renderer, hit.description, 0.4f, textMaxW), textX, y + 22,
                    UiTheme.TEXT_MUTED, 0.4f, vpWidth, vpHeight);
            }

            boolean installing = installing();
            boolean hoverBtn = contains(mouseX, mouseY);
            UiColor btnColor = installing ? UiTheme.ACCENT_DIM
                : alreadyInstalled ? UiTheme.PANEL_BG_ALT
                : (hoverBtn ? UiTheme.ACCENT : UiTheme.CARD_HOVER);
            renderer.drawRoundedRect(btnX(), btnY(), btnX() + BTN_W, btnY() + BTN_H,
                UiTheme.RADIUS_SM, btnColor, vpWidth, vpHeight);
            String label = installing ? spinnerFrame() + " Installation" : (alreadyInstalled ? "Installé" : "Installer");
            float lw = renderer.textWidth(label, 0.48f);
            renderer.drawText(label, btnX() + (BTN_W - lw) / 2f, btnY() + BTN_H / 2f - 6f,
                (alreadyInstalled && !installing) ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_PRIMARY, 0.48f, vpWidth, vpHeight);
        }
    }

    /** Frame de spinner ASCII (rotation continue, ~120ms/frame) — pas de dépendance à une police à glyphes étendus. */
    private static String spinnerFrame() {
        char[] frames = {'|', '/', '-', '\\'};
        return String.valueOf(frames[(int) ((System.currentTimeMillis() / 120L) % frames.length)]);
    }

    // ── Installation ───────────────────────────────────────────────────────────

    private void triggerInstall(ModrinthJson.Hit hit) {
        if (busy) return;
        busy = true;
        installingProjectId = hit.projectId;
        statusText = "Téléchargement de " + hit.title + "...";

        Thread t = new Thread(() -> doInstall(hit), "LauncherAgent-ModrinthInstall");
        t.setDaemon(true);
        t.start();
    }

    private void doInstall(ModrinthJson.Hit hit) {
        try {
            String fileJson = ContentBridge.getLatestFile(hit.projectId);
            String error = ModrinthJson.jsonString(fileJson, "error");
            if (error != null) {
                statusText = "Erreur Modrinth : " + error;
                return;
            }
            String url = ModrinthJson.jsonString(fileJson, "url");
            String filename = ModrinthJson.jsonString(fileJson, "filename");
            if (url == null || filename == null) {
                statusText = "Aucun fichier disponible pour " + hit.title;
                return;
            }

            Path dest = resourcePacksDir.resolve(filename);
            Files.createDirectories(resourcePacksDir);
            boolean ok = ContentBridge.downloadFile(url, dest.toString());
            statusText = ok
                ? hit.title + " installé — visible dans la liste \"Disponibles\""
                : "Échec du téléchargement de " + hit.title;
            // Réaffiche les mêmes résultats pour rafraîchir le badge Installé/Installer,
            // sans relancer une recherche réseau (showResults() ne fait que du local).
            if (ok) pendingResults = shownResults;
        } catch (Throwable t) {
            LauncherLog.err("[ModrinthResourcePackScreen] doInstall: " + t);
            statusText = "Erreur installation : " + t;
        } finally {
            busy = false;
            installingProjectId = null;
        }
    }

    // ── Détection "déjà installé" (pur Java, aucune réflexion MC — portable tel quel) ──

    private Set<String> scanInstalledNormalizedNames() {
        Set<String> names = new HashSet<>();
        try {
            if (Files.isDirectory(resourcePacksDir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(resourcePacksDir)) {
                    stream.forEach(p -> names.add(normalize(p.getFileName().toString())));
                }
            }
        } catch (Exception e) {
            LauncherLog.warn("[ModrinthResourcePackScreen] scanInstalled: " + e);
        }
        return names;
    }

    private static boolean isInstalled(ModrinthJson.Hit hit, Set<String> installedNormalizedNames) {
        String needle = normalize(hit.title);
        if (needle.isEmpty()) return false;
        for (String name : installedNormalizedNames) {
            if (name.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
