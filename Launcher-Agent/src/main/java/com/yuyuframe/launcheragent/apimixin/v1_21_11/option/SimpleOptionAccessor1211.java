package com.yuyuframe.launcheragent.apimixin.v1_21_11.option;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Champ {@code value} de {@code SimpleOption} sur 1.21.11 — pendant exact
 * d'{@code OptionInstanceAccessor261}.
 *
 * <h2>Pourquoi un accessor alors que {@code setValue()} est publique</h2>
 *
 * Parce que {@code setValue()} déclenche la VALIDATION vanilla, qui CLAMPE.
 * Le champ d'à-côté, lui, ne clampe pas — et c'est tout l'intérêt du point
 * d'accès {@code OPTION_VALUE_SET}, dont la javadoc dit explicitement
 * « contourne la validation vanilla ».
 *
 * <p>Bug vécu (v1095) : la liaison 1.21.11 passait par le setter public. La
 * luminosité de {@code FullbrightModule} était donc ramenée dans [0,1] à
 * chaque écriture (le module la pousse bien au-delà), et le champ de vision de
 * {@code ZoomModule} rabattu dans les bornes du menu — d'où un zoom « par
 * à-coups » au lieu d'un fondu. Symptômes différents, cause unique.
 *
 * <p>{@code Object} et non {@code T} : évite une erreur d'effacement de
 * générique côté Mixin, le transtypage reste chez l'appelant.
 */
@Mixin(targets = "net.minecraft.client.option.SimpleOption")
public interface SimpleOptionAccessor1211 {

    @Accessor("value")
    Object la$value();

    @Accessor("value")
    void la$setValue(Object value);
}
