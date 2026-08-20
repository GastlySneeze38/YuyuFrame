package com.yuyuframe.launcheragent.mixin.service.transformer;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.mapping.YarnMappings;
import org.objectweb.asm.*;

import java.util.Map;

/**
 * Transforme nos écrans custom (voir STUB_PATCHED_SCREENS dans
 * LauncherMixinTransformerWrapper) pour remplacer les stubs de compilation
 * par les classes réelles obfusquées résolues via Yarn.
 *
 * Copie indépendante de com.p2pminecraft.mixin.service.transformer.ScreenStubPatcher
 * (p2p-agent) — même bug, même fix : nos écrans custom compilent contre des
 * stubs (net.minecraft.client.gui.screens.Screen / net.minecraft.network.chat.Component)
 * qui n'ont AUCUNE relation de type avec les vraies classes obfusquées du jeu
 * (gsb, yh...). Sans ce patch, l'écran ne serait jamais assignable au
 * paramètre de MinecraftClient.setScreen(Screen) → "setScreen introuvable" en
 * silence (ScreenHelper.navigate() ne trouve aucune méthode compatible).
 *
 * Stubs visés (noms Yarn, namespace "named") :
 *   net/minecraft/client/gui/screen/Screen  → obfusqué (ex: gsb)
 *   net/minecraft/text/Text                 → obfusqué (ex: yh)
 */
public final class ScreenStubPatcher {

    private ScreenStubPatcher() {}

    private static String orElse(String v, String fallback) { return v != null ? v : fallback; }

    /**
     * {@code Screen()} (no-arg) N'EXISTE PAS PARTOUT — trouvé en test réel
     * (voir historique du projet) : 1.8.9 (official {@code axu}) n'a QUE le
     * no-arg (vérifié par désassemblage : {@code public axu();}, aucune autre
     * surcharge) ; 1.21.11 (official {@code gsb}) n'a QUE
     * {@code protected gsb(yh)} (Text) — confirmé par
     * {@code NoSuchMethodError: gsb: method 'void <init>()' not found} en
     * jeu quand {@code UiScreenBase} appelait {@code super()} sans argument.
     * Comme le choix du BON super-constructeur ne peut pas se décider à la
     * compilation (une seule version tourne à la fois, mais le même .class
     * source doit fonctionner sur les deux), on le résout ICI par réflexion
     * sur la VRAIE classe Screen résolue, et on réécrit l'appel
     * {@code INVOKESPECIAL <stub>.<init>()V} en conséquence : appel no-arg
     * inchangé si dispo, sinon injection d'un {@code Text.literal("")} juste
     * avant l'appel au constructeur (Lyh;)V réel.
     */
    private static boolean classLoadable(String slashName, ClassLoader loader) {
        try {
            Class.forName(slashName.replace('/', '.'), false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Résout le vrai nom d'une classe stubbée — via Yarn si chargé
     * (obfuscation classique : map() traduit le nom Yarn "named" vers le
     * nom "official"/runtime réel), SINON (bracket 26.1+, jeu non obfusqué,
     * YarnMappings JAMAIS chargé — voir VersionBracketRegistry) directement
     * par réflexion sur les deux conventions de nommage possibles (ancienne
     * "yarnStub" singulier, ex: "net/minecraft/client/gui/screen/Screen",
     * vs nouvelle "altStub" pluriel, ex: ".../screens/Screen").
     *
     * BUG CORRIGÉ (utilisateur, 26.1.2 : "les Mixins s'appliquent mais
     * l'interface n'apparaît pas" — {@code NoSuchMethodError:
     * net.minecraft.client.gui.screens.Screen: method 'void <init>()' not
     * found}) : l'ancien code traitait "map() n'a rien changé" comme "cette
     * classe est déjà réelle, aucun patch nécessaire" (voir l'ancien
     * commentaire "Screen non mappé (mode non-obfusqué), skip") — FAUX pour
     * 26.1+ : le stub compile-only et la vraie classe {@code Screen}
     * PORTENT LE MÊME NOM COMPLET (coïncidence de package) mais ont des
     * constructeurs DIFFÉRENTS (le stub a un no-arg, le vrai Screen 26.1.2
     * n'en a aucun) — le patch (injection du titre manquant avant l'appel
     * réel, voir plus bas) reste indispensable même quand les noms
     * coïncident.
     */
    private static String resolveRealClass(String yarnStub, String altStub, ClassLoader loader) {
        String mapped = MappingsRegistry.INSTANCE.map(yarnStub);
        if (!yarnStub.equals(mapped)) return mapped; // Yarn chargé, traduction réelle obtenue
        if (classLoadable(altStub, loader)) return altStub;
        if (classLoadable(yarnStub, loader)) return yarnStub;
        return yarnStub; // ni Yarn ni réflexion (classe pas encore chargeable) — repli historique
    }

    private static final Map<String, Boolean> NO_ARG_CTOR_CACHE = new java.util.HashMap<>();

    private static boolean screenHasNoArgConstructor(String realScreenSlash, ClassLoader loader) {
        Boolean cached = NO_ARG_CTOR_CACHE.get(realScreenSlash);
        if (cached != null) return cached;
        boolean result;
        try {
            Class<?> screenClass = Class.forName(realScreenSlash.replace('/', '.'), false, loader);
            result = false;
            for (java.lang.reflect.Constructor<?> c : screenClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 0) { result = true; break; }
            }
        } catch (Throwable t) {
            // Introuvable à ce stade (classe pas encore chargée) : par défaut,
            // supposer no-arg (comportement historique, sûr pour 1.8.9) —
            // le vrai résultat sera mis en cache dès qu'un chargement
            // ultérieur de cette même classe stub réussira à résoudre la classe.
            result = true;
        }
        NO_ARG_CTOR_CACHE.put(realScreenSlash, result);
        return result;
    }

    /** Nom + descripteur RÉELS (pas supposés) de la méthode "String -> Text" trouvée — voir {@link #findLiteralTextMethod}. */
    private static final class LiteralMethod {
        final String name, desc;
        LiteralMethod(String name, String desc) { this.name = name; this.desc = desc; }
    }

    private static final Map<String, LiteralMethod> LITERAL_METHOD_CACHE = new java.util.HashMap<>();

    /**
     * Trouve par RÉFLEXION (pas par nom Yarn) la méthode statique "String ->
     * Text" (ex: {@code Text.of(String)}) sur la vraie classe Text résolue —
     * même motif que {@link #screenHasNoArgConstructor} : cherche une FORME
     * (statique, 1 paramètre String, retour assignable à la classe Text
     * elle-même), pas un nom précis.
     *
     * CORRIGÉ (3e essai, bug trouvé en testant le bracket 1.13-1.16.x) :
     * 1er essai — résolution par nom Yarn "of" SEUL (sans descripteur) —
     * cassé : {@code Text} a plusieurs surcharges de "of" sur 1.16.5, retombe
     * sur la mauvaise (nom obfusqué "b" puis "a" selon la tentative, jamais la
     * bonne forme). 2e essai — réflexion pour trouver le bon NOM, mais le
     * DESCRIPTEUR de l'appel bytecode restait construit en supposant un
     * retour {@code MutableText} (hardcodé) — si la vraie méthode trouvée
     * retourne autre chose (ex: {@code Text} lui-même, pas obligatoirement
     * {@code MutableText}), le descripteur généré ne correspondait PLUS à la
     * signature réelle malgré un nom correct → NoSuchMethodError persistant
     * avec le MÊME nom qu'avant ("a"), symptôme qui a fait croire à tort que
     * la réflexion n'avait rien changé. **Fix définitif** : renvoyer aussi le
     * VRAI type de retour observé par réflexion (jamais supposé), utilisé tel
     * quel pour construire le descripteur du call-site bytecode.
     */
    private static LiteralMethod findLiteralTextMethod(String realCompSlash, ClassLoader loader) {
        LiteralMethod cached = LITERAL_METHOD_CACHE.get(realCompSlash);
        if (cached != null) return cached;
        LiteralMethod result = null;
        try {
            Class<?> compClass = Class.forName(realCompSlash.replace('/', '.'), false, loader);
            for (java.lang.reflect.Method m : compClass.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                if (!compClass.isAssignableFrom(m.getReturnType())) continue;
                String returnSlash = m.getReturnType().getName().replace('.', '/');
                result = new LiteralMethod(m.getName(), "(Ljava/lang/String;)L" + returnSlash + ";");
                break;
            }
        } catch (Throwable ignored) {
            // Classe pas encore chargeable à ce stade — pas de cache, on
            // retentera au prochain écran custom patché.
        }
        if (result != null) LITERAL_METHOD_CACHE.put(realCompSlash, result);
        return result;
    }

    public static byte[] patch(byte[] classBytes, ClassLoader loader) {
        try {
            final String STUB_SCREEN     = "net/minecraft/client/gui/screen/Screen";
            final String STUB_SCREEN_ALT = "net/minecraft/client/gui/screens/Screen";
            final String STUB_COMP       = "net/minecraft/text/Text";
            final String STUB_COMP_ALT   = "net/minecraft/network/chat/Component";
            final String STUB_MUTABLE_TEXT = "net/minecraft/text/MutableText";
            // Types record introduits avec Blaze3D (1.21.6+) remplaçant les
            // paramètres primitifs historiques de mouseClicked/keyPressed —
            // voir stubs Click.java/KeyInput.java et UiScreenBase pour le
            // pourquoi. N'existent pas comme classes réelles sur les brackets
            // antérieurs (map() renvoie alors le nom stub INCHANGÉ) — sans
            // conséquence : les surcharges qui les utilisent restent alors de
            // simples méthodes inertes sur ces versions-là (voir javadoc de
            // UiScreenBase.mouseClicked(Click,boolean)).
            final String STUB_CLICK      = "net/minecraft/client/gui/Click";
            final String STUB_KEY_INPUT  = "net/minecraft/client/input/KeyInput";
            // Types record 26.1+ remplaçant Click/KeyInput — voir stubs
            // MouseButtonEvent.java/KeyEvent.java et UiScreenBase pour le
            // pourquoi (Element renommé GuiEventListener + déplacé de
            // package sur 26.1+, mouseClicked/keyPressed prennent ces
            // nouveaux types). Même motif exact que STUB_CLICK/STUB_KEY_INPUT :
            // n'existent pas comme classes réelles sur les brackets <26.1
            // (map() renvoie alors le nom stub INCHANGÉ), sans conséquence.
            final String STUB_MOUSE_BUTTON_EVENT = "net/minecraft/client/input/MouseButtonEvent";
            final String STUB_KEY_EVENT          = "net/minecraft/client/input/KeyEvent";

            ClassReader cr = new ClassReader(classBytes);
            // resolveRealClass (pas MappingsRegistry.map() brut) pour Screen/Comp —
            // voir sa javadoc : sans ça, le bracket 26.1+ (Yarn jamais chargé)
            // sautait le patch en croyant la classe "déjà réelle".
            String realScreen = resolveRealClass(STUB_SCREEN, STUB_SCREEN_ALT, loader);
            String realComp   = resolveRealClass(STUB_COMP, STUB_COMP_ALT, loader);
            String realMutableText = MappingsRegistry.INSTANCE.map(STUB_MUTABLE_TEXT);
            String realClick     = MappingsRegistry.INSTANCE.map(STUB_CLICK);
            String realKeyInput  = MappingsRegistry.INSTANCE.map(STUB_KEY_INPUT);
            String realMouseButtonEvent = MappingsRegistry.INSTANCE.map(STUB_MOUSE_BUTTON_EVENT);
            String realKeyEvent         = MappingsRegistry.INSTANCE.map(STUB_KEY_EVENT);

            if (STUB_SCREEN.equals(realScreen) && !classLoadable(STUB_SCREEN_ALT, loader) && !classLoadable(STUB_SCREEN, loader)) {
                LauncherLog.asm(1, "[LauncherAgent ASM] " + cr.getClassName() + ": Screen introuvable (ni Yarn ni réflexion), skip");
                return null;
            }
            // Voir screenHasNoArgConstructor : 1.8.9 (axu) n'a QUE le no-arg,
            // 1.21.11 (gsb) n'a QUE (Text) — jamais les deux, résolu par
            // réflexion sur la vraie classe une fois qu'elle est chargeable.
            boolean screenNoArgOk = screenHasNoArgConstructor(realScreen, loader);
            // CORRIGÉ (bug trouvé en testant le bracket 1.13-1.16.x, voir
            // historique de session, 3 essais) : "yh"/"b"/"(Ljava/lang/String;)Lyw;"
            // étaient des noms OFFICIELS codés en dur, valables UNIQUEMENT pour
            // le build 1.21.11 précis. Fixé par RÉFLEXION sur la vraie forme
            // (statique, (String), retour assignable à Text) — NOM ET
            // DESCRIPTEUR tous deux lus depuis la vraie méthode trouvée,
            // jamais supposés — voir findLiteralTextMethod, même motif que
            // screenHasNoArgConstructor juste au-dessus.
            LiteralMethod foundLiteral = findLiteralTextMethod(realComp, loader);
            if (foundLiteral == null) {
                LauncherLog.warn("[LauncherAgent ASM] " + cr.getClassName()
                    + ": aucune méthode statique (String)->Text trouvée sur " + realComp
                    + " — le patch pour écran sans constructeur no-arg échouera si nécessaire");
            }
            // Repli "of"/MutableText — au pire NoSuchMethodError explicite, pas
            // un throw ici. Effectively final (référencées depuis la classe
            // anonyme MethodVisitor plus bas).
            final String literalMethodName = foundLiteral != null ? foundLiteral.name : "of";
            final String literalDesc = foundLiteral != null ? foundLiteral.desc : "(Ljava/lang/String;)L" + realMutableText + ";";

            /*
             * BUG TROUVÉ (voir historique de session — mouseClicked()/keyPressed()
             * de UiScreenBase ne se déclenchaient JAMAIS, sur aucune version, malgré
             * une résolution de setScreen() désormais correcte) : ces deux méthodes
             * sont déclarées sur l'interface Element du VRAI jeu, avec un nom
             * OBFUSQUÉ (ex: "a" pour les deux, en scheme officiel 1.16.5 — descripteurs
             * différents qui permettent la surcharge). Notre code source les déclare
             * sous leur nom NAMED Yarn ("mouseClicked"/"keyPressed") — sans
             * renommage, ce ne sont PAS des overrides du tout pour la JVM (qui lie
             * les méthodes par nom EXACT + descripteur, jamais par "ressemblance" de
             * forme) : elles restent des méthodes inertes, jamais appelées par le
             * jeu, exactement comme Screen.init()/mouseScrolled()/tick() nécessitaient
             * déjà ce même renommage ci-dessus (OVERRIDE_METHODS) — sauf que cette
             * table est figée sur des noms officiels 1.21.11 codés en dur, jamais
             * étendue pour ces deux méthodes ni pour aucune autre version.
             *
             * Fix : résolution DYNAMIQUE (comme tout le reste du pipeline 1.13-1.16.x,
             * voir GlobalUiRenderMixin116) via MappingsRegistry.getObfMethodName avec
             * DESCRIPTEUR (pas juste le nom — Element a plusieurs méthodes candidates
             * par ailleurs) — descripteurs 100% primitifs ici (DDI/III), aucun type de
             * classe à remapper contrairement à setScreen(Screen), donc pas besoin de
             * résoudre quoi que ce soit d'autre au préalable.
             */
            final String realMouseClickedName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "mouseClicked", "(DDI)Z");
            final String realKeyPressedName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "keyPressed", "(III)Z");
            // BUG TROUVÉ (era E, 1.21.11 — "boutons pas cliquables" persistant
            // même après un premier correctif ciblant Click/KeyInput) :
            // getObfMethodName(yarnClass, yarnMethod, officialDesc) — voir sa
            // javadoc — indexe/recherche par descripteur OFFICIAL (celui
            // écrit tel quel dans mappings.tiny, colonne 0 = "official", donc
            // avec les noms de classes OBFUSQUÉS BRUTS, ex: "gzc" pour Click).
            // Le premier essai construisait ce descripteur avec
            // realClick/realKeyInput = MappingsRegistry.map(STUB) — qui
            // renvoie le nom RUNTIME (intermediary sous Fabric en jeu réel,
            // voir Scheme.INTERMEDIARY), PAS le nom official. En scheme
            // INTERMEDIARY (le cas réel en jeu), le descripteur ainsi construit
            // ("(Lclass_XXXX;Z)Z") ne correspond à AUCUNE clé de la table
            // (indexée sur le descripteur official "(Lgzc;Z)Z") → recherche
            // toujours en échec → getObfMethodName retombe sur le nom Yarn
            // INCHANGÉ ("mouseClicked") → jamais un override réel, jamais
            // appelé par le jeu (confirmé : aucune trace de "DIAG-E11" dans
            // les logs, même en cliquant). Fix : résoudre le nom OFFICIAL brut
            // de Click/KeyInput via YarnMappings.getOfficialClass() (jamais
            // via map(), réservé aux références RUNTIME dans le bytecode
            // lui-même — CHECKCAST, remapAll()... — un usage totalement
            // différent, voir plus bas où realClick/realKeyInput restent
            // utilisés tels quels pour ça).
            final String officialClick = orElse(YarnMappings.getOfficialClass(STUB_CLICK), STUB_CLICK);
            final String officialKeyInput = orElse(YarnMappings.getOfficialClass(STUB_KEY_INPUT), STUB_KEY_INPUT);
            final String realMouseClickedClickName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "mouseClicked", "(L" + officialClick + ";Z)Z");
            final String realKeyPressedKeyInputName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "keyPressed", "(L" + officialKeyInput + ";)Z");
            // 26.1+ : MÊME mécanisme que Click/KeyInput ci-dessus, pour
            // MouseButtonEvent/KeyEvent — voir stubs et UiScreenBase. Sur les
            // brackets <26.1, officialClass reste le nom stub inchangé (Yarn
            // ne connaît pas ces classes) → getObfMethodName retombe sur le
            // nom Yarn inchangé ("mouseClicked"/"keyPressed") → la surcharge
            // correspondante reste une méthode inerte sur ces versions-là,
            // sans conséquence (même garantie que pour Click/KeyInput).
            final String officialMouseButtonEvent = orElse(YarnMappings.getOfficialClass(STUB_MOUSE_BUTTON_EVENT), STUB_MOUSE_BUTTON_EVENT);
            final String officialKeyEvent = orElse(YarnMappings.getOfficialClass(STUB_KEY_EVENT), STUB_KEY_EVENT);
            final String realMouseClickedEventName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "mouseClicked", "(L" + officialMouseButtonEvent + ";Z)Z");
            final String realKeyPressedEventName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/Element", "keyPressed", "(L" + officialKeyEvent + ";)Z");

            /*
             * BUG TROUVÉ (retour utilisateur : "en 1.8.9 on ne peut plus
             * cliquer sur les boutons — même pas le bouton fermer", AUCUNE
             * exception nulle part dans les logs) — le renommage ci-dessus ne
             * couvre QUE les surcharges mouseClicked/keyPressed qui RENVOIENT
             * un booléen (déclarées sur l'interface Element, brackets 1.13+).
             * UiScreenBase.mouseClicked(int,int,int)/keyTyped(char,int) —
             * signature RÉELLE historique de GuiScreen 1.8.9 (void, pas
             * d'interface Element séparée à cette époque) — n'était JAMAIS
             * renommée : le commentaire d'origine de ces deux méthodes
             * affirmait à tort que leur nom "n'a jamais été renommé depuis"
             * (vrai pour le nom MCP/Yarn NAMED, "mouseClicked"/lisible par un
             * humain) — mais le nom RÉEL en bytecode obfusqué (celui que la
             * JVM utilise pour lier un override) est complètement différent :
             * confirmé dans mappings/mappings-1.8.9.tiny (classe axu =
             * Screen/class_388) : {@code mouseClicked(III)V} → officiel "a"
             * (method_1026), {@code keyPressed(CI)V} → officiel "a" aussi
             * (method_1024, nommé "keyPressed" côté Yarn même si sémantiquement
             * c'est notre keyTyped). Nos deux méthodes, toujours nommées
             * littéralement "mouseClicked"/"keyTyped" en bytecode, ne
             * correspondaient donc à AUCUN override réel — jamais appelées
             * par le jeu, sans la moindre exception (une méthode qui n'est
             * juste jamais invoquée n'en lève aucune). Résolues ici via
             * Screen (PAS Element, qui n'existe pas en 1.8.9 — la requête y
             * échouerait silencieusement et retomberait sur le nom Yarn
             * inchangé) ; sur les brackets modernes (aucune méthode
             * mouseClicked/keyPressed de CETTE forme précise sur Screen),
             * getObfMethodName ne trouve rien et renvoie le nom Yarn tel
             * quel — même garde-fou déjà établi pour Click/KeyInput/
             * MouseButtonEvent/KeyEvent ci-dessus : la surcharge reste juste
             * inerte, sans risque, sur les versions où elle ne s'applique pas.
             */
            final String realMouseClickedVoidName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/screen/Screen", "mouseClicked", "(III)V");
            final String realKeyTypedVoidName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/screen/Screen", "keyPressed", "(CI)V");

            LauncherLog.asm(1, "[LauncherAgent ASM] " + cr.getClassName() + ": Screen=" + realScreen
                    + "  Text=" + realComp + "  noArgCtor=" + screenNoArgOk
                    + "  mouseClicked(DDI)->" + realMouseClickedName + "  keyPressed(III)->" + realKeyPressedName
                    + "  mouseClicked(Click)->" + realMouseClickedClickName + "  keyPressed(KeyInput)->" + realKeyPressedKeyInputName
                    + "  mouseClicked(MouseButtonEvent)->" + realMouseClickedEventName + "  keyPressed(KeyEvent)->" + realKeyPressedEventName
                    + "  mouseClicked(III)V->" + realMouseClickedVoidName + "  keyTyped(CI)V->" + realKeyTypedVoidName);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String t1, String t2) {
                    return "java/lang/Object";
                }
            };

            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                private boolean isStubScreen(String s) {
                    return STUB_SCREEN.equals(s) || STUB_SCREEN_ALT.equals(s);
                }
                private boolean isStubComp(String s) {
                    return STUB_COMP.equals(s) || STUB_COMP_ALT.equals(s);
                }
                private boolean isStubClick(String s) { return STUB_CLICK.equals(s); }
                private boolean isStubKeyInput(String s) { return STUB_KEY_INPUT.equals(s); }
                private boolean isStubMouseButtonEvent(String s) { return STUB_MOUSE_BUTTON_EVENT.equals(s); }
                private boolean isStubKeyEvent(String s) { return STUB_KEY_EVENT.equals(s); }
                private String remapAll(String desc) {
                    return desc.replace("L" + STUB_SCREEN + ";",     "L" + realScreen + ";")
                               .replace("L" + STUB_SCREEN_ALT + ";", "L" + realScreen + ";")
                               .replace("L" + STUB_COMP + ";",       "L" + realComp + ";")
                               .replace("L" + STUB_COMP_ALT + ";",   "L" + realComp + ";")
                               .replace("L" + STUB_CLICK + ";",      "L" + realClick + ";")
                               .replace("L" + STUB_KEY_INPUT + ";",  "L" + realKeyInput + ";")
                               .replace("L" + STUB_MOUSE_BUTTON_EVENT + ";", "L" + realMouseButtonEvent + ";")
                               .replace("L" + STUB_KEY_EVENT + ";",  "L" + realKeyEvent + ";");
                }

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    String newSuper = isStubScreen(superName) ? realScreen : superName;
                    super.visit(version, access, name, signature, newSuper, interfaces);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    return super.visitField(access, name, remapAll(descriptor), signature, value);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    // Renomme la méthode déclarée ELLE-MÊME quand son nom+descripteur
                    // "official" (ex: "bg_()V" pour Screen.init(), "a(DDDD)Z" pour
                    // Element.mouseScrolled) correspond à un override polymorphique
                    // réel attendu par le jeu — sous Fabric, le vrai nom à l'exécution
                    // est "intermediary" (ex: method_25426), donc "bg_()V" tel
                    // qu'écrit dans le code source n'est PAS un override du tout :
                    // le JVM ne le considère lié à aucune méthode de la vraie
                    // superclasse, donc Screen.init() ne fait jamais rien et l'écran
                    // reste vide. remapAll() (descripteurs des appels/champs internes)
                    // ne couvre pas ce cas car ici c'est la DÉCLARATION elle-même qui
                    // doit changer de nom, pas une référence.
                    String runtimeName;
                    if ("mouseClicked".equals(name) && "(DDI)Z".equals(descriptor)) {
                        runtimeName = realMouseClickedName;
                    } else if ("keyPressed".equals(name) && "(III)Z".equals(descriptor)) {
                        runtimeName = realKeyPressedName;
                    } else if ("mouseClicked".equals(name) && ("(L" + STUB_CLICK + ";Z)Z").equals(descriptor)) {
                        runtimeName = realMouseClickedClickName;
                    } else if ("keyPressed".equals(name) && ("(L" + STUB_KEY_INPUT + ";)Z").equals(descriptor)) {
                        runtimeName = realKeyPressedKeyInputName;
                    } else if ("mouseClicked".equals(name) && ("(L" + STUB_MOUSE_BUTTON_EVENT + ";Z)Z").equals(descriptor)) {
                        runtimeName = realMouseClickedEventName;
                    } else if ("keyPressed".equals(name) && ("(L" + STUB_KEY_EVENT + ";)Z").equals(descriptor)) {
                        runtimeName = realKeyPressedEventName;
                    } else if ("mouseClicked".equals(name) && "(III)V".equals(descriptor)) {
                        runtimeName = realMouseClickedVoidName;
                    } else if ("keyTyped".equals(name) && "(CI)V".equals(descriptor)) {
                        runtimeName = realKeyTypedVoidName;
                    } else {
                        // Plus aucune méthode déclarée par nos écrans custom restants
                        // (UiScreenBase) n'a besoin de renommage hors des cas explicites
                        // ci-dessus — inchangé (ex-table OVERRIDE_METHODS, propre à
                        // CustomKeybindsScreen désormais supprimé, voir ROADMAP-agent
                        // Phase 1).
                        runtimeName = name;
                    }
                    MethodVisitor mv = super.visitMethod(access, runtimeName, remapAll(descriptor), signature, exceptions);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName,
                                                    String mDesc, boolean isInterface) {
                            // CORRECTIF (NoSuchMethodError confirmé en jeu sur
                            // 1.21.11 — voir screenHasNoArgConstructor) : le
                            // stub déclare un constructeur no-arg ET un
                            // (Component) — notre code source appelle TOUJOURS
                            // le no-arg (super()), qui ne correspond au VRAI
                            // constructeur Screen que sur certaines versions
                            // (1.8.9). Sur les autres (1.21.11), on injecte un
                            // Text.literal("") juste avant l'appel réel.
                            if (isStubScreen(owner) && "<init>".equals(mName) && "()V".equals(mDesc) && !screenNoArgOk) {
                                super.visitLdcInsn("");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, realComp, literalMethodName, literalDesc, true);
                                super.visitMethodInsn(opcode, realScreen, "<init>", "(L" + realComp + ";)V", false);
                                return;
                            }
                            // CAS SYMÉTRIQUE (NoSuchMethodError confirmé en jeu sur
                            // 1.8.9 : "axu.<init>(Leu;)V" — découvert via
                            // ResourcePackSearchScreen, écrit pour 1.21.11 et appelant
                            // TOUJOURS super(Component title)). Sur 1.8.9, axu n'a QUE
                            // le no-arg — le titre déjà poussé sur la pile (par l'appel
                            // précédent, ex: ScreenHelper.literal(...)) est jeté (POP,
                            // 1 slot : un type référence) et on appelle le vrai
                            // constructeur no-arg à la place.
                            if (isStubScreen(owner) && "<init>".equals(mName) && !"()V".equals(mDesc) && screenNoArgOk) {
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(opcode, realScreen, "<init>", "()V", false);
                                return;
                            }
                            if (isStubScreen(owner)) owner = realScreen;
                            else if (isStubComp(owner)) {
                                owner = realComp;
                                mName = MappingsRegistry.getObfMethodName(STUB_COMP, mName);
                            } else if (isStubClick(owner)) {
                                owner = realClick;
                                mName = MappingsRegistry.getObfMethodName(STUB_CLICK, mName);
                            } else if (isStubKeyInput(owner)) {
                                owner = realKeyInput;
                                mName = MappingsRegistry.getObfMethodName(STUB_KEY_INPUT, mName);
                            } else if (isStubMouseButtonEvent(owner)) {
                                owner = realMouseButtonEvent;
                                mName = MappingsRegistry.getObfMethodName(STUB_MOUSE_BUTTON_EVENT, mName);
                            } else if (isStubKeyEvent(owner)) {
                                owner = realKeyEvent;
                                mName = MappingsRegistry.getObfMethodName(STUB_KEY_EVENT, mName);
                            }
                            super.visitMethodInsn(opcode, owner, mName, remapAll(mDesc), isInterface);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (isStubScreen(type)) type = realScreen;
                            else if (isStubComp(type)) type = realComp;
                            else if (isStubClick(type)) type = realClick;
                            else if (isStubKeyInput(type)) type = realKeyInput;
                            else if (isStubMouseButtonEvent(type)) type = realMouseButtonEvent;
                            else if (isStubKeyEvent(type)) type = realKeyEvent;
                            super.visitTypeInsn(opcode, type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner,
                                                   String fName, String fDesc) {
                            if (isStubScreen(owner)) owner = realScreen;
                            else if (isStubComp(owner)) owner = realComp;
                            super.visitFieldInsn(opcode, owner, fName, remapAll(fDesc));
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);

            LauncherLog.asm(3, "[LauncherAgent ASM] " + cr.getClassName() + " patché : superclasse → " + realScreen);
            return cw.toByteArray();
        } catch (Exception e) {
            LauncherLog.err("[LauncherAgent ASM] Erreur patch écran custom: " + e);
            e.printStackTrace(System.err);
            return null;
        }
    }
}
