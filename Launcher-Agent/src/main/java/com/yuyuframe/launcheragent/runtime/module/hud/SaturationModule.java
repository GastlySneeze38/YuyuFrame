package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.render.UiVanillaItemRenderer;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.DataComponentsAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.FoodDataAccessor261;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Optional;
import java.util.Set;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
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
 * public ({@code DataComponents.FOOD}) — consigne explicite, voir la javadoc
 * de {@link FoodDataAccessor261}. Ce module ne fait donc rien hors 26.1.2,
 * seul bracket où ces accessors existent ; il s'efface proprement ailleurs.
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
    }

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
            if (!(foodData instanceof FoodDataAccessor261)) return;
            if (((FoodDataAccessor261) foodData).la$foodLevel() < LUNGE_MIN_FOOD) return;

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
        ItemEnchantments enchantments = stack.get(DataComponentsAccessor261.la$enchantments());
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
     * Alpha du clignotement des aperçus — repris tel quel d'AppleSkin :
     * l'accumulateur oscille entre −0,5 et 1,5 par pas de 0,125 par tick,
     * puis est BORNÉ à [0,1]. Ce dépassement volontaire des bornes est ce qui
     * crée les paliers pleins en haut et en bas du cycle, au lieu d'un
     * va-et-vient continu qui ne s'arrêterait jamais sur une valeur lisible.
     */
    private float flashAccumulator = 0f;
    private float flashDirection = 1f;

    @Override
    public void onTick() {
        flashAccumulator += flashDirection * 0.125f;
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
            if (!(foodData instanceof FoodDataAccessor261)) return;
            FoodDataAccessor261 food = (FoodDataAccessor261) foodData;
            estimator.tick(player, food.la$foodLevel(), food.la$saturationLevel(), food.la$exhaustionLevel());
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
            if (!(foodData instanceof FoodDataAccessor261)) {
                // Le cas le plus probable d'un « rien ne s'affiche » muet :
                // l'accessor n'a pas été tissé. Sans ce log, indiscernable
                // d'un problème de coordonnées.
                reportOnce("FoodDataAccessor261 NON TISSÉ sur " + foodData.getClass().getName());
                return;
            }
            FoodDataAccessor261 food = (FoodDataAccessor261) foodData;

            int foodLevel = food.la$foodLevel();
            // L'ESTIMATION, pas la valeur brute — voir Estimator. Elle vaut
            // exactement la valeur serveur au moment où celui-ci parle, et
            // continue de descendre entre deux paquets.
            float saturation = estimateSaturation ? estimator.saturation() : food.la$saturationLevel();
            float exhaustion = food.la$exhaustionLevel();

            float scale = UiVanillaItemRenderer.guiScale(vpWidth);
            if (scale <= 0f) { reportOnce("échelle GUI invalide: " + scale); return; }
            float guiWidth = vpWidth / scale;

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
            float barRight = (guiWidth / 2f + BAR_HALF_WIDTH) * scale;
            float barBottom = (BAR_BOTTOM_OFFSET - ICON_W) * scale;

            reportOnce("OK — fb=" + vpWidth + "x" + vpHeight + " éch=" + scale
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
        if (reason.equals(lastReport)) return;
        lastReport = reason;
        LauncherLog.info("[SaturationModule] " + reason);
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

    private FoodProperties foodOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            return stack.get(DataComponentsAccessor261.la$food());
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
        if (saturation <= 0f) return;
        float thickness = 1.5f * scale;
        float y = barBottom + ICON_W * scale; // haut des icônes de faim
        drawHalfIconRun(renderer, saturation, barRight, y, thickness, scale, SATURATION_COLOR, vpWidth, vpHeight);
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
        float full = (ICONS - 1) * ICON_STEP * scale + ICON_W * scale;
        float w = full * ratio;
        float thickness = 1f * scale;
        float y = barBottom - thickness - scale;
        renderer.drawRoundedRect(barRight - w, y, barRight, y + thickness, 0f, EXHAUSTION_COLOR, vpWidth, vpHeight);
    }

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
            float y = barBottom + 1f * scale;
            float thickness = ICON_W * scale - 2f * scale;
            drawHalfIconRun(renderer, foodLevel, restoredFood, barRight, y, thickness, scale,
                FOOD_GHOST.multiplyAlpha(alpha * 0.6f), vpWidth, vpHeight);
        }

        if (showSaturation) {
            // saturation() est ABSOLUE depuis la 1.20.5 — voir le stub
            // FoodProperties. La traiter comme l'ancien modificateur donnerait
            // un aperçu faux d'un facteur ~10 sur les aliments riches.
            float restoredSat = Math.min(saturation + food.saturation(), restoredFood);
            if (restoredSat > saturation) {
                float y = barBottom + ICON_W * scale;
                drawHalfIconRun(renderer, saturation, restoredSat, barRight, y, 1.5f * scale, scale,
                    SATURATION_GHOST.multiplyAlpha(alpha), vpWidth, vpHeight);
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
        float barLeft = (guiWidth / 2f - BAR_HALF_WIDTH) * scale;
        float y = barBottom + ICON_W * scale;
        float thickness = 1.5f * scale;
        float maxHalves = ICONS * 2f;
        float from = Math.min(maxHalves, health);
        float to = Math.min(maxHalves, health + healed);
        UiColor color = HEALTH_GHOST.multiplyAlpha(alpha);

        for (int half = (int) Math.floor(from); half < Math.ceil(to); half++) {
            int icon = half / 2;
            boolean leftHalf = (half % 2) == 0;
            float iconLeft = barLeft + icon * ICON_STEP * scale;
            float halfW = ICON_W * scale * 0.5f;
            float x1 = leftHalf ? iconLeft : iconLeft + halfW;
            float x2 = leftHalf ? iconLeft + halfW : iconLeft + ICON_W * scale;

            float coverStart = Math.max(0f, from - half);
            float coverEnd = Math.min(1f, to - half);
            if (coverEnd <= coverStart) continue;
            float w = x2 - x1;
            renderer.drawRoundedRect(x1 + w * coverStart, y, x1 + w * coverEnd, y + thickness,
                0f, color, vpWidth, vpHeight);
        }
    }
}
