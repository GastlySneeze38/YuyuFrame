package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.render.UiVanillaItemRenderer;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Optional;
import java.util.Set;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Équivalent AppleSkin — REFONTE COMPLÈTE du 2026-08-31.
 *
 * <h2>Ce que c'était, ce que c'est</h2>
 *
 * Jusqu'ici : un panneau HUD déplaçable affichant deux lignes de texte,
 * « Saturation : 4.2 » et « Exhaustion : 1.35 ». Ça n'a rien à voir avec ce
 * que fait AppleSkin, et l'utilisateur l'a tranché : « je veux qu'elle soit
 * comme AppleSkin, pas un HUD ». Le panneau et son {@code HudElement} ont
 * donc disparu — ce module n'est plus déplaçable du tout.
 *
 * <p>Désormais, comme AppleSkin ({@code squeek502/AppleSkin},
 * {@code HUDOverlayHandler}), tout se dessine PAR-DESSUS les barres vanilla,
 * à leur position calculée :
 * <ol>
 *   <li>la SATURATION en liseré sur la barre de faim, par demi-point ;</li>
 *   <li>l'ÉPUISEMENT en fine barre sous les icônes de faim ;</li>
 *   <li>en tenant un aliment, l'APERÇU de la faim et de la saturation qu'il
 *       rendrait, en fantôme clignotant ;</li>
 *   <li>et l'APERÇU des cœurs que la régénération naturelle rendrait.</li>
 * </ol>
 *
 * <h2>Écart assumé avec AppleSkin</h2>
 *
 * Ses indicateurs utilisent SON PROPRE atlas d'icônes ({@code modIcons}), qui
 * n'est pas un asset vanilla — impossible de le reprendre. Les nôtres sont
 * donc dessinés au moteur de rects, ce qui donne un liseré plutôt que des
 * demi-icônes texturées.
 *
 * <h2>La saturation du client est PÉRIMÉE — d'où {@link Estimator}</h2>
 *
 * Constat utilisateur (2026-08-31) : « la barre de saturation ne descend pas
 * petit à petit, elle disparaît d'un coup ». Ce n'était pas un défaut de
 * calcul — c'est le PROTOCOLE.
 *
 * <p>Le serveur n'envoie {@code ClientboundSetHealthPacket} (le seul paquet
 * qui porte la saturation) que lorsque le niveau de FAIM change, ou que la
 * saturation FRANCHIT zéro — voir {@code ServerPlayer.lastSentFood} et
 * {@code lastFoodSaturationZero}, deux champs vérifiés dans le jar 26.1.2.
 * Entre ces événements la valeur côté client ne bouge pas d'un pouce, puis
 * tombe d'un coup.
 *
 * <p>AppleSkin résout ça par un composant SERVEUR qui pousse la vraie valeur
 * à chaque changement — inapplicable ici : l'agent doit fonctionner sur des
 * serveurs qui ne sont pas les nôtres, où l'on ne peut rien installer. La
 * seule voie 100 % cliente est donc d'ESTIMER l'épuisement localement, ce que
 * fait {@link Estimator} : il rejoue la comptabilité du serveur à partir de
 * ce que le client observe, et se RECALE sur la vérité dès que le serveur
 * parle. Approximatif entre deux paquets, exact à chacun d'eux.
 *
 * <h2>Accès aux données</h2>
 *
 * TOUT passe par des accessors, y compris là où un getter public existe
 * ({@code getFoodLevel()}/{@code getSaturationLevel()}) et là où le champ est
 * public ({@code DataComponents.FOOD}) — consigne explicite. Les accès passent
 * par {@link AccessorRegistry} ({@code FOOD_LEVEL}, {@code FOOD_SATURATION},
 * {@code FOOD_EXHAUSTION}, {@code COMPONENT_TYPE_*}), donc par l'accessor de
 * la tranche active. Sur une version sans liaison, ce module ne fait rien et
 * s'efface proprement.
 */
public final class SaturationModule extends LauncherModule {

    public boolean showSaturation = true;
    public boolean showExhaustion = true;
    public boolean showFoodPreview = true;
    public boolean showHealthPreview = true;
    public boolean estimateSaturation = true;

    @Override
    protected void settings(SettingList s) {
        s.toggle("showSaturation", "Indicateur de saturation",
            "Liseré sur la barre de faim : la saturation, que l'interface vanilla cache entièrement.",
            "Réglages", null, () -> showSaturation, v -> showSaturation = v);
        s.toggle("estimateSaturation", "Estimation continue",
            "Le serveur n'envoie la saturation qu'aux changements de niveau de faim et au passage à zéro : sans estimation, le liseré reste figé puis tombe d'un coup. Activé, l'épuisement est recalculé localement (sprint, nage, saut, régénération, dégâts) et recalé sur la vraie valeur dès que le serveur parle. Décoche pour voir la valeur brute du serveur.",
            "Réglages", () -> showSaturation, () -> estimateSaturation, v -> estimateSaturation = v);
        s.toggle("showExhaustion", "Indicateur d'épuisement",
            "Fine barre sous la faim. L'épuisement monte en courant, sautant, encaissant des dégâts ; à 4 il consomme un point de saturation.",
            "Réglages", null, () -> showExhaustion, v -> showExhaustion = v);
        s.toggle("showFoodPreview", "Aperçu de l'aliment tenu",
            "Montre en fantôme clignotant la faim et la saturation que l'aliment tenu en main rendrait.",
            "Réglages", null, () -> showFoodPreview, v -> showFoodPreview = v);
        s.toggle("showHealthPreview", "Aperçu des cœurs régénérés",
            "Montre en fantôme les cœurs que la régénération naturelle rendrait après avoir mangé.",
            "Réglages", null, () -> showHealthPreview, v -> showHealthPreview = v);
        s.slider("blinkSeconds", "Durée d'un clignotement (s)",
            "Temps d'un aller-retour complet du clignotement des aperçus. 1,6 s reproduit exactement la cadence d'AppleSkin ; plus court = plus nerveux.",
            "Réglages", 0.4f, 4f, 0.1f,
            () -> showFoodPreview || showHealthPreview, () -> blinkSeconds, v -> blinkSeconds = v);
    }

    /** Durée d'un cycle complet du clignotement — voir {@link #flashAccumulator}. */
    public float blinkSeconds = 1.6f;

    public SaturationModule() {
        // Nom raccourci (était "Saturation (AppleSkin)") — retour utilisateur :
        // débordait de la sous-sidebar du groupe "Confort visuel" (voir
        // ModuleRegistry) ; l'attribution AppleSkin reste dans la description.
        super("saturation", "Saturation",
            "Saturation, épuisement et aperçu de l'aliment tenu, façon AppleSkin — dessinés sur les barres vanilla",
            "Saturation et épuisement sur les barres vanilla", false,
            // DÉCLARÉ, sinon PiercingAttackMixin261 n'est pas tissé du tout :
            // le filtre de MixinHookPointRegistry ne retient que les mixins
            // dont le HookPoint est réclamé par au moins un module.
            HookPoint.PIERCING_ATTACK);
        iconUrl = icons8("steak");

        // Le coup de lance chargé — voir onPiercingAttack(). Enregistré même
        // module désactivé : sans ça, activer le module en pleine partie
        // repartirait d'un accumulateur qui a raté des coups. Retour false
        // systématique, ce hook est une notification pure.
        VanillaHookRegistry.register(HookPoint.PIERCING_ATTACK, ctx -> { onPiercingAttack(); return false; });
    }

    /**
     * Coup de lance chargé : l'enchantement « lunge » consomme
     * <b>4 d'épuisement par niveau</b> (base 4, +4 par niveau au-dessus du
     * premier — lu dans {@code data/minecraft/enchantment/lunge.json} du jar
     * 26.1.2), soit 1 à 3 points de saturation d'un coup.
     *
     * <p>C'est LE trou de l'estimation locale, signalé par l'utilisateur : le
     * sprint use la saturation en continu et se laisse suivre, la lance la
     * fait chuter d'un bloc entier sans que le client en sache rien. Le hook
     * {@link HookPoint#PIERCING_ATTACK} le rend observable — l'attaque part du
     * client, aucun besoin de quoi que ce soit côté serveur.
     *
     * <p>Les conditions reproduisent celles du fichier d'enchantement : le
     * lunge ne se déclenche pas en monture, en vol à l'élytre, dans l'eau, ni
     * sous 7 de faim. Les reproduire évite de facturer un coût qui n'a pas été
     * prélevé — une surestimation ferait descendre le liseré trop vite, plus
     * visible qu'un léger retard.
     *
     * <p>« lunge » est le SEUL enchantement du jeu à toucher à la faim :
     * {@code apply_exhaustion} n'apparaît dans aucun autre fichier de données
     * (vérifié sur l'ensemble du jar). Pas besoin d'un mécanisme générique.
     */
    private void onPiercingAttack() {
        if (!estimateSaturation) return;
        try {
            LocalPlayer player = PlayerData.player();
            if (player == null) return;
            if (player.isPassenger() || player.isFallFlying() || player.isInWater()) return;

            FoodData foodData = player.getFoodData();
            Object foodLevelValue = AccessorRegistry.get(AccessPoint.FOOD_LEVEL, foodData);
            if (!(foodLevelValue instanceof Number)) return;
            if (((Number) foodLevelValue).intValue() < LUNGE_MIN_FOOD) return;

            int level = lungeLevel(player.getItemInHand(InteractionHand.MAIN_HAND));
            if (level <= 0) return;
            estimator.addExhaustion(LUNGE_EXHAUSTION_PER_LEVEL * level);
        } catch (Throwable t) {
            if (!lungeErrorLogged) {
                lungeErrorLogged = true;
                LauncherLog.err("[SaturationModule] onPiercingAttack: " + t);
            }
        }
    }

    private static boolean lungeErrorLogged;

    /** Voir {@code data/minecraft/enchantment/lunge.json} : {@code apply_exhaustion}, base 4, +4 par niveau. */
    private static final float LUNGE_EXHAUSTION_PER_LEVEL = 4f;
    /** Idem : le lunge exige au moins ce niveau de faim. */
    private static final int LUNGE_MIN_FOOD = 7;

    /**
     * Niveau de « lunge » sur l'objet tenu, 0 s'il ne l'a pas.
     *
     * <p>Parcourt les enchantements de l'objet et compare le CHEMIN DE
     * REGISTRE, plutôt que de chercher {@code Enchantments.LUNGE} dans le
     * registre : un {@code Holder} porte déjà sa clé
     * ({@code unwrapKey().identifier()}), là où une recherche en registre
     * demanderait un {@code HolderLookup.Provider}, donc l'accès au monde,
     * depuis un chemin appelé en plein combat.
     */
    private int lungeLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        net.minecraft.core.component.DataComponentType<ItemEnchantments> type =
            componentType(AccessPoint.COMPONENT_TYPE_ENCHANTMENTS);
        if (type == null) return 0;
        ItemEnchantments enchantments = stack.get(type);
        if (enchantments == null || enchantments.isEmpty()) return 0;
        Set<Holder<Enchantment>> keys = enchantments.keySet();
        if (keys == null) return 0;
        for (Holder<Enchantment> holder : keys) {
            if (holder == null) continue;
            Optional<ResourceKey<Enchantment>> key = holder.unwrapKey();
            if (key == null || !key.isPresent()) continue;
            Identifier id = key.get().identifier();
            if (id != null && "lunge".equals(id.getPath())) return enchantments.getLevel(holder);
        }
        return 0;
    }

    // ── Géométrie des barres vanilla ──────────────────────────────────────
    //
    // Reprise de Gui : la barre de faim occupe 10 icônes de 9 px espacées de
    // 8, alignées à droite sur (largeurGUI / 2 + 91), à 39 px du bas ; la
    // barre de vie est son miroir à gauche. Recalculée à chaque frame plutôt
    // que mise en cache : elle dépend de la taille de fenêtre ET de l'échelle
    // GUI, qui changent toutes deux en cours de partie.
    private static final int ICONS = 10;
    private static final float ICON_W = 9f;
    private static final float ICON_STEP = 8f;
    private static final float BAR_HALF_WIDTH = 91f;
    private static final float BAR_BOTTOM_OFFSET = 39f;

    /** Épuisement maximum avant que le jeu ne le convertisse en perte de saturation. */
    private static final float MAX_EXHAUSTION = 4f;

    private static final UiColor SATURATION_COLOR = new UiColor(255, 190, 60, 255);
    private static final UiColor SATURATION_GHOST = new UiColor(255, 230, 150, 255);
    private static final UiColor EXHAUSTION_COLOR = new UiColor(255, 255, 255, 190);
    private static final UiColor FOOD_GHOST = new UiColor(200, 140, 60, 255);
    private static final UiColor HEALTH_GHOST = new UiColor(240, 80, 80, 255);

    /**
     * Alpha du clignotement des aperçus — même forme d'onde qu'AppleSkin :
     * l'accumulateur oscille entre −0,5 et 1,5, puis est BORNÉ à [0,1]. Ce
     * dépassement volontaire des bornes est ce qui crée les paliers pleins en
     * haut et en bas du cycle, au lieu d'un va-et-vient continu qui ne
     * s'arrêterait jamais sur une valeur lisible.
     *
     * <p>BUG TROUVÉ (retour utilisateur 2026-09-01, « le clignotement est
     * différent selon le FPS ») : AppleSkin avance son accumulateur de 0,125
     * par TICK CLIENT, donc 20 fois par seconde, quel que soit le FPS
     * ({@code HUDOverlayHandler.onClientTick}). On reprenait ce 0,125 tel
     * quel — mais {@code ModuleRegistry.tickAll()} est appelé une fois par
     * FRAME RENDUE, pas par tick de jeu (voir {@code
     * GlobalUiRenderMixin261}) : à 130 FPS le cycle tournait 6,5 fois trop
     * vite, et sa vitesse suivait le compteur d'images. Corrigé en mesurant
     * le temps réellement écoulé — la durée d'un cycle est donc exactement
     * {@link #blinkSeconds}, stable quel que soit le FPS.
     *
     * <p>Un cycle complet parcourt 4 unités (−0,5 → 1,5 → −0,5). La valeur
     * par défaut de 1,6 s redonne EXACTEMENT la cadence d'AppleSkin
     * (4 unités ÷ 1,6 s = 2,5 unités/s = 0,125 × 20).
     */
    private float flashAccumulator = 0f;
    private float flashDirection = 1f;
    private long lastFlashNanos;

    /** Unités d'accumulateur parcourues sur un cycle complet — voir {@link #flashAccumulator}. */
    private static final float FLASH_UNITS_PER_CYCLE = 4f;

    @Override
    public void onTick() {
        long now = System.nanoTime();
        float dt = lastFlashNanos == 0L ? 0f : (now - lastFlashNanos) / 1_000_000_000f;
        lastFlashNanos = now;
        // Borne haute : après une pause (fenêtre en arrière-plan, chargement),
        // un dt de plusieurs secondes ferait sauter le cycle d'un coup.
        if (dt > 0.25f) dt = 0.25f;
        if (dt > 0f) {
            flashAccumulator += flashDirection * (FLASH_UNITS_PER_CYCLE / Math.max(0.1f, blinkSeconds)) * dt;
        }
        if (flashAccumulator >= 1.5f) { flashAccumulator = 1.5f; flashDirection = -1f; }
        else if (flashAccumulator <= -0.5f) { flashAccumulator = -0.5f; flashDirection = 1f; }

        // L'estimation se met à jour au TICK (20/s, la cadence à laquelle le
        // serveur fait lui-même sa comptabilité) et non à la frame, sinon elle
        // dépendrait du nombre d'images par seconde.
        if (!estimateSaturation) return;
        try {
            LocalPlayer player = PlayerData.player();
            if (player == null) return;
            FoodData foodData = player.getFoodData();
            if (!(AccessorRegistry.get(AccessPoint.FOOD_LEVEL, foodData) instanceof Number)) return;
            estimator.tick(player,
                AccessorRegistry.getInt(AccessPoint.FOOD_LEVEL, foodData, 0),
                AccessorRegistry.getFloat(AccessPoint.FOOD_SATURATION, foodData, 0f),
                AccessorRegistry.getFloat(AccessPoint.FOOD_EXHAUSTION, foodData, 0f));
        } catch (Throwable t) {
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                LauncherLog.err("[SaturationModule] onTick: " + t);
            }
        }
    }

    private static boolean tickErrorLogged;

    private float flashAlpha() {
        return Math.max(0f, Math.min(1f, flashAccumulator));
    }

    /**
     * Rejoue côté client la comptabilité d'épuisement du serveur, pour que la
     * saturation descende CONTINÛMENT au lieu de tomber d'un coup à la
     * réception d'un paquet — voir la javadoc de classe pour le pourquoi.
     *
     * <h2>Principe</h2>
     *
     * Deux mouvements par tick :
     * <ol>
     *   <li><b>recalage</b> — si la faim ou la saturation du client ont bougé,
     *       c'est que le serveur vient de parler : on jette l'estimation et on
     *       repart de la vérité ;</li>
     *   <li><b>accumulation</b> — sinon, on ajoute l'épuisement des actions
     *       observables, et on applique la règle de {@code FoodData.tick()} :
     *       à 4 d'épuisement, retrancher 4 et consommer 1 de saturation.</li>
     * </ol>
     *
     * <h2>Ce qui est couvert, ce qui ne l'est pas</h2>
     *
     * Couvert : sprint, nage, déplacement dans l'eau, saut (et saut sprinté,
     * quatre fois plus cher), régénération naturelle (6 par PV, de loin le
     * poste dominant quand elle tourne) et dégâts reçus.
     *
     * <p>Non couvert, faute d'être observable sans hook dédié : casser un bloc
     * (0,005) et attaquer (0,1). Deux termes petits devant le sprint, et dont
     * l'absence fait sous-estimer la consommation — l'estimation retarde donc
     * un peu, elle n'avance jamais à tort. Le recalage rattrape l'écart au
     * paquet suivant.
     *
     * <p>Les taux viennent de {@code Player.checkMovementStatistics} et de la
     * boucle de régénération de {@code FoodData.tick()}.
     */
    private static final class Estimator {
        /** Épuisement à atteindre pour consommer 1 point de saturation. */
        private static final float EXHAUSTION_PER_SATURATION = 4f;
        private static final float SPRINT_PER_BLOCK = 0.1f;
        private static final float SWIM_PER_BLOCK = 0.01f;
        private static final float JUMP = 0.05f;
        private static final float SPRINT_JUMP = 0.2f;
        private static final float REGEN_PER_HEALTH = 6f;
        private static final float DAMAGE_TAKEN = 0.1f;

        private float saturation;
        private float exhaustion;

        private int lastServerFood = Integer.MIN_VALUE;
        private float lastServerSaturation = Float.NaN;

        private double lastX, lastY, lastZ;
        private float lastHealth = Float.NaN;
        private boolean wasOnGround;
        private boolean hasPosition;

        float saturation() { return saturation; }

        void tick(LocalPlayer player, int serverFood, float serverSaturation, float serverExhaustion) {
            // ── 1. Recalage ───────────────────────────────────────────────
            // Comparaison EXACTE et non à epsilon près : ces deux valeurs ne
            // changent côté client que par désérialisation d'un paquet, jamais
            // par un calcul flottant local. Un changement, si minime soit-il,
            // veut dire que le serveur a parlé.
            if (serverFood != lastServerFood || serverSaturation != lastServerSaturation) {
                lastServerFood = serverFood;
                lastServerSaturation = serverSaturation;
                saturation = serverSaturation;
                exhaustion = serverExhaustion;
            }

            double x = player.getX(), y = player.getY(), z = player.getZ();
            float health = player.getHealth();
            boolean onGround = player.onGround();

            if (hasPosition) {
                // ── 2. Accumulation ───────────────────────────────────────
                double dx = x - lastX, dy = y - lastY, dz = z - lastZ;

                // En monture, c'est elle qui se déplace : le joueur ne dépense
                // rien, alors que la distance parcourue, elle, est bien réelle.
                if (!player.isPassenger()) {
                    exhaustion += movementExhaustion(player, dx, dy, dz);
                    // Saut : quitter le sol en montant. Le test sur dy évite de
                    // compter une chute ou un pas dans le vide comme un saut.
                    if (wasOnGround && !onGround && dy > 0.0) {
                        exhaustion += player.isSprinting() ? SPRINT_JUMP : JUMP;
                    }
                }

                if (!Float.isNaN(lastHealth)) {
                    float delta = health - lastHealth;
                    if (delta > 0f && serverFood >= 18) {
                        // Régénération naturelle : 6 d'épuisement par PV rendu.
                        // Un soin de potion serait compté à tort, mais il est
                        // rare et l'écart se rattrape au recalage suivant.
                        exhaustion += delta * REGEN_PER_HEALTH;
                    } else if (delta < 0f) {
                        exhaustion += DAMAGE_TAKEN;
                    }
                }

                // ── 3. Règle de FoodData.tick() ───────────────────────────
                // `while` et non `if` : un tick de forte dépense (régénération
                // sur plusieurs PV) peut franchir le seuil plusieurs fois.
                while (exhaustion >= EXHAUSTION_PER_SATURATION) {
                    exhaustion -= EXHAUSTION_PER_SATURATION;
                    saturation = Math.max(0f, saturation - 1f);
                }
            }

            lastX = x; lastY = y; lastZ = z;
            lastHealth = health;
            wasOnGround = onGround;
            hasPosition = true;
        }

        /**
         * Seuls le sprint et l'eau coûtent quelque chose : marcher, s'accroupir
         * et tomber sont GRATUITS depuis la 1.9 — c'est la confusion la plus
         * courante sur cette mécanique, et la compter ferait fondre
         * l'estimation bien trop vite.
         */
        /**
         * Coup de lance chargé — voir {@link SaturationModule#onPiercingAttack}.
         * Appelé depuis le hook, hors du tick : l'épuisement est simplement
         * ajouté à l'accumulateur, la conversion en saturation se fera au tick
         * suivant comme pour tout le reste.
         */
        void addExhaustion(float amount) {
            exhaustion += amount;
        }

        private float movementExhaustion(LocalPlayer player, double dx, double dy, double dz) {
            if (player.isSwimming()) {
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                return (float) (d * SWIM_PER_BLOCK);
            }
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (player.isInWater()) return (float) (horizontal * SWIM_PER_BLOCK);
            if (player.isSprinting()) return (float) (horizontal * SPRINT_PER_BLOCK);
            return 0f;
        }
    }

    private final Estimator estimator = new Estimator();

    /**
     * Dessiné DANS la passe GUI de vanilla, donc au-dessus des barres qui
     * viennent d'y être extraites et sous le chat — voir
     * {@code LauncherModule.onRenderInVanillaGui}. Pas de chemin après
     * présentation : il passerait par-dessus le chat, et de toute façon les
     * accessors dont ce module dépend n'existent que sur ce bracket.
     */
    /**
     * Ces indicateurs sont COLLÉS aux barres vanilla, qui restent dessinées
     * derrière les écrans : ils doivent l'être aussi. Sans ça, ouvrir le tchat
     * ou l'inventaire faisait disparaître la saturation d'une barre de faim
     * pourtant toujours visible — signalé par l'utilisateur.
     */
    @Override
    public boolean renderInVanillaGuiWhenScreenOpen() {
        return true;
    }

    @Override
    public void onRenderInVanillaGui(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            LocalPlayer player = PlayerData.player();
            if (player == null) { reportOnce("joueur null"); return; }
            // Ni faim ni vie affichées dans ces modes : nos overlays se
            // dessineraient dans le vide, sous la hotbar.
            if (player.isCreative()) { reportOnce("mode créatif — barres vanilla absentes"); return; }
            if (player.isSpectator()) { reportOnce("mode spectateur — barres vanilla absentes"); return; }

            FoodData foodData = player.getFoodData();
            if (foodData == null) { reportOnce("getFoodData() null"); return; }
            // La VALEUR, pas isBound() : un accès déclaré par la tranche peut
            // rester sans réponse si son accessor n'a pas été tissé, et
            // afficher des barres à 0 serait pire que ne rien afficher.
            Object foodLevelValue = AccessorRegistry.get(AccessPoint.FOOD_LEVEL, foodData);
            if (!(foodLevelValue instanceof Number)) {
                // Le cas le plus probable d'un « rien ne s'affiche » muet :
                // l'accès n'a pas répondu (pas de liaison pour cette version,
                // ou accessor non tissé). Sans ce log, indiscernable d'un
                // problème de coordonnées.
                reportOnce("FOOD_LEVEL sans réponse — reçu " + foodData.getClass().getName());
                return;
            }

            int foodLevel = ((Number) foodLevelValue).intValue();
            // L'ESTIMATION, pas la valeur brute — voir Estimator. Elle vaut
            // exactement la valeur serveur au moment où celui-ci parle, et
            // continue de descendre entre deux paquets.
            float saturation = estimateSaturation ? estimator.saturation()
                : AccessorRegistry.getFloat(AccessPoint.FOOD_SATURATION, foodData, 0f);
            float exhaustion = AccessorRegistry.getFloat(AccessPoint.FOOD_EXHAUSTION, foodData, 0f);

            float scale = UiVanillaItemRenderer.guiScale(vpWidth);
            if (scale <= 0f) { reportOnce("échelle GUI invalide: " + scale); return; }

            // Dimensions GUI EXACTES de vanilla plutôt que reconstruites par
            // division — voir barRight ci-dessous pour ce que ça corrige.
            // Repli sur la reconstruction si la fenêtre n'est pas résolvable
            // (bracket sans accessor) : c'est l'ancien comportement.
            Window window = ClientData.window();
            int guiW = window != null ? window.getGuiScaledWidth() : Math.round(vpWidth / scale);
            int guiH = window != null ? window.getGuiScaledHeight() : Math.round(vpHeight / scale);
            if (guiW <= 0 || guiH <= 0) { reportOnce("dimensions GUI invalides: " + guiW + "x" + guiH); return; }
            float guiWidth = guiW;

            // BUG TROUVÉ (retour utilisateur 2026-08-31, « rien ne s'affiche ») :
            // ce repère-ci est celui du MOTEUR — pixels de framebuffer, Y vers
            // le HAUT, origine en bas à gauche — alors que la géométrie de
            // vanilla (top = hauteurGUI - 39) est en Y vers le BAS. La
            // première version reprenait la formule vanilla telle quelle, ce
            // qui plaçait toute la rangée à 39 px du HAUT de l'écran, au-dessus
            // du ciel, au lieu de 39 px du bas.
            //
            // Conversion : y_moteur = hauteurFB - y_gui × échelle. Les icônes
            // occupent guiY ∈ [guiH-39, guiH-30], donc ici y ∈ [30×éch, 39×éch]
            // — la hauteur d'écran s'annule, il ne reste que les constantes.
            // BUG TROUVÉ (retour utilisateur 2026-09-01, « revoir l'alignement
            // de la texture ») : vanilla place la barre à
            // {@code largeurGUI / 2 + 91} avec une division ENTIÈRE (Gui.
            // renderFood, largeurGUI est un int). On divisait en flottant, ce
            // qui décale tout l'overlay d'un demi-pixel GUI dès que la largeur
            // GUI est IMPAIRE — soit 2 à 3 pixels d'écran à l'échelle 4-6, et
            // seulement dans certaines tailles de fenêtre, d'où un défaut qui
            // apparaît et disparaît au redimensionnement.
            float barRight = (guiW / 2 + BAR_HALF_WIDTH) * scale;
            // L'axe VERTICAL a son propre rapport : la hauteur GUI est
            // arrondie au SUPÉRIEUR indépendamment de la largeur, donc
            // vpHeight/guiH n'égale pas exactement vpWidth/guiW. Sous-pixel,
            // mais gratuit à corriger une fois guiH connu.
            float scaleY = (float) vpHeight / guiH;
            float barBottom = (BAR_BOTTOM_OFFSET - ICON_W) * scaleY;

            reportOnce("OK", "OK — fb=" + vpWidth + "x" + vpHeight + " éch=" + scale
                + " barRight=" + barRight + " barBottom=" + barBottom
                + " faim=" + foodLevel + " sat=" + saturation + " épuis=" + exhaustion);

            FoodProperties held = heldFood(player);

            if (showSaturation) {
                drawSaturation(renderer, saturation, barRight, barBottom, scale, vpWidth, vpHeight);
            }
            if (showExhaustion) {
                drawExhaustion(renderer, exhaustion, barRight, barBottom, scale, vpWidth, vpHeight);
            }
            if (showFoodPreview && held != null) {
                drawFoodPreview(renderer, held, foodLevel, saturation, barRight, barBottom, scale, vpWidth, vpHeight);
            }
            if (showHealthPreview && held != null) {
                drawHealthPreview(renderer, player, held, foodLevel, saturation,
                    guiWidth, barBottom, scale, vpWidth, vpHeight);
            }
        } catch (Throwable t) {
            if (!drawErrorLogged) {
                drawErrorLogged = true;
                LauncherLog.err("[SaturationModule] onRenderInVanillaGui: " + t);
            }
        }
    }

    private static boolean drawErrorLogged;

    // ── Sprites vanilla (aperçus fantômes) ────────────────────────────────
    //
    // AppleSkin dessine ses aperçus avec les VRAIES icônes du jeu — jambon et
    // cœur — simplement rendues translucides et clignotantes. On fait pareil,
    // et c'est aussi la norme du projet : charger le vrai asset plutôt que le
    // recréer. Seuls ses indicateurs de saturation/épuisement utilisent son
    // propre atlas, qui n'a pas d'équivalent vanilla.
    //
    // Chargement paresseux, une fois par sprite, échec compris (la valeur
    // nulle est mémorisée) — même mécanisme que PotionEffectsModule.iconOf.
    private static final String SPRITES = "textures/gui/sprites/hud/";
    private static final java.util.Map<String, java.awt.image.BufferedImage> SPRITE_CACHE =
        new java.util.HashMap<String, java.awt.image.BufferedImage>();

    private static java.awt.image.BufferedImage sprite(String name) {
        if (SPRITE_CACHE.containsKey(name)) return SPRITE_CACHE.get(name);
        java.awt.image.BufferedImage img = load("minecraft", SPRITES + name + ".png", null);
        SPRITE_CACHE.put(name, img);
        return img;
    }

    /**
     * Charge une texture PAR LE GESTIONNAIRE DE RESSOURCES du jeu — donc en
     * appliquant les resource packs.
     *
     * <p>BUG TROUVÉ (retour utilisateur 2026-08-31) : la première version
     * lisait par le classloader, qui sert le contenu du JAR et ignore
     * totalement les packs. Un pack qui redessine la barre de faim laissait
     * donc nos aperçus en icônes vanilla, à côté d'un HUD retexturé.
     * Constaté concrètement sur le pack « Ice Cream » de l'utilisateur, qui
     * surcharge à la fois {@code assets/minecraft/.../hud/food_full.png} ET
     * {@code assets/appleskin/textures/icons.png}.
     *
     * <p>Conséquence voulue : en passant par l'identifiant
     * {@code appleskin:textures/icons.png}, un pack conçu POUR AppleSkin
     * retexture aussi le nôtre, sans rien avoir à adapter.
     *
     * @param fallbackResource ressource de NOTRE jar à utiliser si ni le pack
     *     ni le jeu ne fournissent le fichier ({@code null} s'il n'y en a pas
     *     — c'est le cas des sprites vanilla, qui existent forcément).
     */
    private static java.awt.image.BufferedImage load(String namespace, String path, String fallbackResource) {
        try {
            ResourceManager manager = ClientData.resourceManager();
            if (manager != null) {
                Optional<Resource> res = manager.getResource(Identifier.fromNamespaceAndPath(namespace, path));
                if (res != null && res.isPresent()) {
                    java.io.InputStream in = res.get().open();
                    if (in != null) {
                        try { return javax.imageio.ImageIO.read(in); } finally { in.close(); }
                    }
                }
            }
        } catch (Throwable t) {
            reportOnce("ressource " + namespace + ":" + path + " : " + t);
        }
        if (fallbackResource == null) {
            reportOnce("ressource absente : " + namespace + ":" + path);
            return null;
        }
        // Repli sur notre propre copie embarquée — un atlas AppleSkin qu'aucun
        // pack ne fournit, cas de loin le plus courant.
        try {
            java.io.InputStream in = SaturationModule.class.getResourceAsStream(fallbackResource);
            if (in == null) {
                reportOnce("repli absent du JAR : " + fallbackResource);
                return null;
            }
            try { return javax.imageio.ImageIO.read(in); } finally { in.close(); }
        } catch (Throwable t) {
            reportOnce("repli " + fallbackResource + " : " + t);
            return null;
        }
    }

    /**
     * Vide les images mémorisées — à appeler après un rechargement des
     * ressources (F3+T, changement de pack), sans quoi on continuerait
     * d'afficher les textures de l'ancien pack pour toute la session.
     */
    public static void invalidateTextures() {
        SPRITE_CACHE.clear();
        atlas = null;
        atlasLoaded = false;
    }

    // ── Atlas AppleSkin (saturation + épuisement) ─────────────────────────
    //
    // Ces deux indicateurs n'ont AUCUN équivalent vanilla : ce sont des
    // données que l'interface du jeu ne montre nulle part, donc sans sprite à
    // réutiliser. On embarque celui d'AppleSkin — The Unlicense, domaine
    // public, copie et redistribution explicitement autorisées. Voir
    // resources/textures/README-appleskin.txt pour l'origine et la
    // disposition, relevée en décodant le PNG.
    /** Notre copie embarquée, utilisée seulement si aucun pack ne fournit {@code appleskin:textures/icons.png}. */
    private static final String ATLAS_RESOURCE = "/textures/appleskin_icons.png";
    /** L'identifiant D'APPLESKIN, pas le nôtre : c'est ce qui rend nos indicateurs retexturables par les packs déjà écrits pour lui. */
    private static final String ATLAS_NAMESPACE = "appleskin";
    private static final String ATLAS_PATH = "textures/icons.png";
    /** Largeur en pixels d'atlas de la barre d'épuisement — elle se révèle par la DROITE, comme chez AppleSkin. */
    private static final int ATLAS_EXHAUSTION_WIDTH = 81;
    private static final int ATLAS_EXHAUSTION_Y = 18;
    /** Quatre paliers de remplissage : vide, quart, moitié, plein. */
    private static final int ATLAS_SATURATION_STEPS = 4;

    private static java.awt.image.BufferedImage atlas;
    private static boolean atlasLoaded;

    private static java.awt.image.BufferedImage atlas() {
        if (atlasLoaded) return atlas;
        atlasLoaded = true;
        atlas = load(ATLAS_NAMESPACE, ATLAS_PATH, ATLAS_RESOURCE);
        return atlas;
    }

    /**
     * Découpe et mémorise une sous-image de l'atlas.
     *
     * <p>Le découpage se fait ICI plutôt que par des UV au moment du dessin :
     * {@code drawIcon} prend une image entière, et l'atlas d'icônes du moteur
     * indexe par clé. Chaque sous-image n'est donc copiée sur le GPU qu'une
     * fois, à sa première apparition.
     */
    private static java.awt.image.BufferedImage region(String key, int x, int y, int w, int h) {
        java.awt.image.BufferedImage cached = SPRITE_CACHE.get(key);
        if (cached != null || SPRITE_CACHE.containsKey(key)) return cached;
        java.awt.image.BufferedImage src = atlas();
        java.awt.image.BufferedImage sub = null;
        try {
            if (src != null && x >= 0 && y >= 0 && w > 0 && h > 0
                && x + w <= src.getWidth() && y + h <= src.getHeight()) {
                sub = src.getSubimage(x, y, w, h);
            }
        } catch (Throwable t) {
            reportOnce("découpe atlas " + key + " : " + t);
        }
        SPRITE_CACHE.put(key, sub);
        return sub;
    }

    /**
     * Une rangée d'icônes 9×9 fantômes, de {@code fromHalves} à
     * {@code toHalves} demi-points.
     *
     * <p>Deux passes comme AppleSkin — un fond à un quart de l'opacité puis
     * l'avant-plan à l'opacité pleine : la première assoit l'icône sur le
     * décor, la seconde la fait pulser. Une passe unique donnerait un fantôme
     * qui disparaît complètement au creux du clignotement.
     *
     * @param rightAligned {@code true} pour la faim (les icônes se remplissent
     *     depuis la droite), {@code false} pour la vie (depuis la gauche).
     */
    private void drawGhostIcons(UiRenderer renderer, String fullSprite, String halfSprite,
                                float fromHalves, float toHalves, float origin, float rowBottom,
                                boolean rightAligned, float alpha, float scale, int vpWidth, int vpHeight) {
        java.awt.image.BufferedImage full = sprite(fullSprite);
        java.awt.image.BufferedImage half = sprite(halfSprite);
        if (full == null || half == null) return;

        float size = ICON_W * scale;
        int firstIcon = (int) Math.floor(fromHalves / 2f);
        int lastIcon = (int) Math.ceil(toHalves / 2f) - 1;

        for (int icon = Math.max(0, firstIcon); icon <= Math.min(ICONS - 1, lastIcon); icon++) {
            // Ne rien dessiner sur un demi-point DÉJÀ acquis : l'aperçu ne
            // montre que le gain, pas ce qu'on a déjà.
            float iconStart = icon * 2f;
            if (iconStart + 2f <= fromHalves) continue;

            boolean isFull = toHalves >= iconStart + 2f;
            java.awt.image.BufferedImage img = isFull ? full : half;
            String key = SPRITES + (isFull ? fullSprite : halfSprite);

            float x = rightAligned
                ? origin - icon * ICON_STEP * scale - size
                : origin + icon * ICON_STEP * scale;

            renderer.drawIcon(key, img, x, rowBottom, size, size, alpha * 0.25f, vpWidth, vpHeight);
            renderer.drawIcon(key, img, x, rowBottom, size, size, alpha, vpWidth, vpHeight);
        }
    }

    /**
     * Journalise UNE fois par raison DISTINCTE — pas une fois tout court :
     * appelé à chaque frame, il faut à la fois éviter le déluge et ne pas
     * masquer un second motif d'échec derrière le premier. C'est ce qui
     * manquait à la première version : toutes ses sorties anticipées étaient
     * muettes, donc un « rien ne s'affiche » ne disait pas s'il venait d'un
     * accessor non tissé, d'un mode de jeu, ou de coordonnées hors écran.
     */
    private static String lastReport;

    private static void reportOnce(String reason) {
        reportOnce(reason, reason);
    }

    /**
     * BUG TROUVÉ (2026-09-04, écart de FPS d'un facteur deux avec un launcher
     * concurrent) : la ligne « OK » embarquait faim/saturation/épuisement —
     * des valeurs qui changent en continu. La déduplication portant sur le
     * texte complet, elle ne dédupliquait rien : le module journalisait à
     * chaque frame, et chaque ligne coûtait une ouverture de fichier (voir
     * {@code LauncherLog.toFile}). La clé doit être stable ; seul le message
     * affiché peut varier.
     */
    private static void reportOnce(String key, String message) {
        if (key.equals(lastReport)) return;
        lastReport = key;
        LauncherLog.info("[SaturationModule] " + message);
    }

    /**
     * Valeurs nutritionnelles de l'aliment tenu — main principale d'abord,
     * main secondaire ensuite (l'ordre de vanilla pour décider quoi consommer).
     * {@code null} si aucune des deux ne tient de nourriture, ou si l'aliment
     * n'est PAS consommable maintenant.
     *
     * <p>BUG TROUVÉ (retour utilisateur 2026-08-31) : « ça nous fait clignoter
     * la barre de saturation pour dire qu'elle va augmenter alors que notre
     * barre de bouffe est pleine, donc on ne peut pas manger cet aliment ».
     * La première version affichait l'aperçu dès qu'un aliment était en main,
     * sans jamais demander s'il était consommable — elle promettait donc un
     * gain impossible.
     *
     * <p>{@code canEat(canAlwaysEat)} est la garde qu'utilise vanilla lui-même
     * avant de consommer : faux barre pleine, sauf pour un aliment marqué
     * {@code canAlwaysEat} (pomme dorée, ragoût suspect…), qui se mange à tout
     * moment et dont l'aperçu reste donc légitime. Filtrer ici plutôt qu'à
     * chaque appelant éteint d'un coup les trois aperçus (faim, saturation,
     * cœurs), qui n'ont aucune raison de diverger sur ce point.
     */
    private FoodProperties heldFood(LocalPlayer player) {
        FoodProperties main = edible(player, foodOf(player.getItemInHand(InteractionHand.MAIN_HAND)));
        if (main != null) return main;
        return edible(player, foodOf(player.getItemInHand(InteractionHand.OFF_HAND)));
    }

    private FoodProperties edible(LocalPlayer player, FoodProperties food) {
        if (food == null) return null;
        try {
            return player.canEat(food.canAlwaysEat()) ? food : null;
        } catch (Throwable t) {
            if (!canEatErrorLogged) {
                canEatErrorLogged = true;
                LauncherLog.err("[SaturationModule] canEat: " + t);
            }
            return null;
        }
    }

    private static boolean canEatErrorLogged;

    /**
     * Type de composant d'item servi par la tranche active, ou {@code null}
     * si l'accès n'est pas lié.
     *
     * <p>Le cast non vérifié est inévitable : {@link AccessorRegistry} rend un
     * {@code Object}, et {@code DataComponentType} est générique. Il est sûr
     * ici parce que chaque {@link AccessPoint} de cette famille désigne UN
     * champ précis, dont le paramètre de type est connu à l'écriture de
     * l'appel — c'est exactement ce que faisait déjà l'accessor, dont la
     * signature portait le générique.
     */
    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.core.component.DataComponentType<T> componentType(AccessPoint point) {
        Object type = AccessorRegistry.get(point);
        return type instanceof net.minecraft.core.component.DataComponentType
            ? (net.minecraft.core.component.DataComponentType<T>) type : null;
    }

    private FoodProperties foodOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            net.minecraft.core.component.DataComponentType<FoodProperties> type =
                componentType(AccessPoint.COMPONENT_TYPE_FOOD);
            return type == null ? null : stack.get(type);
        } catch (Throwable t) {
            if (!foodErrorLogged) {
                foodErrorLogged = true;
                LauncherLog.err("[SaturationModule] foodOf: " + t);
            }
            return null;
        }
    }

    private static boolean foodErrorLogged;

    /**
     * Saturation en liseré SOUS le bord haut des icônes de faim, un segment
     * par demi-point (20 demi-points pour 10 icônes, exactement la granularité
     * d'AppleSkin). Le dernier segment est tronqué au prorata quand la
     * saturation tombe entre deux demi-points, sinon une saturation de 4,3
     * s'afficherait comme 4,0 et paraîtrait figée en descendant.
     */
    private void drawSaturation(UiRenderer renderer, float saturation,
                                float barRight, float barBottom, float scale, int vpWidth, int vpHeight) {
        drawSaturationRun(renderer, 0f, saturation, barRight, barBottom, 1f, scale, vpWidth, vpHeight);
    }

    /**
     * Les cellules de saturation d'AppleSkin, superposées aux icônes de faim.
     *
     * <p>Chaque icône de faim vaut 2 points de saturation, et l'atlas offre
     * quatre paliers de remplissage — donc un palier tous les 0,5 point. Le
     * palier est choisi par ARRONDI SUPÉRIEUR : dès qu'il reste un fragment de
     * saturation sur une icône, on montre au moins le premier palier plutôt
     * que rien. Arrondir vers le bas ferait disparaître le dernier reste avant
     * qu'il ne soit réellement consommé.
     *
     * @param from début de l'intervalle en points de saturation (0 pour la
     *     valeur réelle ; la saturation courante pour un aperçu, qui reprend
     *     donc là où elle s'arrête).
     */
    private void drawSaturationRun(UiRenderer renderer, float from, float to,
                                   float barRight, float barBottom, float alpha,
                                   float scale, int vpWidth, int vpHeight) {
        if (to <= from || to <= 0f) return;
        float size = ICON_W * scale;

        for (int icon = 0; icon < ICONS; icon++) {
            float iconStart = icon * 2f;             // 2 points de saturation par icône
            float fill = Math.min(2f, to - iconStart);
            if (fill <= 0f) break;                    // au-delà, plus rien à remplir
            if (iconStart + 2f <= from) continue;     // déjà couvert par la partie réelle

            // Paliers EXACTS d'AppleSkin (HUDOverlayHandler.
            // drawSaturationOverlay, source relue le 2026-09-01) : le palier
            // dépend de la fraction de l'ICÔNE remplie, pas d'un découpage
            // régulier en quarts.
            //   ]0 ; 0,25]  → vide      ]0,25 ; 0,5] → quart
            //   ]0,5 ; 1[   → moitié     = 1          → plein
            // On appliquait { ceil(points/0,5) − 1 }, soit des seuils réguliers
            // à 0,25/0,5/0,75 : l'icône passait à « plein » dès trois quarts,
            // alors qu'AppleSkin réserve ce palier à l'icône ENTIÈREMENT
            // remplie. C'est ce qui faisait paraître le liseré en avance d'un
            // cran sur la vraie saturation.
            float iconFill = fill / 2f;               // fraction de l'icône, 0..1
            int step = iconFill >= 1f ? 3
                : iconFill > 0.5f ? 2
                : iconFill > 0.25f ? 1
                : 0;
            if (step > ATLAS_SATURATION_STEPS - 1) step = ATLAS_SATURATION_STEPS - 1;

            String key = "appleskin/sat_" + step;
            java.awt.image.BufferedImage cell = region(key, step * (int) ICON_W, 0, (int) ICON_W, (int) ICON_W);
            if (cell == null) return;                 // atlas absent, déjà journalisé

            float x = barRight - icon * ICON_STEP * scale - size;
            renderer.drawIcon(key, cell, x, barBottom, size, size, alpha, vpWidth, vpHeight);
        }
    }

    /**
     * Trace {@code value} demi-points (2 par icône) en partant de la DROITE,
     * comme la barre de faim elle-même. Facteur commun à la saturation et à
     * son aperçu, qui ne diffèrent que par la couleur et le point de départ.
     */
    private void drawHalfIconRun(UiRenderer renderer, float value, float barRight, float y,
                                 float thickness, float scale, UiColor color, int vpWidth, int vpHeight) {
        drawHalfIconRun(renderer, 0f, value, barRight, y, thickness, scale, color, vpWidth, vpHeight);
    }

    /** Variante à INTERVALLE : dessine de {@code from} à {@code to} demi-points — c'est ce qui permet à un aperçu de commencer là où la valeur réelle s'arrête. */
    private void drawHalfIconRun(UiRenderer renderer, float from, float to, float barRight, float y,
                                 float thickness, float scale, UiColor color, int vpWidth, int vpHeight) {
        float maxHalves = ICONS * 2f;
        from = Math.max(0f, Math.min(maxHalves, from));
        to = Math.max(0f, Math.min(maxHalves, to));
        if (to <= from) return;

        for (int half = (int) Math.floor(from); half < Math.ceil(to); half++) {
            int icon = half / 2;
            boolean rightHalf = (half % 2) == 0; // les demi-points se remplissent de la droite vers la gauche
            float iconRight = barRight - icon * ICON_STEP * scale;
            float halfW = ICON_W * scale * 0.5f;
            float x1 = rightHalf ? iconRight - halfW : iconRight - ICON_W * scale;
            float x2 = rightHalf ? iconRight : iconRight - halfW;

            // Fraction réellement couverte de CE demi-point — c'est elle qui
            // rend la descente continue plutôt que par paliers.
            float coverStart = Math.max(0f, from - half);
            float coverEnd = Math.min(1f, to - half);
            if (coverEnd <= coverStart) continue;
            float w = x2 - x1;
            float sx1 = x2 - w * coverEnd;
            float sx2 = x2 - w * coverStart;

            renderer.drawRoundedRect(sx1, y, sx2, y + thickness, 0f, color, vpWidth, vpHeight);
        }
    }

    /**
     * Épuisement en barre fine SOUS les icônes de faim, largeur proportionnelle
     * — même idée qu'AppleSkin (chez lui : {@code ratio × 81} px, alpha 0,75).
     * Elle se remplit de la droite vers la gauche puis se vide d'un coup : le
     * jeu retranche 4 d'épuisement pour consommer un point de saturation.
     */
    private void drawExhaustion(UiRenderer renderer, float exhaustion,
                                float barRight, float barBottom, float scale, int vpWidth, int vpHeight) {
        float ratio = Math.max(0f, Math.min(1f, exhaustion / MAX_EXHAUSTION));
        if (ratio <= 0f) return;

        // Découpage à la largeur EXACTE d'AppleSkin : la source est prise à
        // (81 - largeur, 18), donc la barre se révèle par la droite. Largeur
        // arrondie au pixel d'atlas, ce qui borne à 81 le nombre de
        // sous-images distinctes — sinon chaque frame en créerait une nouvelle
        // et remplirait l'atlas d'icônes du moteur.
        // TRONCATURE, pas arrondi — AppleSkin fait {@code (int)(ratio * 81)}.
        // Arrondir affichait un pixel de barre de plus dès 0,5, un décalage
        // constant d'un demi-pixel par rapport à la référence.
        int w = (int) (ATLAS_EXHAUSTION_WIDTH * ratio);
        if (w <= 0) return;

        String key = "appleskin/exh_" + w;
        java.awt.image.BufferedImage bar = region(key,
            ATLAS_EXHAUSTION_WIDTH - w, ATLAS_EXHAUSTION_Y, w, (int) ICON_W);
        if (bar == null) return;

        renderer.drawIcon(key, bar, barRight - w * scale, barBottom,
            w * scale, ICON_W * scale, EXHAUSTION_ALPHA, vpWidth, vpHeight);
    }

    /** Opacité de la barre d'épuisement — la valeur d'AppleSkin, reprise telle quelle. */
    private static final float EXHAUSTION_ALPHA = 0.75f;

    /**
     * Aperçu de ce que l'aliment tenu rendrait : la faim en fantôme sur les
     * icônes VIDES à droite du niveau actuel, la saturation en fantôme dans le
     * prolongement du liseré. Les deux clignotent ensemble ({@link #flashAlpha}).
     *
     * <p>La saturation est PLAFONNÉE au niveau de faim résultant, exactement
     * comme {@code FoodData.add} : au-delà, le jeu la jette, et l'aperçu
     * mentirait.
     */
    private void drawFoodPreview(UiRenderer renderer, FoodProperties food, int foodLevel, float saturation,
                                 float barRight, float barBottom, float scale, int vpWidth, int vpHeight) {
        float alpha = flashAlpha();
        if (alpha <= 0.01f) return;

        int restoredFood = Math.min(20, foodLevel + food.nutrition());
        if (restoredFood > foodLevel) {
            // VRAIES icônes de jambon vanilla, comme AppleSkin — le rectangle
            // translucide de la première version ne ressemblait à rien une
            // fois posé sur la barre de faim.
            drawGhostIcons(renderer, "food_full", "food_half",
                foodLevel, restoredFood, barRight, barBottom, true, alpha, scale, vpWidth, vpHeight);
        }

        if (showSaturation) {
            // saturation() est ABSOLUE depuis la 1.20.5 — voir le stub
            // FoodProperties. La traiter comme l'ancien modificateur donnerait
            // un aperçu faux d'un facteur ~10 sur les aliments riches.
            float restoredSat = Math.min(saturation + food.saturation(), restoredFood);
            if (restoredSat > saturation) {
                // Mêmes cellules que l'indicateur réel, simplement translucides
                // et clignotantes — reprises là où la saturation courante
                // s'arrête, jamais redessinées par-dessus elle.
                drawSaturationRun(renderer, saturation, restoredSat, barRight, barBottom,
                    alpha, scale, vpWidth, vpHeight);
            }
        }
    }

    /**
     * Cœurs que la RÉGÉNÉRATION NATURELLE rendrait après avoir mangé, en
     * fantôme au-dessus de la barre de vie.
     *
     * <p>AppleSkin simule tick par tick ; on résout la même mécanique en forme
     * fermée. Au-dessus de 18 de faim le jeu régénère 1 PV en dépensant 6
     * d'épuisement, et chaque tranche de 4 d'épuisement consomme 1 de
     * saturation : un PV coûte donc 6/4 = 1,5 de saturation, d'où
     * {@code PV ≈ saturation / 1,5}, borné par les PV manquants.
     *
     * <p>Approximation assumée sur deux points : la règle de jeu
     * {@code naturalRegeneration} n'est pas lisible côté client sans requête
     * serveur, et l'épuisement déjà accumulé est ignoré (il ne décale le
     * résultat que d'une fraction de cœur).
     */
    private void drawHealthPreview(UiRenderer renderer, LocalPlayer player, FoodProperties food,
                                   int foodLevel, float saturation, float guiWidth, float barBottom,
                                   float scale, int vpWidth, int vpHeight) {
        float alpha = flashAlpha();
        if (alpha <= 0.01f) return;

        int restoredFood = Math.min(20, foodLevel + food.nutrition());
        if (restoredFood < 18) return; // sous ce seuil, aucune régénération

        float restoredSat = Math.min(saturation + food.saturation(), restoredFood);
        float health = player.getHealth();
        float missing = Math.max(0f, player.getMaxHealth() - health);
        float healed = Math.min(missing, restoredSat / 1.5f);
        if (healed <= 0f) return;

        // Barre de vie : miroir gauche de la barre de faim, les cœurs se
        // remplissent de la GAUCHE vers la droite.
        // Division ENTIÈRE comme vanilla — même correctif que barRight, voir
        // sa remarque. {@code guiWidth} porte une valeur entière exacte
        // (largeur GUI de la fenêtre), la conversion ne perd rien.
        float barLeft = ((int) guiWidth / 2 - BAR_HALF_WIDTH) * scale;
        float maxHalves = ICONS * 2f;
        float from = Math.min(maxHalves, health);
        float to = Math.min(maxHalves, health + healed);

        // VRAIS cœurs vanilla. La rangée de vie est à la même hauteur que
        // celle de la faim, elle se remplit juste depuis la GAUCHE.
        drawGhostIcons(renderer, "heart/full", "heart/half",
            from, to, barLeft, barBottom, false, alpha, scale, vpWidth, vpHeight);
    }
}
