package net.minecraft.client.option;

/**
 * Stub compile-only — obfusqué en "avb" dans mappings-1.8.9.tiny.
 *
 * Contrairement aux stubs de {@code net/minecraft/client/gui/screens/}
 * (patchés au chargement par ScreenStubPatcher pour DEVENIR la vraie
 * superclasse d'un écran custom), celui-ci sert UNIQUEMENT à donner un type
 * Java réel et compilable au paramètre d'un handler {@code @Redirect}/
 * {@code @Inject} de Mixin (voir MixinToggleSprint189/MixinToggleSneak189) :
 * Sponge Mixin exige que le DESCRIPTEUR du paramètre corresponde EXACTEMENT
 * au type réel de la cible interceptée (validation stricte, {@code Object}
 * rejeté avec "InvalidInjectionException ... expected avb") — le remapper
 * enregistré (MappingsRegistry) traduit ensuite ce nom "named" vers "avb" au
 * moment du tissage, sans jamais charger CETTE classe stub au runtime.
 *
 * Corps vide volontairement : jamais instancié, jamais réellement chargé —
 * seul son NOM (package + classe, identique à la colonne "named" du fichier
 * de mappings) compte pour la résolution Mixin.
 */
public final class KeyBinding {
    private KeyBinding() {}
}
