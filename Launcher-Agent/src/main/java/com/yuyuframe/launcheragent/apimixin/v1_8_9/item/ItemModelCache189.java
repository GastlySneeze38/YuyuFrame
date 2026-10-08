package com.yuyuframe.launcheragent.apimixin.v1_8_9.item;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Garde sur la carte graphique la géométrie des modèles d'objets.
 *
 * <p><b>Ce que fait vanilla</b> ({@code ItemRenderer.renderBakedItemModel}) :
 * à chaque fois qu'un objet est dessiné — tenu par un joueur, dans ta main,
 * au sol, dans un cadre, dans la barre d'inventaire — il réécrit tous les
 * quads du modèle dans un tampon et l'envoie. Une épée fait une centaine de
 * quads, et un objet enchanté passe trois fois par là (une fois normalement,
 * deux pour le reflet). Mesuré le 2026-10-06 : 93 à 178 µs par joueur équipé,
 * soit 80 % du coût de son dessin.
 *
 * <p><b>Ce que fait cette classe</b> : la première fois, elle laisse vanilla
 * dessiner à l'intérieur d'une liste d'affichage OpenGL, qui enregistre la
 * géométrie. Les fois suivantes, elle rejoue la liste en un appel. Rien n'est
 * recalculé : ce sont les sommets de vanilla.
 *
 * <p><b>La teinte.</b> Vanilla colore chaque quad d'après l'objet
 * ({@code Item.getDisplayColor}) : blanc pour une épée, la couleur du cuir
 * pour une armure teinte, celle du liquide pour une potion. Tous les modèles
 * d'objets plats sont concernés, y compris ceux qui restent blancs — une
 * première version (v1245) les écartait tous du cache et ne gardait que le
 * reflet d'enchantement. La teinte fait donc partie de la clé : une liste par
 * (modèle, couleur passée par vanilla, teintes de l'objet).
 *
 * <p><b>Jamais en cache</b> : un modèle à plus de deux couches teintées, un
 * modèle qui a déjà {@value #MAX_VARIANTS} variantes (armures en cuir de
 * toutes les couleurs), et tout en mode anaglyphe, qui retouche les teintes.
 *
 * <p>Fil de rendu seulement. Vidé au rechargement des ressources, qui refait
 * tous les modèles.
 */
@YarnNamed
public final class ItemModelCache189 {

    private ItemModelCache189() {
    }

    /** La couleur que vanilla passe pour « prends la teinte de l'objet ». */
    private static final int NO_COLOR = -1;

    private static final int MAX_TINT_LAYERS = 2;
    private static final int MAX_VARIANTS = 32;

    /** Marque une clé de teinte réelle, pour qu'elle ne vaille jamais 0 (« pas de teinte »). */
    private static final long TINTED = 1L << 62;

    private static final class Entry {
        /** Les couches dont le modèle prend la teinte, sans doublon ; vide s'il n'en prend aucune. */
        int[] tintLayers = new int[0];
        /** Trop de couches teintées pour tenir dans la clé. */
        boolean uncacheable;

        final int[] colors = new int[MAX_VARIANTS];
        final long[] tints = new long[MAX_VARIANTS];
        final int[] lists = new int[MAX_VARIANTS];
        int count;

        int find(int color, long tint) {
            for (int i = 0; i < this.count; i++) {
                if (this.colors[i] == color && this.tints[i] == tint) {
                    return this.lists[i];
                }
            }
            return 0;
        }
    }

    /** Par identité : deux modèles distincts ne sont jamais « égaux ». */
    private static final IdentityHashMap<Object, Entry> ENTRIES = new IdentityHashMap<>();

    /** Liste en cours d'enregistrement, ou 0. */
    private static int recording;
    private static Entry recordingEntry;
    private static int recordingColor;
    private static long recordingTint;

    /**
     * En tête de {@code renderBakedItemModel}.
     *
     * @return vrai si le modèle a été dessiné depuis le cache : vanilla ne doit alors rien faire
     */
    public static boolean begin(Object modelObject, int color, Object stackObject) {
        // Un enregistrement resté ouvert : vanilla a levé une exception au
        // milieu. On le referme, sinon tout ce qui suit irait dans la liste.
        if (recording != 0) {
            GL11.glEndList();
            GL11.glDeleteLists(recording, 1);
            recording = 0;
        }

        Entry entry = ENTRIES.get(modelObject);
        if (entry == null) {
            entry = new Entry();
            collectTintLayers((BakedModel) modelObject, entry);
            ENTRIES.put(modelObject, entry);
        }
        if (entry.uncacheable) {
            return false;
        }

        // Même condition que vanilla pour teindre : couleur neutre ET objet connu.
        long tint = 0L;
        if (entry.tintLayers.length != 0 && color == NO_COLOR && stackObject != null) {
            if (MinecraftClient.getInstance().options.anaglyph3d) {
                return false;
            }
            ItemStack stack = (ItemStack) stackObject;
            Item item = stack.getItem();
            tint = TINTED;
            for (int i = 0; i < entry.tintLayers.length; i++) {
                tint |= (long) (item.getDisplayColor(stack, entry.tintLayers[i]) & 0xFFFFFF) << (24 * i);
            }
        }

        int list = entry.find(color, tint);
        if (list != 0) {
            replay(list);
            return true;
        }
        if (entry.count == MAX_VARIANTS) {
            return false;
        }

        list = GL11.glGenLists(1);
        if (list == 0) {
            return false;
        }
        recording = list;
        recordingEntry = entry;
        recordingColor = color;
        recordingTint = tint;
        GL11.glNewList(list, GL11.GL_COMPILE);
        return false;
    }

    /** En sortie de {@code renderBakedItemModel} : clôt l'enregistrement et dessine ce qui vient d'être enregistré. */
    public static void end() {
        if (recording == 0) {
            return;
        }
        int list = recording;
        recording = 0;
        GL11.glEndList();
        Entry entry = recordingEntry;
        recordingEntry = null;
        entry.colors[entry.count] = recordingColor;
        entry.tints[entry.count] = recordingTint;
        entry.lists[entry.count] = list;
        entry.count++;
        replay(list);
    }

    /**
     * Rejoue un enregistrement. Il porte une couleur par sommet : après lui, la
     * couleur courante d'OpenGL est celle du dernier sommet, pas celle que
     * {@code GlStateManager} a en mémoire. Vanilla l'oublie après chaque dessin
     * coloré ({@code BufferRenderer.draw}) ; rejouer court-circuite ce dessin,
     * donc on le fait ici. Sans ça, le {@code color(1, 1, 1, 1)} suivant était
     * sauté et ce qui venait après (le corps d'un joueur, la barre d'objets)
     * prenait la couleur du dernier objet dessiné.
     */
    private static void replay(int list) {
        GL11.glCallList(list);
        GlStateManager.clearColor();
    }

    /** Au rechargement des ressources : les modèles sont refaits, les listes ne valent plus rien. */
    public static void clear() {
        if (recording != 0) {
            GL11.glEndList();
            GL11.glDeleteLists(recording, 1);
            recording = 0;
            recordingEntry = null;
        }
        for (Entry entry : ENTRIES.values()) {
            for (int i = 0; i < entry.count; i++) {
                GL11.glDeleteLists(entry.lists[i], 1);
            }
        }
        ENTRIES.clear();
    }

    private static void collectTintLayers(BakedModel model, Entry entry) {
        Direction[] directions = Direction.values();
        for (int i = 0; i < directions.length && !entry.uncacheable; i++) {
            collectTintLayers(model.getByDirection(directions[i]), entry);
        }
        if (!entry.uncacheable) {
            collectTintLayers(model.getQuads(), entry);
        }
    }

    private static void collectTintLayers(List<?> quads, Entry entry) {
        for (int q = 0; q < quads.size(); q++) {
            BakedQuad quad = (BakedQuad) quads.get(q);
            if (!quad.hasColor()) {
                continue;
            }
            int layer = quad.getColorIndex();
            boolean known = false;
            for (int i = 0; i < entry.tintLayers.length; i++) {
                if (entry.tintLayers[i] == layer) {
                    known = true;
                }
            }
            if (known) {
                continue;
            }
            if (entry.tintLayers.length == MAX_TINT_LAYERS) {
                entry.uncacheable = true;
                return;
            }
            entry.tintLayers = java.util.Arrays.copyOf(entry.tintLayers, entry.tintLayers.length + 1);
            entry.tintLayers[entry.tintLayers.length - 1] = layer;
        }
    }
}
