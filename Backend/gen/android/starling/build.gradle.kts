// Le moteur Horizon pour Android (copie de Starling dans horizon-android/), en bibliothèque
// Android : son code Java et ses ressources sont lus directement dans horizon-android/,
// sans copie, et son espace de noms reste le leur (io.github.juanluci.starling) pour que
// leur code trouve sa classe R telle qu'il l'attend.
//
// La bibliothèque native du jeu, les petits lanceurs Java qui préparent les fichiers du jeu
// sur le téléphone et les assets sont produits par horizon-android/yuyu/build_engine.py,
// qui les dépose dans src/main/jniLibs, src/main/assets et src/main/res/values (ignorés
// par git). À lancer avant `tauri android build`.
//
// Notre seul ajout : fr.yuyuframe.game.YuyuGameActivity (src/main/java).

plugins {
    id("com.android.library")
}

val engine = "../../../../../horizon-android/packaging/android"

android {
    namespace = "io.github.juanluci.starling"
    compileSdk = 36
    defaultConfig {
        // AAudio, qu'utilise le son du jeu : Starling ne descend pas plus bas.
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets["main"].apply {
        java.srcDirs("$engine/java", "src/main/java")
        res.srcDirs("$engine/res", "src/main/res")
    }
}
