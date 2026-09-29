package com.yuyuframe.launcheragent.apigraphic.era.blaze3d.v26_3.gpu;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Met nos sources GLSL au format qu'exige le compilateur de la 26.3.
 *
 * <p>En 26.3, TOUS les shaders passent par le compilateur GLSL → SPIR-V de
 * renderpearl ({@code frontend.shaders.PipelineBuilder}), quel que soit le
 * backend. Les shaders vanilla ont changé en conséquence (diff des jars
 * 26.2/26.3), et les nôtres — écrits une fois pour toutes les versions dans
 * le moteur partagé ({@code Blaze3DCore}, {@code Blaze3DRect}…) — doivent
 * suivre sur cette version seulement :
 * <ul>
 *   <li>{@code #extension GL_ARB_separate_shader_objects : require} après
 *       {@code #version} ;</li>
 *   <li>un {@code layout(location = N)} sur chaque {@code in}/{@code out} de
 *       portée globale. Les attributs de sommet sont reliés au format PAR NOM
 *       ({@code VertexFormatElement.name()}), leur numéro importe peu ; les
 *       variables entre étages, elles, sont reliées PAR EMPLACEMENT
 *       ({@code "Vertex shader missing output at location %d consumed by
 *       Fragment shader"}) — un {@code in} du fragment reçoit donc le numéro
 *       du {@code out} de même nom du vertex ;</li>
 *   <li>le bloc {@code DynamicTransforms} dans l'ordre que
 *       {@code DynamicGpuData$Transform.write} écrit désormais :
 *       ModelViewMat, TextureMat, ColorModulator, ModelOffset. Un ordre faux
 *       ne lève rien : les couleurs et les matrices sont lues au mauvais
 *       endroit.</li>
 * </ul>
 * Le texte d'entrée reste celui des autres versions : rien n'est modifié
 * dans le moteur partagé.
 */
final class Glsl263 {

    private Glsl263() {
    }

    private static final String EXTENSION = "#extension GL_ARB_separate_shader_objects : require\n";

    /** Déclaration {@code in}/{@code out} de portée globale, une par ligne (seule forme employée par nos shaders). */
    private static final Pattern VARYING = Pattern.compile(
        "(?m)^([ \\t]*)((?:flat|smooth|noperspective)[ \\t]+)?(in|out)[ \\t]+(\\w+)[ \\t]+(\\w+)[ \\t]*;");

    private static final Pattern DYNAMIC_TRANSFORMS = Pattern.compile(
        "(uniform[ \\t]+DynamicTransforms[ \\t]*\\{)([^}]*)(\\})");

    /** Ordre des membres de {@code DynamicTransforms} en 26.3 — celui de {@code dynamictransforms.glsl}. */
    private static final String[] TRANSFORM_ORDER = { "ModelViewMat", "TextureMat", "ColorModulator", "ModelOffset" };

    /** Sources adaptées d'un couple vertex/fragment : {@code [vertex, fragment]}. */
    static String[] adapt(String vertex, String fragment) {
        Map<String, Integer> vertexOutputs = new HashMap<>();
        String v = common(vertex);
        v = locate(v, vertexOutputs, null);
        String f = common(fragment);
        f = locate(f, null, vertexOutputs);
        return new String[]{ v, f };
    }

    private static String common(String src) {
        if (src == null) return null;
        String out = src;
        if (!out.contains("GL_ARB_separate_shader_objects")) {
            int versionEnd = out.startsWith("#version") ? out.indexOf('\n') + 1 : 0;
            out = out.substring(0, versionEnd) + EXTENSION + out.substring(versionEnd);
        }
        return reorderTransforms(out);
    }

    /**
     * Numérote les {@code in}/{@code out}. {@code outputs} non nul : on
     * enregistre le numéro de chaque {@code out} (étage vertex).
     * {@code upstream} non nul : chaque {@code in} reprend le numéro du
     * {@code out} de même nom (étage fragment) ; un nom inconnu en amont
     * prend le premier numéro libre — le compilateur du jeu le signalera
     * lui-même, avec le nom du shader.
     */
    private static String locate(String src, Map<String, Integer> outputs, Map<String, Integer> upstream) {
        if (src == null) return null;
        Matcher m = VARYING.matcher(src);
        StringBuilder sb = new StringBuilder();
        int nextIn = 0, nextOut = 0;
        int nextFreeIn = upstream == null ? 0 : upstream.values().stream().mapToInt(i -> i + 1).max().orElse(0);
        while (m.find()) {
            String indent = m.group(1);
            String interpolation = m.group(2) == null ? "" : m.group(2);
            String dir = m.group(3), type = m.group(4), name = m.group(5);
            int location;
            if ("out".equals(dir)) {
                location = nextOut++;
                if (outputs != null) outputs.put(name, location);
            } else if (upstream != null) {
                Integer up = upstream.get(name);
                location = up != null ? up : nextFreeIn++;
            } else {
                location = nextIn++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                indent + "layout(location = " + location + ") " + interpolation + dir + " " + type + " " + name + ";"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String reorderTransforms(String src) {
        Matcher m = DYNAMIC_TRANSFORMS.matcher(src);
        if (!m.find()) return src;
        String body = m.group(2);
        StringBuilder members = new StringBuilder("\n");
        String rest = body;
        for (String member : TRANSFORM_ORDER) {
            Matcher line = Pattern.compile("[ \\t]*\\w+[ \\t]+" + member + "[ \\t]*;[ \\t]*\\n?").matcher(rest);
            if (line.find()) {
                String decl = line.group().trim();
                members.append("    ").append(decl).append('\n');
                rest = rest.substring(0, line.start()) + rest.substring(line.end());
            }
        }
        // Tout membre inattendu reste, après les quatre connus.
        String leftover = rest.trim();
        if (!leftover.isEmpty()) members.append("    ").append(leftover).append('\n');
        return src.substring(0, m.start(2)) + members + src.substring(m.end(2));
    }
}
