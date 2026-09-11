package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

/**
 * Fournisseur d'une implémentation de {@link Blaze3DGpu}, déclaré dans
 * {@code META-INF/services} et découvert par {@link Blaze3DGpus}.
 *
 * <p>Séparé de l'implémentation elle-même pour une raison précise : le
 * {@code ServiceLoader} INSTANCIE chaque fournisseur déclaré, sur toutes les
 * versions. Le fournisseur ne doit donc référencer AUCUN type du jeu — sinon
 * sa simple instanciation chargerait des classes absentes et échouerait sur
 * les autres versions. Seul {@link #create()}, appelé sur la bonne version,
 * touche à l'implémentation typée.
 */
public interface Blaze3DGpuProvider {

    /** {@code mcVersion} = propriété {@code launcheragent.mcVersion} (fournie par le launcher). */
    boolean supports(String mcVersion);

    Blaze3DGpu create();
}
