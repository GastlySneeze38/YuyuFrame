package net.minecraft.client.render.entity.state;

import net.minecraft.text.Text;

/** Stub compile-only 1.21.11, nom Yarn ({@code ick}) — champs déclarés sur CETTE classe. */
public class PlayerEntityRenderState extends EntityRenderState {

    /** Malgré son nom Yarn : la ligne de score affichée SOUS le pseudo, ou {@code null} (relevé dans le bytecode). */
    public Text playerName;

    /** Les oreilles de deadmau5 : l'étiquette est alors remontée de dix pixels. */
    public boolean extraEars;
}
