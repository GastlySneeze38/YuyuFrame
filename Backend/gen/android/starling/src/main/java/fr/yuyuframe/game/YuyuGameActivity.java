package fr.yuyuframe.game;

import io.github.juanluci.starling.R;
import io.github.juanluci.starling.StarlingActivity;

/**
 * L'écran du jeu Horizon dans YuyuFrame : celui de Starling, dont il hérite tout, avec deux
 * réponses changées. C'est la seule différence avec leur code, qui reste intact dans
 * horizon-android/.
 *
 * Le launcher ouvre cet écran dans son propre processus (« :game », voir le manifeste) après
 * avoir écrit les options de la partie dans horizon-args.txt, que le jeu lit au démarrage.
 */
public final class YuyuGameActivity extends StarlingActivity {
    /**
     * Jamais « version publiée » : c'est ce qui allume la proposition de mise à jour de Starling
     * (game/update_offer.rs), qui téléchargerait et installerait l'APK de Starling. Ici, les
     * mises à jour du moteur arrivent avec celles de YuyuFrame.
     */
    @Override
    public boolean releaseBuild() {
        return false;
    }

    /**
     * La version du moteur embarqué, pas celle de YuyuFrame : Starling s'en sert pour retrouver
     * les builds de mods natifs faits pour lui (NativeMods), qui ne valent que pour sa version.
     * Écrite par build_engine.py dans les ressources.
     */
    @Override
    public String versionName() {
        return getString(R.string.yuyu_engine_version);
    }
}
