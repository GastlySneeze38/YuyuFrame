package com.yuyuframe.launcheragent.apimixin.typed.v1_21_11;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.core.HungerManagerAccessor1211;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.core.MinecraftClientAccessor1211;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.HungerManager;

/**
 * Liaisons {@link AccessPoint} → données du jeu pour la tranche 1.21.11 —
 * pendant d'{@code AccessorBindings261}.
 *
 * <h2>Pourquoi presque aucun accessor Mixin ici</h2>
 *
 * La 26.1.2 passe par un accessor pour CHAQUE champ, parce qu'ils y sont
 * privés. Sur 1.21.11, {@code player}, {@code world}, {@code options},
 * {@code currentScreen}, {@code inGameHud} et {@code mouse} sont PUBLICS
 * (vérifié {@code javap} sur le jar client réel), et {@code window} /
 * {@code session} / {@code resourceManager} ont des getters publics. Du code
 * TYPÉ suffit donc — ni mixin, ni réflexion.
 *
 * <p>Deux exceptions, toutes deux des champs privés PRIMITIFS, donc servies
 * par un accessor classique : {@code currentFps} et l'épuisement du joueur.
 *
 * <h2>Unité de compilation et traduction</h2>
 *
 * Ce fichier vit dans {@code src/main_1_21_11} et nomme les classes du jeu
 * sous leur nom YARN ; {@code YarnNamedRemapper} les traduit au chargement
 * vers les noms du loader actif (son tableau {@code PACKAGES} couvre ce
 * paquet). Ne jamais le déplacer hors de ce paquet : non traduit, il
 * chercherait des classes Yarn inexistantes en jeu.
 *
 * <p>Chargée par NOM depuis {@code AccessorRegistry.ensureInitialized()} —
 * jamais référencée par du code neutre, qui tomberait sinon en
 * {@code NoClassDefFoundError} sur toute autre version.
 *
 * <h2>Accès volontairement NON LIÉS sur cette version</h2>
 *
 * {@code KEYBIND_KEY} (champ {@code boundKey} privé, de type obfusqué),
 * {@code CHAT_ALL_MESSAGES} et {@code CHAT_ADD_MESSAGE} (privés, et leurs
 * paramètres sont des types du jeu), {@code FOG_SET_ENABLED} (le brouillard de
 * cette version passe par {@code FogDataAccessor1211}, pas par un drapeau
 * global). Le registre les signale une fois et sert la valeur de repli — c'est
 * le comportement prévu, pas un échec.
 */
public final class AccessorBindings1211 {

    private AccessorBindings1211() {
    }

    static {
        // ── Client ─────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CLIENT_INSTANCE, (r, a) -> client());
        AccessorRegistry.bind(AccessPoint.CLIENT_PLAYER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.player; });
        AccessorRegistry.bind(AccessPoint.CLIENT_LEVEL, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.world; });
        AccessorRegistry.bind(AccessPoint.CLIENT_OPTIONS, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.options; });
        AccessorRegistry.bind(AccessPoint.CLIENT_USER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getSession(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SCREEN, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.currentScreen; });
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.inGameHud; });
        AccessorRegistry.bind(AccessPoint.CLIENT_WINDOW, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getWindow(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_MOUSE_HANDLER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.mouse; });
        AccessorRegistry.bind(AccessPoint.CLIENT_RESOURCE_MANAGER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getResourceManager(); });
        // Champ statique PRIVÉ : seul accessor Mixin de la classe. Il lève s'il
        // n'est pas tissé (plutôt que de rendre un 0 faux) — AccessorRegistry
        // attrape, journalise une fois, et l'appelant reçoit son repli.
        AccessorRegistry.bind(AccessPoint.CLIENT_FPS, (r, a) -> Integer.valueOf(MinecraftClientAccessor1211.la$fps()));

        // ── Options ────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTIONS_FOV, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getFov(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_SENSITIVITY, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getMouseSensitivity(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_GAMMA, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getGamma(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_HIDE_GUI, (r, a) -> { GameOptions o = options(r); return o == null ? null : Boolean.valueOf(o.hudHidden); });

        // ── Poignée d'option ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE, (r, a) ->
            r instanceof SimpleOption ? ((SimpleOption) r).getValue() : null);
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE_SET, (r, a) -> {
            if (!(r instanceof SimpleOption) || a.length < 1) return null;
            ((SimpleOption) r).setValue(a[0]);
            return null;
        });

        // ── Raccourci clavier ──────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.KEYBIND_IS_DOWN, (r, a) ->
            r instanceof KeyBinding ? Boolean.valueOf(((KeyBinding) r).isPressed()) : null);

        // ── Faim/saturation ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.FOOD_LEVEL, (r, a) ->
            r instanceof HungerManager ? Integer.valueOf(((HungerManager) r).getFoodLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_SATURATION, (r, a) ->
            r instanceof HungerManager ? Float.valueOf(((HungerManager) r).getSaturationLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_EXHAUSTION, (r, a) ->
            r instanceof HungerManagerAccessor1211 ? Float.valueOf(((HungerManagerAccessor1211) r).la$exhaustion()) : null);

        // ── Types de composants d'item (champs STATIQUES publics) ──────────
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_FOOD, (r, a) -> DataComponentTypes.FOOD);
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_ENCHANTMENTS, (r, a) -> DataComponentTypes.ENCHANTMENTS);

        // ── Serveur courant (champs publics) ───────────────────────────────
        AccessorRegistry.bind(AccessPoint.SERVER_IP, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).address : null);
        AccessorRegistry.bind(AccessPoint.SERVER_NAME, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).name : null);
    }

    /**
     * Receveur → client. Receveur {@code null} = « prends l'instance courante »,
     * pour que les appelants neutres n'aient jamais à fabriquer le receveur.
     */
    private static MinecraftClient mc(Object receiver) {
        Object target = receiver != null ? receiver : client();
        return target instanceof MinecraftClient ? (MinecraftClient) target : null;
    }

    /**
     * Instance courante, ou {@code null}. Le {@code Throwable} n'est pas
     * décoratif : {@code getInstance()} peut être appelé avant que la classe ne
     * soit initialisable, et on y récolte alors une erreur de liaison.
     */
    private static MinecraftClient client() {
        try {
            return MinecraftClient.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Receveur → options, {@code null} = celles du client courant. */
    private static GameOptions options(Object receiver) {
        Object target = receiver;
        if (target == null) {
            MinecraftClient c = mc(null);
            target = c == null ? null : c.options;
        }
        return target instanceof GameOptions ? (GameOptions) target : null;
    }
}
