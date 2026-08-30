package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import com.yuyuframe.launcheragent.runtime.module.hud.ArmorDurabilityModule;

/**
 * Aperçu du contenu d'une shulker box en la survolant (case d'inventaire)
 * tout en maintenant Maj — reprend l'IDÉE du mod ShulkerBoxTooltip
 * (MisterPeModder, GitHub, branche 26.1.x, licence MIT), PAS son code : ce
 * mod s'intègre profondément au pipeline de tooltip vanilla
 * (ClientTooltipComponent, GuiGraphicsExtractor, mixins sur Screen/
 * ItemStack...) — bien plus qu'il n'en faut ici. Réimplémenté avec
 * l'infrastructure déjà existante de ce projet : panneau flottant dessiné à
 * CHAQUE frame près du curseur, en dehors de tout pipeline de tooltip
 * vanilla — voir {@link #renderIfApplicable}, appelé directement depuis
 * GlobalUiPresentMixin261/GlobalUiPresentMixin (branche "écran vanilla
 * ouvert"), aucun Mixin supplémentaire nécessaire.
 *
 * BUG TROUVÉ (retour utilisateur) : un premier essai ajoutait un Mixin
 * {@code @Inject} sur {@code Screen.extractRenderStateWithTooltipAndSubtitles}
 * (pour annuler le tooltip vanilla quand notre panneau se chevauchait avec
 * lui) — ciblant la classe {@code Screen} elle-même (superclasse commune de
 * TOUS les écrans, y compris nos propres écrans custom), Mixin devait
 * reconstruire la hiérarchie de classes pour chaque sous-type au chargement,
 * et ça CASSAIT le chargement de nos propres écrans. Retiré entièrement —
 * le panneau est de toute façon ancré sur la case survolée (voir drawGrid),
 * pas sur le curseur brut ni sur la fenêtre entière, ce qui réduit déjà
 * fortement le risque de chevauchement sans avoir besoin d'y toucher.
 *
 * FOND DE FENÊTRE : la VRAIE texture vanilla
 * ({@code textures/gui/container/shulker_box.png}, 256x256, région visible
 * 176x166 dans son coin haut-gauche — confirmé par désassemblage bytecode du
 * jar 26.1.2, PAS recréée), demandé explicitement par l'utilisateur — voir
 * {@link UiRenderer#drawVanillaContainerTexture} (déjà cross-bracket via son
 * propre repli Yarn/réel interne, rien à changer ici pour ce morceau).
 *
 * SCOPE ACTUEL : 26.1.2 (noms réels directs) ET 1.21.11 (résolution Yarn +
 * repli réel via {@link McReflect}, TOUT vérifié par mappings officiels
 * Mojang + fichier Yarn .tiny téléchargés pour CETTE version exacte, pas
 * deviné — voir chaque résolveur ci-dessous pour le détail par bracket) :
 * <pre>
 *   HandledScreen (Yarn) / AbstractContainerScreen (réel) — champ Slot survolé :
 *     "focusedSlot" (Yarn) / "hoveredSlot" (réel).
 *   HandledScreen.x/y (Yarn) / AbstractContainerScreen.leftPos/topPos (réel) —
 *     coin haut-gauche de la fenêtre en GUI-pixels.
 *   Slot (même nom Yarn/réel) — getStack() (Yarn) / getItem() (réel) ; x/y
 *     (mêmes noms côté source, mais TOUJOURS obfusqués à l'exécution sur
 *     1.21.11, résolution McReflect obligatoire même quand yarn==réel).
 *   ItemStack (même nom Yarn/réel) — isEmpty()/getItem()/get(ComponentType)
 *     mêmes noms des deux côtés (get() hérité de ComponentsAccess sur Yarn,
 *     DataComponentGetter sur réel — interfaces DIFFÉRENTES mais même nom de
 *     méthode "get", résolu par recherche de hiérarchie McReflect standard).
 *   BlockItem (même nom Yarn/réel) — getBlock() (même nom des deux côtés).
 *   ShulkerBoxBlock (même nom Yarn/réel, package différent).
 *   DataComponentTypes (Yarn) / DataComponents (réel) — champ CONTAINER
 *     (même nom des deux côtés).
 *   ContainerComponent (Yarn) / ItemContainerContents (réel) — stream()
 *     (Yarn) / allItemsCopyStream() (réel), TOUJOURS un élément par slot
 *     dans l'ordre, ItemStack vide pour les cases vides.
 * </pre>
 * 1.8.9/1.16.5/1.20.4 pas encore portés — le contenu d'une shulker box y est
 * stocké via NBT ({@code BlockEntityTag}), pas de DataComponent avant la
 * refonte "Data Components" (~1.20.5) : lecture entièrement différente à
 * écrire, pas un simple repli de noms.
 */
public final class ShulkerPreviewModule extends LauncherModule {

    private static final String CONTAINER_TEXTURE_PATH = "textures/gui/container/shulker_box.png";

    private static final int COLS = 9, ROWS = 3;
    // Origine/pas RÉELS vanilla pour la famille "coffre" (27 slots de
    // stockage) — voir javadoc de classe : les icônes tombent directement
    // dans les cases peintes par CONTAINER_TEXTURE, pas de recalage manuel.
    private static final float SLOT_ORIGIN_X_GUI = 7f;
    private static final float SLOT_ORIGIN_Y_GUI = 17f;
    private static final float SLOT_PITCH_GUI = 18f;
    private static final float ICON_GUI = 16f;
    private static final float ICON_INSET_GUI = (SLOT_PITCH_GUI - ICON_GUI) / 2f;
    // Texture réelle 256x256 (atlas), région utile 176x166 — recadrée ici à
    // la zone de stockage seule (bord plat en bas, compromis accepté).
    private static final float TEX_W = 256f, TEX_H = 256f;
    private static final float IMG_W_GUI = 176f;
    private static final float IMG_CROP_H_GUI = 78f;
    /** Écart (GUI-pixels) entre le bord de la case survolée et notre panneau — voir drawGrid, même ordre de grandeur que l'offset du tooltip vanilla. */
    private static final float SLOT_GAP_GUI = 12f;
    /** Taille d'une case vanilla (18x18) — pour placer le panneau juste APRÈS le bord droit de la case survolée. */
    private static final float HOVERED_SLOT_SIZE_GUI = 18f;

    // ── Résolution paresseuse, mise en cache (McReflect : Yarn + repli réel) ──
    private static volatile Field fHoveredSlot;
    private static volatile Field fLeftPos, fTopPos;
    private static volatile Field fSlotX, fSlotY;
    private static volatile Method mSlotGetStack;
    private static volatile Method mItemStackGetItem;
    private static volatile Method mItemStackIsEmpty;
    private static volatile Method mBlockItemGetBlock;
    private static volatile Method mItemStackGet;
    private static volatile Method mContentsStream;
    private static volatile Class<?> clsBlockItem;
    private static volatile Class<?> clsShulkerBoxBlock;
    private static volatile Field fContainerComponent;

    /** Garde le log de {@link #hoveredSlotScreenPosGui} à une seule occurrence (sinon spam à chaque frame tant que la résolution échoue, fLeftPos/fSlotX etc. restant null indéfiniment). */
    private static volatile boolean posResolveErrorLogged;
    /**
     * BUG TROUVÉ (test utilisateur, 1.21.11 : "le shulker ne marche pas",
     * aucun log — {@code renderIfApplicable} avalait TOUTE exception
     * silencieusement, y compris un vrai échec de résolution McReflect à
     * n'importe quelle étape de la chaîne). Un seul log (pas par frame) dès
     * la première exception, pour avoir un signal exploitable au prochain
     * test au lieu d'un silence total — voir feedback session "jamais
     * avaler silencieusement un catch(Throwable)".
     */
    private static volatile boolean errorLogged;

    public ShulkerPreviewModule() {
        super("shulker-preview", "Aperçu shulker (Maj)", "Survole une shulker box dans un inventaire en maintenant Maj pour voir son contenu.",
            "Aperçu du contenu au survol", true);
        iconUrl = icons8("treasure-chest");
    }

    /**
     * Appelé à CHAQUE frame par GlobalUiPresentMixin261/GlobalUiPresentMixin
     * quand un écran vanilla (pas un des nôtres) est ouvert. Ne fait rien
     * tant que le module est désactivé, qu'aucune touche Maj n'est
     * maintenue, ou que l'écran/la case survolée ne correspond pas à une
     * shulker box non vide.
     */
    public static void renderIfApplicable(UiRenderer renderer, Object currentScreen, UiInputPoller poller, int vpWidth, int vpHeight) {
        try {
            Object slot = hoveredShulkerSlot(currentScreen, poller);
            if (slot == null) return;
            Object stack = slotItem(slot);

            Object[] items = readContents(stack);
            if (items == null) return;
            drawGrid(renderer, currentScreen, slot, items, vpWidth, vpHeight);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                LauncherLog.err("[ShulkerPreviewModule] renderIfApplicable: " + sw);
            }
        }
    }

    /** @return la case (Slot) survolée si elle contient une shulker box non vide et que les conditions sont réunies (module actif, Maj maintenu) — sinon {@code null}. */
    private static Object hoveredShulkerSlot(Object screen, UiInputPoller poller) throws Exception {
        LauncherModule self = ModuleRegistry.get("shulker-preview");
        if (self == null || !self.isEnabled()) return null;
        if (screen == null || poller == null || !poller.shiftDown) return null;

        Object hoveredSlot = hoveredSlot(screen);
        if (hoveredSlot == null) return null;
        Object stack = slotItem(hoveredSlot);
        if (stack == null || isEmptyStack(stack)) return null;
        if (!isShulkerBox(stack)) return null;
        return hoveredSlot;
    }

    /**
     * "focusedSlot" (Yarn) / "hoveredSlot" (réel 26.1.2) — champ protégé
     * déclaré DIRECTEMENT sur AbstractContainerScreen/HandledScreen (vérifié
     * par javap sur le vrai jar 26.1.2).
     *
     * BUG TROUVÉ (test utilisateur, 1.21.11 : "IllegalArgumentException:
     * object is not an instance of declaring class" dans slotItem, juste
     * après cet appel) : {@code McReflect.field(screen.getClass(), ...)}
     * remonte la hiérarchie RUNTIME de l'écran REND (ex: {@code
     * ShulkerBoxScreen}, {@code InventoryScreen}, {@code
     * GenericContainerScreen}...) et s'arrête au PREMIER champ portant la
     * lettre obfusquée cherchée — si une de ces sous-classes CONCRÈTES a SA
     * PROPRE variable, sans rapport, sous la MÊME lettre obfusquée courte
     * (coïncidence quasi inévitable, voir javadoc de {@link
     * McReflect#field(Class, String, String)}), on récupère un objet qui
     * N'EST PAS un {@code Slot} — {@code .get()} ne plante pas (le champ
     * coïncident existe bien sur CETTE sous-classe), mais {@code slotItem}
     * plante ensuite en essayant d'invoquer {@code getStack()} dessus.
     * Fix : {@link McReflect#fieldOnClass} résout DIRECTEMENT sur la classe
     * QUI DÉCLARE VRAIMENT le champ (jamais en remontant depuis l'instance),
     * élimine structurellement le risque, quelle que soit la sous-classe
     * d'écran ouverte.
     *
     * BUG TROUVÉ #2 (test utilisateur, 1.21.4 : {@code IllegalArgumentException:
     * Can not get cua field fvb.B on fti}) : {@code fieldOnClass} protège
     * contre les collisions de lettre obfusquée, mais PAS contre un appelant
     * qui passe un écran qui N'EST TOUT SIMPLEMENT PAS un {@code
     * HandledScreen} — {@code renderIfApplicable} est appelé pour N'IMPORTE
     * QUEL écran vanilla non custom ouvert (voir {@code GlobalUiPresentMixin}/
     * {@code GlobalUiRenderMixin1214}, branche {@code !(currentScreen
     * instanceof UiDrawable)}), y compris {@code ChatScreen} (obf {@code
     * fti} sur 1.21.4, confirmé par le message d'erreur) si Maj est tenue
     * pendant que le chat est ouvert — {@code Field.get()} lève alors
     * IMMÉDIATEMENT, quelle que soit la façon dont le {@code Field} a été
     * résolu (comportement JVM standard, pas un défaut de McReflect). Ce
     * risque latent existait déjà sur 26.1.2/1.21.11 (jamais déclenché,
     * probablement jamais testé Maj+chat ouvert). Fix : vérifier {@code
     * handledScreenClass.isInstance(screen)} AVANT tout accès au champ.
     */
    private static volatile Class<?> clsHandledScreen;

    private static Object hoveredSlot(Object screen) throws Exception {
        if (clsHandledScreen == null) {
            clsHandledScreen = McReflect.yarnClass(
                "net/minecraft/client/gui/screen/ingame/HandledScreen",
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen");
            if (clsHandledScreen == null) return null;
        }
        if (!clsHandledScreen.isInstance(screen)) return null;
        if (fHoveredSlot == null) {
            fHoveredSlot = McReflect.fieldOnClass(
                "net/minecraft/client/gui/screen/ingame/HandledScreen",
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
                "focusedSlot", "hoveredSlot");
            if (fHoveredSlot == null) return null;
        }
        return fHoveredSlot.get(screen);
    }

    /**
     * Position ÉCRAN (GUI-pixels, repère haut-gauche) de la case survolée —
     * "x"/"y" (Yarn, sur HandledScreen) / "leftPos"/"topPos" (réel 26.1.2,
     * sur AbstractContainerScreen) + Slot.x/Slot.y (même nom source des deux
     * côtés, mais résolution McReflect quand même nécessaire — TOUJOURS
     * obfusqué à l'exécution sur un bracket Yarn). Sert à ancrer le panneau
     * juste À CÔTÉ de l'objet survolé (même repère que le tooltip vanilla),
     * PAS sur le curseur ni sur la fenêtre entière — voir drawGrid.
     */
    private static int[] hoveredSlotScreenPosGui(Object screen, Object slot) throws Exception {
        // Même correctif que hoveredSlot() (voir sa javadoc) — fieldOnClass
        // au lieu de field(instance.getClass(), ...) pour éliminer tout
        // risque de collision de lettre obfusquée avec une sous-classe
        // d'écran/de Slot concrète.
        if (fLeftPos == null) {
            fLeftPos = McReflect.fieldOnClass(
                "net/minecraft/client/gui/screen/ingame/HandledScreen",
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
                "x", "leftPos");
            fTopPos = McReflect.fieldOnClass(
                "net/minecraft/client/gui/screen/ingame/HandledScreen",
                "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
                "y", "topPos");
            if (fLeftPos == null || fTopPos == null) {
                if (!posResolveErrorLogged) {
                    posResolveErrorLogged = true;
                    LauncherLog.err("[ShulkerPreviewModule] hoveredSlotScreenPosGui: résolution HandledScreen.x/y échouée (fLeftPos="
                        + fLeftPos + " fTopPos=" + fTopPos + ")");
                }
                return null;
            }
        }
        if (fSlotX == null) {
            fSlotX = McReflect.fieldOnClass(
                "net/minecraft/screen/slot/Slot", "net.minecraft.world.inventory.Slot", "x", "x");
            fSlotY = McReflect.fieldOnClass(
                "net/minecraft/screen/slot/Slot", "net.minecraft.world.inventory.Slot", "y", "y");
            if (fSlotX == null || fSlotY == null) {
                if (!posResolveErrorLogged) {
                    posResolveErrorLogged = true;
                    LauncherLog.err("[ShulkerPreviewModule] hoveredSlotScreenPosGui: résolution Slot.x/y échouée (fSlotX="
                        + fSlotX + " fSlotY=" + fSlotY + ")");
                }
                return null;
            }
        }
        int leftPos = fLeftPos.getInt(screen), topPos = fTopPos.getInt(screen);
        return new int[]{ leftPos + fSlotX.getInt(slot), topPos + fSlotY.getInt(slot) };
    }

    /**
     * "getStack" (Yarn) / "getItem" (réel 26.1.2) — déclarée DIRECTEMENT sur
     * Slot (vérifié par javap). Résolue via {@link McReflect#methodOnClass}
     * (jamais {@code noArgMethod(slot.getClass(), ...)}) — même correctif
     * que {@link #hoveredSlot} : une sous-classe concrète de {@code Slot}
     * (il en existe plusieurs en vanilla : résultat de craft, sortie de
     * fourneau...) pourrait sinon faire remonter une méthode sans rapport
     * partageant la même lettre obfusquée courte.
     */
    private static Object slotItem(Object slot) throws Exception {
        if (mSlotGetStack == null) {
            mSlotGetStack = McReflect.methodOnClass("net/minecraft/screen/slot/Slot", "getStack");
            if (mSlotGetStack == null) {
                mSlotGetStack = McReflect.noArgMethod(slot.getClass(), "net/minecraft/screen/slot/Slot", "getStack", "getItem");
            }
            if (mSlotGetStack == null) return null;
        }
        return mSlotGetStack.invoke(slot);
    }

    /** "isEmpty" — même nom Yarn/réel. */
    private static boolean isEmptyStack(Object stack) throws Exception {
        if (mItemStackIsEmpty == null) {
            mItemStackIsEmpty = McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "isEmpty");
            if (mItemStackIsEmpty == null) return false;
        }
        return (boolean) mItemStackIsEmpty.invoke(stack);
    }

    private static boolean isShulkerBox(Object stack) throws Exception {
        if (mItemStackGetItem == null) {
            mItemStackGetItem = McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getItem");
            if (mItemStackGetItem == null) return false;
        }
        Object item = mItemStackGetItem.invoke(stack);
        if (item == null) return false;

        if (clsBlockItem == null) {
            clsBlockItem = McReflect.yarnClass("net/minecraft/item/BlockItem", "net.minecraft.world.item.BlockItem");
            if (clsBlockItem == null) return false;
        }
        if (!clsBlockItem.isInstance(item)) return false;

        if (mBlockItemGetBlock == null) {
            mBlockItemGetBlock = McReflect.methodOnClass("net/minecraft/item/BlockItem", "getBlock");
            if (mBlockItemGetBlock == null) {
                mBlockItemGetBlock = McReflect.noArgMethod(item.getClass(), "net/minecraft/item/BlockItem", "getBlock");
            }
            if (mBlockItemGetBlock == null) return false;
        }
        Object block = mBlockItemGetBlock.invoke(item);
        if (block == null) return false;

        if (clsShulkerBoxBlock == null) {
            clsShulkerBoxBlock = McReflect.yarnClass("net/minecraft/block/ShulkerBoxBlock", "net.minecraft.world.level.block.ShulkerBoxBlock");
            if (clsShulkerBoxBlock == null) return false;
        }
        return clsShulkerBoxBlock.isInstance(block);
    }

    /** @return les ItemStack des (jusqu'à) 27 slots, EMPTY filtrés en null pour {@link #drawGrid} — null si le composant CONTAINER est absent. */
    private static Object[] readContents(Object stack) throws Exception {
        if (fContainerComponent == null) {
            Class<?> dc = McReflect.yarnClass("net/minecraft/component/DataComponentTypes", "net.minecraft.core.component.DataComponents");
            if (dc == null) return null;
            // Champ statique public "CONTAINER" — même nom Yarn/réel comme
            // CHAÎNE, mais TOUJOURS obfusqué à l'exécution sur un bracket
            // Yarn (1.21.11) : getField("CONTAINER") échouerait (aucun champ
            // littéralement nommé ainsi dans la classe chargée) — McReflect
            // obligatoire même ici.
            fContainerComponent = McReflect.field(dc, "net/minecraft/component/DataComponentTypes", "CONTAINER");
            if (fContainerComponent == null) return null;
        }
        Object componentType = fContainerComponent.get(null);
        if (componentType == null) return null;

        if (mItemStackGet == null) {
            // BUG TROUVÉ (test utilisateur, 1.21.11) : ClassCastException
            // plus loin (bfk$2 → Stream) trahissait que "contents" n'était
            // PAS une vraie instance de ContainerComponent — la VRAIE cause
            // était ICI. McReflect.method(stack.getClass(), ...) commence sa
            // recherche par les méthodes DÉCLARÉES DIRECTEMENT sur ItemStack
            // lui-même (findMethodInHierarchy vérifie c.getDeclaredMethods()
            // AVANT ses interfaces) — ItemStack est une classe ÉNORME avec
            // des DIZAINES de surcharges obfusquées "a" à 1 argument (vérifié
            // par javap : a(dgz), a(bef<dlp>), a(dlp), a(Predicate<jd<dlp>>),
            // a(jd<dlp>), a(jh<dlp>), a(int)...), sans AUCUN rapport avec les
            // composants — largement de quoi coïncider avec un des types
            // acceptant kh (DataComponentType) via isAssignableFrom AVANT que
            // la recherche n'atteigne jamais la VRAIE méthode déclarée sur
            // l'interface ComponentsAccess/kd.
            // Fix : methodOnClass résout DIRECTEMENT sur l'interface (3
            // méthodes seulement, kd.a(kh)/a(kh,T)/b(kh) — aucune ambiguïté
            // possible), jamais en remontant depuis ItemStack. Repli sur
            // l'ancienne résolution UNIQUEMENT pour 26.1.2 (noms réels
            // complets, pas de lettres obfusquées courtes — le risque de
            // collision qui affecte 1.21.11 n'existe pas là-bas).
            mItemStackGet = McReflect.methodOnClass("net/minecraft/component/ComponentsAccess", "get", fContainerComponent.getType());
            if (mItemStackGet == null) {
                mItemStackGet = McReflect.method(stack.getClass(), "net/minecraft/component/ComponentsAccess", "get", fContainerComponent.getType());
            }
            if (mItemStackGet == null) return null;
        }
        Object contents = mItemStackGet.invoke(stack, componentType);
        if (contents == null) return null;

        if (mContentsStream == null) {
            mContentsStream = McReflect.noArgMethod(contents.getClass(), "net/minecraft/component/type/ContainerComponent", "stream", "allItemsCopyStream");
            if (mContentsStream == null) return null;
        }
        java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) mContentsStream.invoke(contents);
        List<Object> out = new ArrayList<>();
        stream.forEach(entry -> {
            try {
                out.add(isEmptyStack(entry) ? null : entry);
            } catch (Throwable t) {
                out.add(null);
            }
        });
        return out.toArray();
    }

    /**
     * Panneau ancré AU-DESSUS de la case survolée (voir
     * {@link #hoveredSlotScreenPosGui}), centré horizontalement dessus — PAS
     * à côté (essai précédent) : vanilla place TOUJOURS son propre tooltip
     * en bas-à-droite du curseur, jamais au-dessus — ce placement évite donc
     * pratiquement tout chevauchement avec le nom/lore de l'objet SANS avoir
     * besoin d'annuler ce tooltip par Mixin.
     *
     * PAS sur le curseur brut non plus (bougeait avec le moindre tremblement
     * de souris à l'intérieur de la case) NI sur la fenêtre de conteneur
     * entière (un tout premier essai recouvrait l'inventaire) : ancré sur la
     * case ELLE-MÊME, donc stable tant que la souris reste dessus, et se
     * repositionne proprement d'une case à l'autre en survolant plusieurs
     * shulker box Maj maintenu. Repli EN DESSOUS de la case si le panneau
     * déborderait par le haut de l'écran (case tout en haut de la fenêtre).
     *
     * Fond = VRAIE texture vanilla recadrée (voir javadoc de classe), grille
     * 9x3 (taille fixe d'une shulker box vanilla), cases vides simplement
     * omises. Coordonnées en "GUI-pixels" multipliées par
     * {@link UiRenderer#guiScale} avant tout appel de dessin — même
     * convention que ArmorDurabilityModule.drawVanillaHotbarRow.
     */
    private static void drawGrid(UiRenderer renderer, Object currentScreen, Object slot, Object[] items, int vpWidth, int vpHeight) throws Exception {
        int[] slotPos = hoveredSlotScreenPosGui(currentScreen, slot);
        if (slotPos == null) {
            if (!posResolveErrorLogged) {
                posResolveErrorLogged = true;
                LauncherLog.err("[ShulkerPreviewModule] drawGrid: hoveredSlotScreenPosGui a renvoyé null (voir logs de résolution ci-dessus) — panneau jamais dessiné");
            }
            return;
        }
        int slotLeftGui = slotPos[0], slotTopGui = slotPos[1];

        float guiScale = UiRenderer.guiScale(vpWidth);
        float guiWidth = vpWidth / guiScale;
        float guiHeight = vpHeight / guiScale;

        float panelWGui = IMG_W_GUI;
        float panelHGui = IMG_CROP_H_GUI;

        // Centré horizontalement sur la case survolée.
        float leftGui = slotLeftGui + (HOVERED_SLOT_SIZE_GUI - panelWGui) / 2f;
        if (leftGui < 0) leftGui = 0;
        if (leftGui + panelWGui > guiWidth) leftGui = guiWidth - panelWGui;

        // Bord BAS du panneau juste au-dessus du bord HAUT de la case (repli en dessous si pas la place en haut).
        float topGui = slotTopGui - SLOT_GAP_GUI - panelHGui;
        if (topGui < 0) topGui = slotTopGui + HOVERED_SLOT_SIZE_GUI + SLOT_GAP_GUI;
        if (topGui + panelHGui > guiHeight) topGui = guiHeight - panelHGui;
        if (topGui < 0) topGui = 0;

        float left = leftGui * guiScale;
        // Conversion vers notre repère bas-gauche (framebuffer) — voir
        // drawVanillaItemIcon/drawVanillaContainerTexture, qui attendent le
        // bord BAS en y (topGui = origine haut vanilla, guiHeight - topGui -
        // hauteur = bord bas en GUI-space, puis mise à l'échelle framebuffer).
        float bottom = (guiHeight - topGui - panelHGui) * guiScale;
        float top = bottom + panelHGui * guiScale;

        renderer.drawVanillaContainerTexture(CONTAINER_TEXTURE_PATH, left, bottom, panelWGui * guiScale, panelHGui * guiScale,
            0f, 0f, TEX_W, TEX_H, vpWidth, vpHeight);

        for (int i = 0; i < items.length && i < COLS * ROWS; i++) {
            Object stack = items[i];
            if (stack == null) continue;
            int col = i % COLS, row = i / COLS;
            float cellLeftGui = SLOT_ORIGIN_X_GUI + col * SLOT_PITCH_GUI + ICON_INSET_GUI;
            float cellTopGui = SLOT_ORIGIN_Y_GUI + row * SLOT_PITCH_GUI + ICON_INSET_GUI;
            float iconLeftFb = left + cellLeftGui * guiScale;
            // top = origine HAUT (image), notre repère est bas-gauche (framebuffer) — bord BAS de l'icône = top du panneau moins (cellTopGui+ICON_GUI) converti.
            float iconBottomFb = top - (cellTopGui + ICON_GUI) * guiScale;
            renderer.drawVanillaItemIcon(stack, iconLeftFb, iconBottomFb, ICON_GUI * guiScale, vpWidth, vpHeight);
        }
    }
}
