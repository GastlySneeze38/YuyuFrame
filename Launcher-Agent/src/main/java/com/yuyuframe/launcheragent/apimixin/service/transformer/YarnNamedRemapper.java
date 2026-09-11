package com.yuyuframe.launcheragent.apimixin.service.transformer;

import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashSet;
import java.util.Set;

/**
 * Traduit AU CHARGEMENT le code typé d'une version obfusquée, compilé contre
 * des stubs aux noms Yarn « named », vers les noms du loader actif
 * (intermédiaire sous Fabric/Quilt, officiel en vanilla).
 *
 * <h2>Pourquoi</h2>
 *
 * Le moteur ne doit plus faire de réflexion. Mais sur une version obfusquée, un
 * type comme {@code GpuSampler} s'appelle {@code class_12137} sous Fabric et
 * {@code fzf} en vanilla : aucun stub unique ne peut le nommer. On compile donc
 * contre son nom Yarn ({@code net/minecraft/client/gl/GpuSampler}), et cette
 * classe réécrit le bytecode à la volée — même principe que
 * {@link ScreenStubPatcher}, en générique, via {@link MappingsRegistry}.
 *
 * <h2>Périmètre</h2>
 *
 * Uniquement les classes des paquets listés dans {@link #PACKAGES} (code typé
 * par version, voir {@code src/main_1_21_11}). Rien d'autre n'est touché.
 *
 * <h2>Ce qui est traduit</h2>
 *
 * Supertypes, types de champs et de méthodes, appels (propriétaire, NOM,
 * descripteur), accès aux champs, {@code CHECKCAST}/{@code NEW}/{@code
 * INSTANCEOF}, constantes {@code Type}/{@code Handle}, {@code invokedynamic}
 * (lambdas), frames, blocs {@code catch}, variables locales, classes internes.
 * Les signatures génériques ne sont PAS traduites : elles ne servent qu'à la
 * réflexion, que ce code n'utilise pas.
 *
 * <h2>Limites connues, assumées</h2>
 * <ul>
 *   <li>Une LAMBDA qui implémente une interface Yarn verrait sa méthode
 *       fonctionnelle non traduite : utiliser une classe (anonyme ou nommée)
 *       pour implémenter une interface du jeu.</li>
 *   <li>ASM de base uniquement ({@code asm-commons}/{@code ClassRemapper}
 *       n'est garanti ni sur le classpath isolé ni à la compilation).</li>
 * </ul>
 */
public final class YarnNamedRemapper {

    private YarnNamedRemapper() {}

    /** Paquets (noms internes, avec « / » final) dont les classes sont traduites. */
    private static final String[] PACKAGES = {
        "com/yuyuframe/launcheragent/apigraphic/era/blaze3d/v1_21_11/",
    };

    /** Membres net.minecraft introuvables dans Yarn — journalisés une fois chacun. */
    private static final Set<String> REPORTED = new HashSet<>();

    public static boolean applies(String internalName) {
        if (internalName == null) return false;
        for (String p : PACKAGES) {
            if (internalName.startsWith(p)) return true;
        }
        return false;
    }

    /** @return les octets traduits, ou {@code null} si rien à faire (Yarn non chargé). */
    public static byte[] remap(byte[] classBytes) {
        if (!MappingsRegistry.isLoaded()) return null;
        try {
            ClassReader cr = new ClassReader(classBytes);
            // Pas de recalcul (flags 0) : les frames existantes sont conservées et
            // traduites une à une dans visitFrame — recalculer demanderait de
            // charger les classes du jeu (getCommonSuperClass), ce qu'on évite.
            ClassWriter cw = new ClassWriter(0);
            Visitor v = new Visitor(cw);
            cr.accept(v, 0);
            LauncherLog.asm(3, "[YarnNamedRemapper] " + cr.getClassName() + " traduite ("
                + MappingsRegistry.getScheme() + ")");
            return cw.toByteArray();
        } catch (Throwable t) {
            // Relancé par l'appelant comme toute exception de transformer — mais
            // journalisé ici avec le contexte, jamais en silence.
            LauncherLog.err("[YarnNamedRemapper] échec de traduction : " + t);
            throw t;
        }
    }

    // ── Traductions élémentaires ───────────────────────────────────────────

    private static String cls(String internalName) {
        if (internalName == null) return null;
        if (internalName.startsWith("[")) return desc(internalName);
        return MappingsRegistry.INSTANCE.map(internalName);
    }

    private static String desc(String descriptor) {
        return descriptor == null ? null : MappingsRegistry.INSTANCE.mapDesc(descriptor);
    }

    /**
     * {@code true} si le NOM DE CLASSE lui-même est traduit (classe obfusquée).
     *
     * <p>Règle vérifiée hors jeu (banc {@code RemapCheck}, jar
     * {@code client-intermediary.jar} réel) : les membres d'une classe dont le
     * nom n'est pas traduit — les classes {@code com.mojang.blaze3d.*} de premier
     * niveau — gardent leur vrai nom à l'exécution. Yarn contient pourtant des
     * entrées intermédiaires pour certains d'entre eux ({@code RenderPass.close}
     * → {@code method_44380}, {@code RenderPipeline.getVertexFormat} →
     * {@code method_23031}) que le jar exécuté n'applique pas : les suivre
     * produisait des {@code NoSuchMethodError}. Seuls les membres d'une classe
     * RENOMMÉE ({@code net.minecraft.*}, imbriquées obfusquées comme
     * {@code RenderSystem$ShapeIndexBuffer}) sont traduits.
     */
    private static boolean ownerRenamed(String namedOwner) {
        return !namedOwner.startsWith("[") && !MappingsRegistry.INSTANCE.map(namedOwner).equals(namedOwner);
    }

    private static String method(String namedOwner, String name, String descriptor) {
        if (name.startsWith("<") || !ownerRenamed(namedOwner)) return name;
        String runtime = MappingsRegistry.namedToRuntimeMethod(namedOwner, name, descriptor, true);
        if (runtime.equals(name)) report(namedOwner, name, descriptor);
        return runtime;
    }

    private static String field(String namedOwner, String name, String descriptor) {
        if (!ownerRenamed(namedOwner)) return name;
        String runtime = MappingsRegistry.namedToRuntimeField(namedOwner, name);
        if (runtime.equals(name)) report(namedOwner, name, descriptor);
        return runtime;
    }

    /**
     * Un nom resté inchangé est normal pour {@code com.mojang.blaze3d.*} (non
     * obfusqué) et pour tout ce qui n'est pas du jeu. Sur une classe
     * {@code net.minecraft} connue de Yarn, c'est presque toujours un stub mal
     * écrit : on le signale une fois, pour que le {@code NoSuchMethodError} qui
     * suivra ait une explication dans le log.
     */
    private static void report(String owner, String name, String descriptor) {
        if (name.startsWith("<")) return; // constructeurs : jamais renommés, pas une anomalie
        if (!owner.startsWith("net/minecraft/") || !MappingsRegistry.isNamedClass(owner)) return;
        String key = owner + "." + name + descriptor;
        synchronized (REPORTED) {
            if (!REPORTED.add(key)) return;
        }
        LauncherLog.warn("[YarnNamedRemapper] membre inconnu de Yarn, laissé tel quel : " + key);
    }

    private static Object value(Object v) {
        if (v instanceof Type) {
            Type t = (Type) v;
            switch (t.getSort()) {
                case Type.OBJECT: return Type.getObjectType(cls(t.getInternalName()));
                case Type.ARRAY:
                case Type.METHOD: return Type.getType(desc(t.getDescriptor()));
                default: return t;
            }
        }
        if (v instanceof Handle) {
            Handle h = (Handle) v;
            boolean isField = h.getTag() <= Opcodes.H_PUTSTATIC;
            String name = isField ? field(h.getOwner(), h.getName(), h.getDesc())
                                  : method(h.getOwner(), h.getName(), h.getDesc());
            return new Handle(h.getTag(), cls(h.getOwner()), name, desc(h.getDesc()), h.isInterface());
        }
        return v;
    }

    private static Object[] frame(Object[] entries, int count) {
        if (entries == null) return null;
        Object[] out = entries.clone();
        for (int i = 0; i < count && i < out.length; i++) {
            if (out[i] instanceof String) out[i] = cls((String) out[i]);
        }
        return out;
    }

    // ── Visiteurs ──────────────────────────────────────────────────────────

    private static final class Visitor extends ClassVisitor {
        private String superName;
        private String[] interfaces = new String[0];

        Visitor(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.superName = superName;
            this.interfaces = interfaces != null ? interfaces : new String[0];
            String[] mapped = new String[this.interfaces.length];
            for (int i = 0; i < mapped.length; i++) mapped[i] = cls(this.interfaces[i]);
            super.visit(version, access, name, signature, cls(superName), mapped);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            String mapped = cls(name);
            String mappedInner = innerName;
            if (innerName != null && mapped != null && !mapped.equals(name) && mapped.indexOf('$') >= 0) {
                mappedInner = mapped.substring(mapped.lastIndexOf('$') + 1);
            }
            super.visitInnerClass(mapped, cls(outerName), mappedInner, access);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            return super.visitField(access, name, desc(descriptor), signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            // Une DÉCLARATION qui redéfinit une méthode d'un supertype du jeu doit
            // porter son nom runtime, sinon ce n'est pas un override pour la JVM
            // (déjà vécu avec mouseClicked/keyPressed, voir ScreenStubPatcher).
            String declared = name;
            if (!name.startsWith("<")) {
                String[] supers = new String[interfaces.length + 1];
                supers[0] = superName;
                System.arraycopy(interfaces, 0, supers, 1, interfaces.length);
                for (String s : supers) {
                    // Même règle que method() : un supertype non renommé garde
                    // les noms de ses méthodes, donc nos redéfinitions aussi.
                    if (s == null || !MappingsRegistry.isNamedClass(s) || !ownerRenamed(s)) continue;
                    String r = MappingsRegistry.namedToRuntimeMethod(s, name, descriptor, false);
                    if (!r.equals(name)) { declared = r; break; }
                }
            }
            String[] mappedEx = null;
            if (exceptions != null) {
                mappedEx = new String[exceptions.length];
                for (int i = 0; i < exceptions.length; i++) mappedEx[i] = cls(exceptions[i]);
            }
            MethodVisitor mv = super.visitMethod(access, declared, desc(descriptor), signature, mappedEx);
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                    super.visitMethodInsn(opcode, cls(owner), method(owner, mName, mDesc), desc(mDesc), itf);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                    super.visitFieldInsn(opcode, cls(owner), field(owner, fName, fDesc), desc(fDesc));
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    super.visitTypeInsn(opcode, cls(type));
                }

                @Override
                public void visitLdcInsn(Object v) {
                    super.visitLdcInsn(value(v));
                }

                @Override
                public void visitInvokeDynamicInsn(String iName, String iDesc, Handle bsm, Object... args) {
                    Object[] mapped = new Object[args.length];
                    for (int i = 0; i < args.length; i++) mapped[i] = value(args[i]);
                    super.visitInvokeDynamicInsn(iName, desc(iDesc), (Handle) value(bsm), mapped);
                }

                @Override
                public void visitMultiANewArrayInsn(String aDesc, int dims) {
                    super.visitMultiANewArrayInsn(desc(aDesc), dims);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    super.visitTryCatchBlock(start, end, handler, cls(type));
                }

                @Override
                public void visitLocalVariable(String lName, String lDesc, String lSig, Label start, Label end, int index) {
                    super.visitLocalVariable(lName, desc(lDesc), lSig, start, end, index);
                }

                @Override
                public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                    super.visitFrame(type, numLocal, frame(local, numLocal), numStack, frame(stack, numStack));
                }
            };
        }
    }
}
