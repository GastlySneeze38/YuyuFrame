package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Un modèle 3D : des parties emboîtées, chacune avec ses faces texturées.
 *
 * <p>Même structure que les modèles du jeu ({@code ModelPart} : un pivot, trois
 * angles, des cubes, des enfants), pour qu'un modèle vanilla s'y recopie tel
 * quel — c'est ce que font les points d'accès {@code VANILLA_MODEL} de chaque
 * version. Un modèle fabriqué par nous (les tuiles de rang) prend la même
 * forme.
 *
 * <h2>Repère</h2>
 *
 * En pixels du jeu (16 par bloc), <b>Y vers le haut</b>, Z vers celui qui
 * regarde : vue de face, X va à droite. C'est le repère des modèles de bloc
 * des versions récentes ; la 1.8.9, qui dessine ses modèles à l'envers, est
 * retournée à la lecture.
 *
 * <p>Une face a quatre sommets dans l'ordre du tour (un triangle répète son
 * dernier sommet), leurs coordonnées de texture de 0 à 1, et sa normale vers
 * l'extérieur — c'est elle qui décide si la face est vue et comment elle est
 * éclairée, l'ordre des sommets n'a pas besoin d'être fiable.
 *
 * <p>Immuable une fois construit : le même modèle sert à toutes les images,
 * c'est la {@link UiModelPose} qui l'anime.
 */
public final class UiModel {

    /** Une face : quatre sommets, leurs coordonnées de texture, sa normale. */
    public static final class Face {
        private final float[] positions;
        private final float[] uvs;
        private final float[] normal;

        /**
         * @param positions 12 valeurs, x y z de chaque sommet, dans le repère de la partie
         * @param uvs       8 valeurs, u v de chaque sommet, de 0 à 1
         * @param normal    3 valeurs, vers l'extérieur (normalisée ici)
         */
        public Face(float[] positions, float[] uvs, float[] normal) {
            if (positions.length != 12 || uvs.length != 8 || normal.length != 3) {
                throw new IllegalArgumentException("une face = 4 sommets (12 positions, 8 uv) et une normale");
            }
            this.positions = positions.clone();
            this.uvs = uvs.clone();
            float length = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            this.normal = length < 1e-6f ? new float[]{ 0f, 0f, 1f }
                : new float[]{ normal[0] / length, normal[1] / length, normal[2] / length };
        }

        public float[] positions() { return positions; }
        public float[] uvs() { return uvs; }
        public float[] normal() { return normal; }
    }

    /** Une partie : son pivot et ses angles au repos, ses faces, ses enfants. */
    public static final class Part {
        private final String name;
        private final float pivotX, pivotY, pivotZ;
        private final float rotX, rotY, rotZ;
        private final List<Face> faces;
        private final List<Part> children;

        public Part(String name, float pivotX, float pivotY, float pivotZ,
                    float rotX, float rotY, float rotZ, List<Face> faces, List<Part> children) {
            this.name = name;
            this.pivotX = pivotX; this.pivotY = pivotY; this.pivotZ = pivotZ;
            this.rotX = rotX; this.rotY = rotY; this.rotZ = rotZ;
            this.faces = Collections.unmodifiableList(new ArrayList<>(faces));
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }

        public String name() { return name; }
        public float pivotX() { return pivotX; }
        public float pivotY() { return pivotY; }
        public float pivotZ() { return pivotZ; }
        /** Angles au repos, en radians, appliqués dans l'ordre Z puis Y puis X comme dans le jeu. */
        public float rotX() { return rotX; }
        public float rotY() { return rotY; }
        public float rotZ() { return rotZ; }
        public List<Face> faces() { return faces; }
        public List<Part> children() { return children; }
    }

    private final List<Part> parts;
    private final float[] bounds;

    public UiModel(List<Part> parts) {
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        this.bounds = computeBounds();
    }

    public List<Part> parts() { return parts; }

    /**
     * La boîte englobante au repos, {@code {minX, minY, minZ, maxX, maxY, maxZ}} —
     * pour centrer le modèle ou caler sa taille à l'écran sans connaître sa forme.
     */
    public float[] bounds() { return bounds.clone(); }

    /** Le centre de la boîte englobante, {@code {x, y, z}}. */
    public float[] center() {
        return new float[]{ (bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f, (bounds[2] + bounds[5]) / 2f };
    }

    /** Nombre de faces, toutes parties confondues. */
    public int faceCount() {
        int[] count = { 0 };
        visit(parts, part -> count[0] += part.faces().size());
        return count[0];
    }

    private float[] computeBounds() {
        float[] b = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        UiModelPose rest = new UiModelPose();
        UiMeshProjector.forEachRestVertex(this, rest, (x, y, z) -> {
            b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z);
            b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z);
        });
        if (b[0] > b[3]) return new float[6];
        return b;
    }

    private static void visit(List<Part> parts, java.util.function.Consumer<Part> action) {
        for (Part part : parts) {
            action.accept(part);
            visit(part.children(), action);
        }
    }

    // ── Construction ──────────────────────────────────────────────────────

    /** Construit une partie pas à pas — utilisé par les lecteurs de modèles vanilla et par nos formes. */
    public static final class PartBuilder {
        private final String name;
        private float pivotX, pivotY, pivotZ, rotX, rotY, rotZ;
        private final List<Face> faces = new ArrayList<>();
        private final List<Part> children = new ArrayList<>();

        public PartBuilder(String name) {
            this.name = name;
        }

        public PartBuilder pivot(float x, float y, float z) {
            pivotX = x; pivotY = y; pivotZ = z;
            return this;
        }

        public PartBuilder rotation(float x, float y, float z) {
            rotX = x; rotY = y; rotZ = z;
            return this;
        }

        public PartBuilder face(Face face) {
            faces.add(face);
            return this;
        }

        /**
         * Ajoute une face dont la normale est déduite : produit vectoriel de
         * ses arêtes, retourné pour s'éloigner de {@code insideX/Y/Z} (le
         * centre du volume qui la porte). C'est ce qui rend l'ordre des
         * sommets indifférent : les modèles du jeu ne le garantissent pas
         * d'une version à l'autre.
         */
        public PartBuilder face(float[] positions, float[] uvs, float insideX, float insideY, float insideZ) {
            float[] n = UiMeshProjector.faceNormal(positions);
            float cx = 0, cy = 0, cz = 0;
            for (int i = 0; i < 4; i++) {
                cx += positions[i * 3] / 4f; cy += positions[i * 3 + 1] / 4f; cz += positions[i * 3 + 2] / 4f;
            }
            float outward = (cx - insideX) * n[0] + (cy - insideY) * n[1] + (cz - insideZ) * n[2];
            if (outward < 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
            faces.add(new Face(positions, uvs, n));
            return this;
        }

        public PartBuilder child(Part part) {
            children.add(part);
            return this;
        }

        public Part build() {
            return new Part(name, pivotX, pivotY, pivotZ, rotX, rotY, rotZ, faces, children);
        }
    }
}
