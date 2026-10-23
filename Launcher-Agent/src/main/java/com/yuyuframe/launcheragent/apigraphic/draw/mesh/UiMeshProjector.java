package com.yuyuframe.launcheragent.apigraphic.draw.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Toute la 3D, en Java, commune à toutes les versions : pose des parties,
 * rotation de la vue, perspective, faces cachées, éclairage, ordre de dessin.
 * Le résultat est un {@link UiMeshBatch} de quads plats que chaque moteur de
 * rendu dessine comme des icônes.
 *
 * <h2>Pourquoi sur le processeur</h2>
 *
 * Un modèle d'interface compte quelques dizaines de faces : le calcul tient
 * en quelques microsecondes, et il évite un pipeline 3D par moteur (Blaze3D,
 * état de GUI de chaque version, GL3 de la 1.8.9). Un seul code fait la
 * géométrie, les moteurs ne font que recopier des sommets.
 *
 * <h2>Ordre de dessin sans tampon de profondeur</h2>
 *
 * L'état de GUI du jeu dessine dans l'ordre de soumission, sans profondeur.
 * Les faces tournées vers l'arrière sont donc retirées, puis les autres sont
 * dessinées de la plus lointaine à la plus proche (algorithme du peintre),
 * triées par le centre de la FACE entière : ses morceaux suivent, sinon un
 * morceau du fond d'un coffre pourrait passer devant le bas du couvercle qui
 * le touche. Exact pour des volumes convexes comme ceux des modèles du jeu.
 *
 * <h2>Perspective juste</h2>
 *
 * Une face est découpée en une grille de petits quads, projetés un à un : la
 * texture d'un quad 2D s'étire en ligne droite, ce qui tord une grande face
 * vue en biais ; en petits morceaux ça ne se voit plus. La même grille porte
 * l'éclairage, calculé à chacun de ses sommets.
 */
public final class UiMeshProjector {

    private UiMeshProjector() {
    }

    /** Taille visée d'un morceau de face à l'écran, en pixels. */
    private static final float CELL_PIXELS = 18f;
    private static final int MAX_CELLS = 10;

    /** Une face retenue pour l'image : ses sommets dans le repère du modèle et de la vue. */
    private static final class Visible {
        final float[] model = new float[12];
        final float[] view = new float[12];
        float[] uvs;
        final float[] modelNormal = new float[3];
        final float[] viewNormal = new float[3];
        float depth;
    }

    /**
     * Projette {@code model} et remplit {@code out}.
     *
     * @param uScale   facteur appliqué aux {@code u} du modèle (la texture
     *                 préparée a une bande blanche à droite, voir {@link UiMeshTexture})
     * @param whiteU   {@code u} d'un point de la bande blanche, pour les
     *                 quads d'éclaircissement ; {@code < 0} = pas d'éclaircissement
     */
    public static void project(UiModel model, UiModelPose pose, UiView3D view, UiLighting lighting,
                               float alpha, float uScale, float whiteU, UiMeshBatch out) {
        out.reset();
        if (alpha <= 0.002f) return;
        List<Visible> faces = new ArrayList<>();
        float[] viewMatrix = viewRotation(view);
        float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 };
        for (UiModel.Part part : model.parts()) {
            collect(part, identity, pose, view, viewMatrix, faces);
        }
        faces.sort((a, b) -> Float.compare(a.depth, b.depth));
        for (Visible face : faces) {
            emit(face, view, viewMatrix, lighting, alpha, uScale, whiteU, out);
        }
    }

    /**
     * Où tombe à l'écran un point du modèle (repère de la racine, au repos) :
     * {@code {x, y, échelle}} — l'échelle est celle de la perspective à cette
     * profondeur, pour dimensionner un effet 2D posé dessus.
     */
    public static float[] projectPoint(UiView3D view, float x, float y, float z) {
        float[] m = viewRotation(view);
        float[] p = toView(m, view, x, y, z);
        float k = perspective(view, p[2]);
        return new float[]{ view.centerX() + p[0] * view.scale() * k, view.centerY() + p[1] * view.scale() * k, k };
    }

    /**
     * Un point exprimé dans le repère d'une partie, ramené dans celui du
     * modèle selon la pose — le bord d'un couvercle qui s'ouvre, par exemple.
     * {@code null} si la partie n'existe pas.
     */
    public static float[] partPoint(UiModel model, UiModelPose pose, String partName, float x, float y, float z) {
        float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 };
        for (UiModel.Part part : model.parts()) {
            float[] found = findPart(part, identity, pose, partName);
            if (found != null) return apply(found, x, y, z);
        }
        return null;
    }

    // ── Pose des parties ──────────────────────────────────────────────────

    private static float[] findPart(UiModel.Part part, float[] parent, UiModelPose pose, String name) {
        float[] m = partMatrix(part, parent, pose);
        if (part.name().equals(name)) return m;
        for (UiModel.Part child : part.children()) {
            float[] found = findPart(child, m, pose, name);
            if (found != null) return found;
        }
        return null;
    }

    /** Matrice 3×4 de la partie : parent × translation(pivot + décalage) × Rz × Ry × Rx (ordre du jeu). */
    private static float[] partMatrix(UiModel.Part part, float[] parent, UiModelPose pose) {
        float[] rot = pose == null ? null : pose.rotationOf(part.name());
        float rx = rot != null ? rot[0] : part.rotX();
        float ry = rot != null ? rot[1] : part.rotY();
        float rz = rot != null ? rot[2] : part.rotZ();
        float[] off = pose == null ? null : pose.offsetOf(part.name());
        float tx = part.pivotX() + (off != null ? off[0] : 0f);
        float ty = part.pivotY() + (off != null ? off[1] : 0f);
        float tz = part.pivotZ() + (off != null ? off[2] : 0f);
        float[] local = multiply(rotationZ(rz), multiply(rotationY(ry), rotationX(rx)));
        local[3] = tx; local[7] = ty; local[11] = tz;
        return multiply(parent, local);
    }

    private static void collect(UiModel.Part part, float[] parent, UiModelPose pose, UiView3D view,
                                float[] viewMatrix, List<Visible> out) {
        float[] m = partMatrix(part, parent, pose);
        for (UiModel.Face face : part.faces()) {
            Visible v = new Visible();
            float[] pos = face.positions();
            float cx = 0, cy = 0, cz = 0;
            for (int i = 0; i < 4; i++) {
                float[] p = apply(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
                v.model[i * 3] = p[0]; v.model[i * 3 + 1] = p[1]; v.model[i * 3 + 2] = p[2];
                float[] q = toView(viewMatrix, view, p[0], p[1], p[2]);
                v.view[i * 3] = q[0]; v.view[i * 3 + 1] = q[1]; v.view[i * 3 + 2] = q[2];
                cx += q[0] / 4f; cy += q[1] / 4f; cz += q[2] / 4f;
            }
            float[] n = face.normal();
            float[] nm = rotate(m, n[0], n[1], n[2]);
            float[] nv = rotate(viewMatrix, nm[0], nm[1], nm[2]);
            // Vue de l'œil : en perspective, depuis le point (0, 0, distance) ;
            // sinon, tout droit le long de Z.
            float facing = view.eyeDistance() > 0f
                ? nv[0] * -cx + nv[1] * -cy + nv[2] * (view.eyeDistance() - cz)
                : nv[2];
            if (facing <= 1e-4f) continue;
            System.arraycopy(nm, 0, v.modelNormal, 0, 3);
            System.arraycopy(nv, 0, v.viewNormal, 0, 3);
            v.uvs = face.uvs();
            v.depth = cz;
            out.add(v);
        }
        for (UiModel.Part child : part.children()) {
            collect(child, m, pose, view, viewMatrix, out);
        }
    }

    /** Parcourt les sommets du modèle au repos (ou dans {@code pose}), repère du modèle. */
    interface VertexVisitor {
        void vertex(float x, float y, float z);
    }

    static void forEachRestVertex(UiModel model, UiModelPose pose, VertexVisitor visitor) {
        float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 };
        for (UiModel.Part part : model.parts()) visitVertices(part, identity, pose, visitor);
    }

    private static void visitVertices(UiModel.Part part, float[] parent, UiModelPose pose, VertexVisitor visitor) {
        float[] m = partMatrix(part, parent, pose);
        for (UiModel.Face face : part.faces()) {
            float[] pos = face.positions();
            for (int i = 0; i < 4; i++) {
                float[] p = apply(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
                visitor.vertex(p[0], p[1], p[2]);
            }
        }
        for (UiModel.Part child : part.children()) visitVertices(child, m, pose, visitor);
    }

    // ── Découpage, éclairage, émission ────────────────────────────────────

    private static void emit(Visible face, UiView3D view, float[] viewMatrix, UiLighting lighting,
                             float alpha, float uScale, float whiteU, UiMeshBatch out) {
        // Taille de la face à l'écran : la plus longue arête projetée.
        float[] screen = new float[8];
        for (int i = 0; i < 4; i++) {
            float k = perspective(view, face.view[i * 3 + 2]);
            screen[i * 2] = view.centerX() + face.view[i * 3] * view.scale() * k;
            screen[i * 2 + 1] = view.centerY() + face.view[i * 3 + 1] * view.scale() * k;
        }
        float longest = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            longest = Math.max(longest, (float) Math.hypot(screen[j * 2] - screen[i * 2], screen[j * 2 + 1] - screen[i * 2 + 1]));
        }
        int cells = Math.max(1, Math.min(MAX_CELLS, (int) Math.ceil(longest / CELL_PIXELS)));
        int side = cells + 1;
        float[] sx = new float[side * side], sy = new float[side * side];
        float[] su = new float[side * side], sv = new float[side * side];
        int[] base = new int[side * side], glow = new int[side * side];
        boolean anyGlow = false;
        float[] lit = new float[3];
        for (int j = 0; j <= cells; j++) {
            float t = j / (float) cells;
            for (int i = 0; i <= cells; i++) {
                float s = i / (float) cells;
                int k = j * side + i;
                // Bilinéaire sur les quatre coins, dans le repère du modèle.
                float mx = bilinear(face.model, 0, s, t), my = bilinear(face.model, 1, s, t), mz = bilinear(face.model, 2, s, t);
                float[] q = toView(viewMatrix, view, mx, my, mz);
                float f = perspective(view, q[2]);
                sx[k] = view.centerX() + q[0] * view.scale() * f;
                sy[k] = view.centerY() + q[1] * view.scale() * f;
                su[k] = bilinearUv(face.uvs, 0, s, t) * uScale;
                sv[k] = bilinearUv(face.uvs, 1, s, t);
                light(lighting, face, mx, my, mz, lit);
                base[k] = color(Math.min(1f, lit[0]), Math.min(1f, lit[1]), Math.min(1f, lit[2]), alpha);
                float er = Math.max(0f, lit[0] - 1f), eg = Math.max(0f, lit[1] - 1f), eb = Math.max(0f, lit[2] - 1f);
                float excess = Math.max(er, Math.max(eg, eb));
                if (excess > 0.004f && whiteU >= 0f) {
                    anyGlow = true;
                    float a = Math.min(0.9f, excess * 0.6f) * alpha;
                    glow[k] = color(er / excess, eg / excess, eb / excess, a);
                } else {
                    glow[k] = 0;
                }
            }
        }
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int a = j * side + i, b = a + 1, c = a + side + 1, d = a + side;
                float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a])
                           + (sx[c] - sx[a]) * (sy[d] - sy[a]) - (sx[d] - sx[a]) * (sy[c] - sy[a]);
                if (Math.abs(area) < 1e-4f) continue;
                if (area < 0) { int swap = b; b = d; d = swap; }
                out.add(sx[a], sy[a], sx[b], sy[b], sx[c], sy[c], sx[d], sy[d],
                    su[a], sv[a], su[b], sv[b], su[c], sv[c], su[d], sv[d],
                    base[a], base[b], base[c], base[d]);
                if (anyGlow && (glow[a] | glow[b] | glow[c] | glow[d]) != 0) {
                    out.add(sx[a], sy[a], sx[b], sy[b], sx[c], sy[c], sx[d], sy[d],
                        whiteU, 0.5f, whiteU, 0.5f, whiteU, 0.5f, whiteU, 0.5f,
                        glow[a], glow[b], glow[c], glow[d]);
                }
            }
        }
    }

    private static void light(UiLighting lighting, Visible face, float x, float y, float z, float[] out) {
        if (lighting == null) {
            out[0] = out[1] = out[2] = 1f;
            return;
        }
        float r = lighting.ambientR(), g = lighting.ambientG(), b = lighting.ambientB();
        for (UiLighting.Light l : lighting.lights()) {
            float amount;
            if (l.isPoint()) {
                float dx = l.x() - x, dy = l.y() - y, dz = l.z() - z;
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist >= l.range()) continue;
                float ndotl = dist < 1e-4f ? 1f
                    : (face.modelNormal[0] * dx + face.modelNormal[1] * dy + face.modelNormal[2] * dz) / dist;
                if (ndotl <= 0f) continue;
                float falloff = 1f - dist / l.range();
                amount = ndotl * falloff * falloff * l.intensity();
            } else {
                float ndotl = face.viewNormal[0] * l.x() + face.viewNormal[1] * l.y() + face.viewNormal[2] * l.z();
                if (ndotl <= 0f) continue;
                amount = ndotl * l.intensity();
            }
            r += l.r() * amount;
            g += l.g() * amount;
            b += l.b() * amount;
        }
        out[0] = r; out[1] = g; out[2] = b;
    }

    private static int color(float r, float g, float b, float a) {
        int ai = Math.max(0, Math.min(255, Math.round(a * 255f)));
        int ri = Math.max(0, Math.min(255, Math.round(r * 255f)));
        int gi = Math.max(0, Math.min(255, Math.round(g * 255f)));
        int bi = Math.max(0, Math.min(255, Math.round(b * 255f)));
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static float bilinear(float[] p, int axis, float s, float t) {
        float top = p[axis] + (p[3 + axis] - p[axis]) * s;
        float bottom = p[9 + axis] + (p[6 + axis] - p[9 + axis]) * s;
        return top + (bottom - top) * t;
    }

    private static float bilinearUv(float[] uv, int axis, float s, float t) {
        float top = uv[axis] + (uv[2 + axis] - uv[axis]) * s;
        float bottom = uv[6 + axis] + (uv[4 + axis] - uv[6 + axis]) * s;
        return top + (bottom - top) * t;
    }

    // ── Vue ───────────────────────────────────────────────────────────────

    /** Roulis, puis lacet, puis tangage : {@code Rx(tangage) · Ry(lacet) · Rz(roulis)}. */
    private static float[] viewRotation(UiView3D view) {
        return multiply(rotationX(view.pitch()), multiply(rotationY(view.yaw()), rotationZ(view.roll())));
    }

    private static float[] toView(float[] m, UiView3D view, float x, float y, float z) {
        float[] p = rotate(m, x - view.originX(), y - view.originY(), z - view.originZ());
        p[0] += view.translateX();
        p[1] += view.translateY();
        p[2] += view.translateZ();
        return p;
    }

    /** Grossissement de la perspective à la profondeur {@code z} (Z vers l'œil). */
    private static float perspective(UiView3D view, float z) {
        float eye = view.eyeDistance();
        if (eye <= 0f) return 1f;
        return eye / Math.max(eye * 0.05f, eye - z);
    }

    // ── Petites matrices 3×4, rangées par lignes ──────────────────────────

    private static float[] rotationX(float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{ 1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0 };
    }

    private static float[] rotationY(float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{ c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0 };
    }

    private static float[] rotationZ(float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[]{ c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0 };
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                r[row * 4 + col] = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col];
            }
            r[row * 4 + 3] = a[row * 4] * b[3] + a[row * 4 + 1] * b[7] + a[row * 4 + 2] * b[11] + a[row * 4 + 3];
        }
        return r;
    }

    private static float[] apply(float[] m, float x, float y, float z) {
        return new float[]{
            m[0] * x + m[1] * y + m[2] * z + m[3],
            m[4] * x + m[5] * y + m[6] * z + m[7],
            m[8] * x + m[9] * y + m[10] * z + m[11],
        };
    }

    private static float[] rotate(float[] m, float x, float y, float z) {
        return new float[]{
            m[0] * x + m[1] * y + m[2] * z,
            m[4] * x + m[5] * y + m[6] * z,
            m[8] * x + m[9] * y + m[10] * z,
        };
    }

    /** Normale d'une face par le produit vectoriel de ses diagonales (sens arbitraire). */
    static float[] faceNormal(float[] p) {
        float ax = p[6] - p[0], ay = p[7] - p[1], az = p[8] - p[2];
        float bx = p[9] - p[3], by = p[10] - p[4], bz = p[11] - p[5];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6f) {
            // Triangle (dernier sommet répété) : diagonale nulle, on prend deux arêtes.
            ax = p[3] - p[0]; ay = p[4] - p[1]; az = p[5] - p[2];
            bx = p[6] - p[0]; by = p[7] - p[1]; bz = p[8] - p[2];
            nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx;
            length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (length < 1e-6f) return new float[]{ 0f, 0f, 1f };
        return new float[]{ nx / length, ny / length, nz / length };
    }
}
