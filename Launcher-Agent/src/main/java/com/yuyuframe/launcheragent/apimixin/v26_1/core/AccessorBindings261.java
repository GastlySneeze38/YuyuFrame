package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

/**
 * Liaisons {@link AccessPoint} → accessors de la tranche 26.1.2 — le SEUL
 * endroit de la tranche qui nomme à la fois un {@link AccessPoint} neutre et
 * un accessor {@code *261}.
 *
 * <h2>Chargée par NOM, jamais référencée</h2>
 *
 * {@code AccessorRegistry.ensureInitialized()} la charge par
 * {@code Class.forName} quand la version détectée est 26.1.2, ce qui exécute
 * le {@code <clinit>} ci-dessous. Personne ne doit la nommer depuis du code
 * neutre : elle référence {@code net.minecraft.client.Minecraft} et consorts,
 * qui n'existent sous ces noms qu'à partir de la ligne 26.1 (MC non obfusqué)
 * — un import depuis {@code runtime/} ferait tomber toute autre tranche en
 * {@code NoClassDefFoundError}.
 *
 * <h2>Écrire la même chose pour une nouvelle version</h2>
 *
 * Copier ce fichier dans {@code apimixin/vXX_Y/core/}, remplacer les accessors
 * par ceux de la tranche, et ajouter la ligne correspondante dans
 * {@code AccessorRegistry.bindingsClassFor()}. Un accès que la tranche ne sait
 * pas servir se laisse simplement NON LIÉ : {@code AccessorRegistry} le
 * signale une fois dans le log et sert la valeur de repli, plutôt que de faire
 * échouer le chargement entier.
 *
 * <p>Chaque {@code instanceof} n'est pas décoratif : il vaut « ce mixin
 * s'est-il réellement tissé ? ». Un accessor non tissé laisse l'objet du jeu
 * sans notre interface, et le cast lèverait — le test le convertit en
 * « non disponible », que le registre traduit en valeur de repli.
 */
public final class AccessorBindings261 {

    private AccessorBindings261() {}

    static {
        // ── Client ─────────────────────────────────────────────────────────
        // getInstance() est une méthode publique statique, pas un champ : pas
        // d'accessor, appel direct. Elle est quand même exposée ici pour que
        // les appelants neutres n'aient jamais à nommer Minecraft eux-mêmes.
        AccessorRegistry.bind(AccessPoint.CLIENT_INSTANCE, (r, a) -> client());
        AccessorRegistry.bind(AccessPoint.CLIENT_PLAYER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$player(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_LEVEL, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$level(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_OPTIONS, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$options(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_USER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$user(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SCREEN, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$screen(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$gui(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_WINDOW, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$window(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_MOUSE_HANDLER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$mouseHandler(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_RESOURCE_MANAGER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$resourceManager(); });
        // Champ STATIQUE : aucun receveur. L'accessor lève s'il n'est pas
        // tissé (plutôt que de renvoyer un 0 faux) — AccessorRegistry.invoke()
        // attrape, journalise une fois, et l'appelant reçoit son repli.
        AccessorRegistry.bind(AccessPoint.CLIENT_FPS, (r, a) -> MinecraftAccessor261.la$fps());

        // ── Options ────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTIONS_FOV, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$fov(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_SENSITIVITY, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$sensitivity(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_GAMMA, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$gamma(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_HIDE_GUI, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : Boolean.valueOf(o.la$hideGui()); });

        // ── Poignée d'option ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE, (r, a) ->
            r instanceof OptionInstanceAccessor261 ? ((OptionInstanceAccessor261) r).la$value() : null);
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE_SET, (r, a) -> {
            if (!(r instanceof OptionInstanceAccessor261) || a.length < 1) return null;
            ((OptionInstanceAccessor261) r).la$setValue(a[0]);
            return null;
        });

        // ── Raccourci clavier ──────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.KEYBIND_IS_DOWN, (r, a) ->
            r instanceof KeyMappingAccessor261 ? Boolean.valueOf(((KeyMappingAccessor261) r).la$isDown()) : null);
        AccessorRegistry.bind(AccessPoint.KEYBIND_KEY, (r, a) ->
            r instanceof KeyMappingAccessor261 ? ((KeyMappingAccessor261) r).la$key() : null);

        // ── Faim/saturation ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.FOOD_LEVEL, (r, a) ->
            r instanceof FoodDataAccessor261 ? Integer.valueOf(((FoodDataAccessor261) r).la$foodLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_SATURATION, (r, a) ->
            r instanceof FoodDataAccessor261 ? Float.valueOf(((FoodDataAccessor261) r).la$saturationLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_EXHAUSTION, (r, a) ->
            r instanceof FoodDataAccessor261 ? Float.valueOf(((FoodDataAccessor261) r).la$exhaustionLevel()) : null);

        // ── Types de composants d'item (champs STATIQUES) ──────────────────
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_FOOD, (r, a) -> DataComponentsAccessor261.la$food());
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_ENCHANTMENTS, (r, a) -> DataComponentsAccessor261.la$enchantments());

        // ── Serveur courant ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.SERVER_IP, (r, a) ->
            r instanceof ServerDataAccessor261 ? ((ServerDataAccessor261) r).la$ip() : null);
        AccessorRegistry.bind(AccessPoint.SERVER_NAME, (r, a) ->
            r instanceof ServerDataAccessor261 ? ((ServerDataAccessor261) r).la$name() : null);

        // ── Chat ───────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CHAT_ALL_MESSAGES, (r, a) ->
            r instanceof ChatComponentAccessor261 ? ((ChatComponentAccessor261) r).la$allMessages() : null);
        AccessorRegistry.bind(AccessPoint.CHAT_ADD_MESSAGE, (r, a) -> {
            if (!(r instanceof ChatComponentAccessor261) || a.length < 4) return null;
            ((ChatComponentAccessor261) r).la$addMessage(
                (Component) a[0],
                (MessageSignature) a[1],
                (GuiMessageSource) a[2],
                (GuiMessageTag) a[3]);
            return null;
        });

        // ── Brouillard (champ STATIQUE en écriture) ────────────────────────
        AccessorRegistry.bind(AccessPoint.FOG_SET_ENABLED, (r, a) -> {
            if (a.length < 1 || !(a[0] instanceof Boolean)) return null;
            FogRendererAccessor261.la$setFogEnabled((Boolean) a[0]);
            return null;
        });
    }

    /**
     * Receveur → accessor {@code Minecraft}. Receveur {@code null} = « prends
     * l'instance courante » : les appelants neutres n'ont ainsi jamais à
     * fabriquer le receveur eux-mêmes, ce qui les obligerait à nommer
     * {@code Minecraft}.
     */
    private static MinecraftAccessor261 mc(Object receiver) {
        Object target = receiver != null ? receiver : client();
        return target instanceof MinecraftAccessor261 ? (MinecraftAccessor261) target : null;
    }

    /**
     * Instance courante du client, ou {@code null}. Le {@code Throwable} n'est
     * pas décoratif : {@code Minecraft.getInstance()} peut être appelé avant
     * que la classe ne soit initialisable, et on y récolte alors une erreur de
     * liaison, pas une exception ordinaire.
     */
    private static Minecraft client() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Receveur → accessor {@code Options}. Receveur {@code null} = les options
     * du client courant, même raison que {@link #mc(Object)}.
     */
    private static OptionsAccessor261 options(Object receiver) {
        Object target = receiver;
        if (target == null) {
            MinecraftAccessor261 client = mc(null);
            target = client == null ? null : client.la$options();
        }
        return target instanceof OptionsAccessor261 ? (OptionsAccessor261) target : null;
    }
}
