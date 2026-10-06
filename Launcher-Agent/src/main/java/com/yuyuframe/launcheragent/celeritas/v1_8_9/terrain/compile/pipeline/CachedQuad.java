package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline;

import com.yuyuframe.launcheragent.celeritas.chunk.sprite.SpriteTransparencyLevel;
import com.yuyuframe.launcheragent.celeritas.model.quad.BakedQuadView;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFlags;
import com.yuyuframe.launcheragent.celeritas.util.ModelQuadUtil;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore.QuadStore;

/**
 * Un quad de modèle de bloc, décodé une fois pour toutes dans la forme que
 * lit le moteur — notre code. Aucun type du jeu.
 *
 * <p>Vanilla range un quad dans 28 entiers (7 par sommet) : position, couleur,
 * coordonnées de texture. L'adaptateur d'amont greffe une interface sur la
 * classe du jeu pour les relire à chaque usage ; ici on les décode à la
 * création, avec ce que le moteur en déduit (face de la normale, drapeaux de
 * forme pour l'éclairage).
 *
 * <p>Deuxième format d'entrée du futur noyau natif : la géométrie d'un quad
 * ne dépend que du modèle, elle pourra être rangée dans une table commune.
 *
 * <p>Immuable après construction : partagé entre les fils de construction.
 */
public final class CachedQuad implements BakedQuadView {

    private static final int INTS_PER_VERTEX = 7;

    private final float[] x = new float[4];
    private final float[] y = new float[4];
    private final float[] z = new float[4];
    private final float[] u = new float[4];
    private final float[] v = new float[4];
    /** Couleur de chaque sommet. Dans la 1.8.9, l'ombrage de la face y est déjà : c'est toujours un gris. */
    private final int[] color = new int[4];

    private final int colorIndex;
    private final ModelQuadFacing lightFace;
    private final ModelQuadFacing normalFace;
    private final int computedNormal;
    private int flags;

    /**
     * @param vertexData les 28 entiers de vanilla
     * @param colorIndex la couche dont le quad prend la teinte, ou −1
     * @param lightFace  la face vers laquelle le modèle déclare le quad tourné
     */
    public CachedQuad(int[] vertexData, int colorIndex, ModelQuadFacing lightFace) {
        for (int i = 0; i < 4; i++) {
            int at = i * INTS_PER_VERTEX;
            this.x[i] = Float.intBitsToFloat(vertexData[at]);
            this.y[i] = Float.intBitsToFloat(vertexData[at + 1]);
            this.z[i] = Float.intBitsToFloat(vertexData[at + 2]);
            this.color[i] = vertexData[at + 3];
            this.u[i] = Float.intBitsToFloat(vertexData[at + 4]);
            this.v[i] = Float.intBitsToFloat(vertexData[at + 5]);
        }
        this.colorIndex = colorIndex;
        this.lightFace = lightFace;
        this.computedNormal = ModelQuadUtil.calculateNormal(this);
        this.normalFace = ModelQuadUtil.findNormalFace(this.computedNormal);
        this.flags = ModelQuadFlags.getQuadFlags(this, lightFace);
        this.nativeAddress = QuadStore.store(this.nativeRecord());
    }

    /** L'adresse de la copie native du quad, lue par le noyau de maillage ({@link QuadStore}). */
    public final long nativeAddress;

    private int[] nativeRecord() {
        int[] words = new int[QuadStore.RECORD_WORDS];
        for (int i = 0; i < 4; i++) {
            words[i] = Float.floatToRawIntBits(this.x[i]);
            words[4 + i] = Float.floatToRawIntBits(this.y[i]);
            words[8 + i] = Float.floatToRawIntBits(this.z[i]);
            words[12 + i] = Float.floatToRawIntBits(this.u[i]);
            words[16 + i] = Float.floatToRawIntBits(this.v[i]);
            words[20 + i] = this.color[i];
        }
        words[QuadStore.WORD_FLAGS] = this.flags;
        words[QuadStore.WORD_FACES] = this.lightFace.ordinal() | this.normalFace.ordinal() << 8;
        words[QuadStore.WORD_NORMAL] = this.computedNormal;
        return words;
    }

    @Override
    public float getX(int idx) {
        return this.x[idx];
    }

    @Override
    public float getY(int idx) {
        return this.y[idx];
    }

    @Override
    public float getZ(int idx) {
        return this.z[idx];
    }

    @Override
    public int getColor(int idx) {
        return this.color[idx];
    }

    @Override
    public float getTexU(int idx) {
        return this.u[idx];
    }

    @Override
    public float getTexV(int idx) {
        return this.v[idx];
    }

    /** Pas de lumière propre au quad dans la 1.8.9. */
    @Override
    public int getLight(int idx) {
        return 0;
    }

    @Override
    public int getFlags() {
        return this.flags;
    }

    @Override
    public int getColorIndex() {
        return this.colorIndex;
    }

    /** Les quads de la 1.8.9 ne connaissent pas leur texture ; le moteur ne s'en sert que pour suivre les textures animées. */
    @Override
    public Object celeritas$getSprite() {
        return null;
    }

    @Override
    public ModelQuadFacing getLightFace() {
        return this.lightFace;
    }

    @Override
    public ModelQuadFacing getNormalFace() {
        return this.normalFace;
    }

    @Override
    public int getForgeNormal(int idx) {
        return 0;
    }

    @Override
    public int getComputedFaceNormal() {
        return this.computedNormal;
    }

    /** L'ombrage de face est déjà dans la couleur des sommets : rien à ajouter. */
    @Override
    public boolean hasShade() {
        return false;
    }

    @Override
    public void addFlags(int extra) {
        this.flags |= extra;
        QuadStore.setWord(this.nativeAddress, QuadStore.WORD_FLAGS, this.flags);
    }

    @Override
    public int getVerticesCount() {
        return 4;
    }

    @Override
    public SpriteTransparencyLevel getTransparencyLevel() {
        return null;
    }
}
