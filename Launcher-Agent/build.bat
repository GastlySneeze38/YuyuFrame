@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

set "AGENT_DIR=%~dp0"
set "SRC_MAIN=%AGENT_DIR%src\main\java"
set "SRC_STUBS=%AGENT_DIR%src\stubs\v26_1"
set "RES=%AGENT_DIR%src\main\resources"
set "LIB=%AGENT_DIR%lib"
set "OUT_MAIN=%AGENT_DIR%build\main"
set "OUT_STUBS=%AGENT_DIR%build\stubs"
:: Unite 1.21.11 (API Blaze3D differente de la 26.1.2, meme noms de classes) —
:: voir la passe "Unite 1.21.11" plus bas. Ses SOURCES vivent dans
:: src\main\java comme le reste : c'est tout dossier nomme "v1_21_11" qui est
:: selectionne, pas une racine a part (2026-09-13).
set "SRC_STUBS_1211=%AGENT_DIR%src\stubs\v1_21_11"
set "OUT_STUBS_1211=%AGENT_DIR%build\stubs_1_21_11"
:: Unite 26.2 (refonte Blaze3D : GpuSurface, RenderPassDescriptor, GpuFormat...
:: memes noms de classes que la 26.1, API differente) — meme montage que la
:: 1.21.11, voir "Unite 26.2" plus bas. Stubs de depart = copie de ceux de la
:: 26.1 (2026-09-29), a aligner sur le vrai jar 26.2.
set "SRC_STUBS_262=%AGENT_DIR%src\stubs\v26_2"
set "OUT_STUBS_262=%AGENT_DIR%build\stubs_26_2"
:: Unite 26.3 (Blaze3D renomme renderpearl, GLFW remplace par SDL3) — meme
:: montage, voir "Unite 26.3" plus bas. Stubs de depart = copie de ceux de la
:: 26.2 avec la table des packages deplaces (2026-09-29).
set "SRC_STUBS_263=%AGENT_DIR%src\stubs\v26_3"
set "OUT_STUBS_263=%AGENT_DIR%build\stubs_26_3"
:: Unite 1.8.9 (Yarn legacy : MEMES noms de classes que les modernes, API
:: sans rapport) — meme montage que la 1.21.11, voir "Unite 1.8.9" plus bas.
set "SRC_STUBS_189=%AGENT_DIR%src\stubs\v1_8_9"
set "OUT_STUBS_189=%AGENT_DIR%build\stubs_1_8_9"
:: Couche LWJGL 2 -> 3 de la 1.8.9 (reprise de legacy-lwjgl3, LGPL-2.1) —
:: jar SEPARE lwjgl2-compat.jar, jamais dans launcher-agent.jar : ses classes
:: org.lwjgl.opengl.Display & co ne doivent pas apparaitre sur le classpath
:: des autres versions. Voir la passe "Couche LWJGL 2 -> 3" plus bas.
set "LIB_LWJGL3=%AGENT_DIR%lib\lwjgl3"
set "OUT_COMPAT=%AGENT_DIR%build\lwjgl2compat"
set "COMPAT_JAR=%AGENT_DIR%build\lwjgl2-compat.jar"
set "OUT_ASM=%AGENT_DIR%build\_asm_tmp"
set "JAR=%AGENT_DIR%build\launcher-agent.jar"
set "VER_TMP=%TEMP%\launcheragent_ver.txt"

echo.
echo  ================================================
echo    YuyuFrame LauncherAgent - Build + Deploy
echo  ================================================
echo.

:: --- Trouver un JDK 25 (javac.exe + jar.exe) ----------------------------------
:: L'agent est compile en --release 25 (2026-09-14, refonte 1.8.9 : toutes les
:: versions qui chargent l'agent tournent en Java 25). Un JDK plus ancien ne
:: sait pas produire ce bytecode : on cherche d'abord un dossier jdk-25*, puis
:: JAVA_HOME, et on VERIFIE la version dans les deux cas. Le launcher suit la
:: meme borne (LAUNCHER_AGENT_MIN_JAVA dans agents.rs).

set "JAVA_RELEASE=25"
set "JAVAC_CMD="
set "JAR_CMD="

for %%R in ("C:\Program Files\Java" "C:\Program Files\Eclipse Adoptium" "C:\Program Files\Microsoft" "C:\Program Files\BellSoft" "C:\Program Files\Amazon Corretto") do (
    if not defined JAVAC_CMD (
        for /d %%D in ("%%~R\jdk-%JAVA_RELEASE%*") do (
            if not defined JAVAC_CMD (
                if exist "%%~D\bin\javac.exe" (
                    set "JAVAC_CMD=%%~D\bin\javac.exe"
                    set "JAR_CMD=%%~D\bin\jar.exe"
                )
            )
        )
    )
)

if not defined JAVAC_CMD (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\javac.exe" (
            set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
            set "JAR_CMD=%JAVA_HOME%\bin\jar.exe"
        )
    )
)

if not defined JAVAC_CMD (
    echo [ERREUR] Aucun JDK %JAVA_RELEASE% trouve.
    echo  Installe un JDK %JAVA_RELEASE% ^(ex. Eclipse Temurin^) ou pointe JAVA_HOME dessus.
    goto :error
)

:: "javac 25.0.1" -> accepte ; "javac 24.0.2" -> refuse (ne sait pas --release 25).
"%JAVAC_CMD%" -version 2>&1 | findstr /b /c:"javac %JAVA_RELEASE%" >nul
if errorlevel 1 (
    echo [ERREUR] %JAVAC_CMD% n'est pas un JDK %JAVA_RELEASE% :
    "%JAVAC_CMD%" -version
    echo  Installe un JDK %JAVA_RELEASE% ^(ex. Eclipse Temurin^) ou pointe JAVA_HOME dessus.
    goto :error
)
echo [Java] %JAVAC_CMD%
echo.

:: --- Incrementer BUILD_VERSION dans LauncherAgent.java -----------------------

echo [Version] Mise a jour de BUILD_VERSION...
powershell -NoProfile -Command "$q=[char]34; $f='%AGENT_DIR%src\main\java\com\yuyuframe\launcheragent\agent\LauncherAgent.java'; $enc=New-Object System.Text.UTF8Encoding $false; $raw=[IO.File]::ReadAllBytes($f); $c=[System.Text.Encoding]::UTF8.GetString($raw); if([int]$c[0] -eq 0xFEFF){$c=$c.Substring(1)}; $pat='BUILD_VERSION\s*=\s*'+$q+'(\d{4}-\d{2}-\d{2})-v(\d+)'+$q; $m=[regex]::Match($c,$pat); if($m.Success){ $old=$m.Groups[2].Value; $new=[int]$old+1; $d=(Get-Date).ToString('yyyy-MM-dd'); $r='BUILD_VERSION = '+$q+$d+'-v'+$new+$q; $c=$c.Replace($m.Value,$r); $sw=New-Object System.IO.StreamWriter($f,$false,$enc); $sw.Write($c); $sw.Close(); Set-Content '%VER_TMP%' ('v'+$old+' vers v'+$new+' ('+$d+')') -Encoding ASCII } else { Set-Content '%VER_TMP%' 'SKIP (pattern non trouve)' -Encoding ASCII }"
set /p VER_MSG=< "%VER_TMP%"
del "%VER_TMP%" 2>nul
echo [Version] %VER_MSG%
echo.

:: --- Telecharger les dependances manquantes ----------------------------------
:: Memes dependances que p2p-agent (Mixin standalone + ASM) mais copie
:: independante dans Launcher-Agent\lib\ — aucun partage de jar entre agents.

if not exist "%LIB%" mkdir "%LIB%"

:: BUG TROUVE (utilisateur, MC 26.1.2/Java 25 : "aucun Mixin ne s'applique,
:: ClassFormatError/Unsupported class file major version 69 partout") —
:: repo.spongepowered.org/.../org/spongepowered/mixin/0.8.7 est le projet
:: SpongePowered D'ORIGINE, non maintenu pour les JDK recents (son
:: MixinEnvironment.CompatibilityLevel s'arrete a JAVA_21, verifie par javap).
:: net.fabricmc:sponge-mixin est LE FORK maintenu par Fabric (utilise par
:: Fabric Loader lui-meme, meme version "0.17.3+mixin.0.8.7" mais contenu
:: different — 1.54 Mo contre 1.13 Mo pour l'ancien, JAVA_25 confirme present
:: par javap) — c'est CETTE version qu'il faut utiliser, jamais l'originale.
if not exist "%LIB%\mixin.jar" (
    echo [Deps] Telechargement Mixin 0.8.7 ^(fork Fabric, compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar' -OutFile '%LIB%\mixin.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement Mixin echoue & goto :error )
)
:: Meme raison : asm 9.5 (Maven Central) ne connait pas les class files
:: Java 22+ (V22..V25 absents d'Opcodes.class) — notre propre lecture ASM
:: (IsolatedBootstrap.discoverMixinTargets, ScreenStubPatcher) y serait
:: exposee au meme risque. 9.10.1 = derniere version stable, verifiee
:: (javap sur Opcodes.class) porter V25=69 explicitement. Nom de fichier
:: local INCHANGE ("asm-9.5.jar" etc., pas renomme partout) pour ne pas
:: casser les references de classpath ailleurs (launcher.rs) — seul le
:: CONTENU telecharge change.
if not exist "%LIB%\asm-9.5.jar" (
    echo [Deps] Telechargement ASM 9.10.1 ^(compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar' -OutFile '%LIB%\asm-9.5.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement ASM echoue & goto :error )
)
if not exist "%LIB%\asm-tree-9.5.jar" (
    echo [Deps] Telechargement ASM-Tree 9.10.1 ^(compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar' -OutFile '%LIB%\asm-tree-9.5.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement ASM-Tree echoue & goto :error )
)
if not exist "%LIB%\asm-util-9.5.jar" (
    echo [Deps] Telechargement ASM-Util 9.10.1 ^(compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar' -OutFile '%LIB%\asm-util-9.5.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement ASM-Util echoue & goto :error )
)
if not exist "%LIB%\asm-analysis-9.5.jar" (
    echo [Deps] Telechargement ASM-Analysis 9.10.1 ^(compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar' -OutFile '%LIB%\asm-analysis-9.5.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement ASM-Analysis echoue & goto :error )
)
if not exist "%LIB%\asm-commons-9.5.jar" (
    echo [Deps] Telechargement ASM-Commons 9.10.1 ^(compat Java 25^)...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar' -OutFile '%LIB%\asm-commons-9.5.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement ASM-Commons echoue & goto :error )
)

:: MixinExtras (ROADMAP-agent.md Phase 3) : compagnon de Sponge Mixin
:: (injecteurs @WrapOperation/@ModifyReturnValue/@WrapMethod/@ModifyReceiver/
:: @Local/@Share/@Cancellable), utilise massivement par Fabric API elle-meme.
:: mixinextras-common (PAS -fabric/-forge, qui sont des variantes deja
:: pre-shadees pour ces loaders precis) : le bon choix ici, cet agent a son
:: propre IMixinService standalone (LauncherMixinService), ni Fabric Loader
:: ni ModLauncher/Forge.
::
:: DEPENDANCE AJOUTEE ICI SEULEMENT (compilation) — le cablage runtime
:: (classloader isole sous Fabric, classpath de lancement vanilla cote Rust,
:: appel MixinExtrasBootstrap.init()) reste a faire separement, voir
:: ROADMAP-agent.md Phase 3.2 : sans lui, ce jar compile mais n'est
:: chargeable par aucun des deux modes de lancement en l'etat.
::
:: -proc:none (voir plus bas) desactive aussi l'annotation processor de
:: mixinextras-common — INTENTIONNEL, meme raisonnement que pour celui de
:: Mixin lui-meme (crash CI documente plus haut) : cet AP ne sert QUE la
:: feature "Expressions" (@ModifyExpressionValue, matching semantique par
:: @Definition) — tous les autres injecteurs (@WrapOperation etc., ceux
:: vises par la Phase 3) sont de simples annotations lues a la transformation
:: de classe par MixinExtras, aucun codegen de compilation requis.
if not exist "%LIB%\mixinextras.jar" (
    echo [Deps] Telechargement MixinExtras 0.5.4...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/io/github/llamalad7/mixinextras-common/0.5.4/mixinextras-common-0.5.4.jar' -OutFile '%LIB%\mixinextras.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement MixinExtras echoue & goto :error )
)

:: LWJGL 3.4.1 (couche LWJGL 2 -> 3 de la 1.8.9) : COMPILATION SEULEMENT —
:: jamais embarque ni deploye, le launcher met les vrais jars LWJGL 3 sur le
:: classpath du jeu. Meme version que Minecraft 26.1.2. Copie depuis les
:: bibliotheques du launcher si elles sont deja la, sinon Maven Central.
if not exist "%LIB_LWJGL3%" mkdir "%LIB_LWJGL3%"
for %%M in (lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal) do (
    if not exist "%LIB_LWJGL3%\%%M-3.4.1.jar" (
        if exist "%APPDATA%\YuyuFrame\.minecraft\libraries\org\lwjgl\%%M\3.4.1\%%M-3.4.1.jar" (
            echo [Deps] Copie %%M 3.4.1 depuis les bibliotheques du launcher...
            copy /Y "%APPDATA%\YuyuFrame\.minecraft\libraries\org\lwjgl\%%M\3.4.1\%%M-3.4.1.jar" "%LIB_LWJGL3%\%%M-3.4.1.jar" >nul
        ) else (
            echo [Deps] Telechargement %%M 3.4.1...
            powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/lwjgl/%%M/3.4.1/%%M-3.4.1.jar' -OutFile '%LIB_LWJGL3%\%%M-3.4.1.jar' -UseBasicParsing"
        )
        if not exist "%LIB_LWJGL3%\%%M-3.4.1.jar" ( echo [ERREUR] %%M 3.4.1 introuvable & goto :error )
    )
)
set "CP_LWJGL3=%LIB_LWJGL3%\lwjgl-3.4.1.jar;%LIB_LWJGL3%\lwjgl-glfw-3.4.1.jar;%LIB_LWJGL3%\lwjgl-opengl-3.4.1.jar;%LIB_LWJGL3%\lwjgl-openal-3.4.1.jar"

:: JNA (MumbleLinkBridge, ReadyEventSignal) : appel direct de l'API Win32
:: (Kernel32) depuis du Java pur, sans ecrire/compiler le moindre code natif
:: nous-memes — contrairement a content_core.dll/rust_core.dll, aucune
:: nouvelle DLL Rust pour ces features.
if not exist "%LIB%\jna.jar" (
    echo [Deps] Telechargement JNA 5.14.0...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar' -OutFile '%LIB%\jna.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement JNA echoue & goto :error )
)
if not exist "%LIB%\jna-platform.jar" (
    echo [Deps] Telechargement JNA-Platform 5.14.0...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar' -OutFile '%LIB%\jna-platform.jar' -UseBasicParsing"
    if errorlevel 1 ( echo [ERREUR] Telechargement JNA-Platform echoue & goto :error )
)

:: --- Compiler les stubs MC (compile-only, non inclus dans le JAR final) ------

if exist "%OUT_STUBS%" rmdir /s /q "%OUT_STUBS%"
mkdir "%OUT_STUBS%"
echo [Stubs] Compilation des stubs Minecraft...

set "STUBLIST=%TEMP%\launcheragent_stubs.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_STUBS%' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST%', $files)"

:: Garde-fou : une liste vide fait "reussir" javac trivialement (0 fichier a
:: compiler, exit code 0) sans AUCUNE erreur — deja arrive en CI (jar final
:: de 3 Ko, juste le manifeste, aucune classe dedans, personne ne s'en rend
:: compte tant que le jeu ne plante pas au lancement). Mieux vaut echouer ICI,
:: bruyamment, que produire un agent silencieusement casse.
for %%A in ("%STUBLIST%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs\v26_1 — chemin/checkout incorrect ?
    del "%STUBLIST%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -d "%OUT_STUBS%" "@%STUBLIST%"
set "JAVAC_RC=!errorlevel!"
del "%STUBLIST%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation stubs echouee.
    goto :error
)
echo [Stubs] OK

:: --- Compiler le code principal ----------------------------------------------

if exist "%OUT_MAIN%" rmdir /s /q "%OUT_MAIN%"
mkdir "%OUT_MAIN%"
echo [Build] Compilation principale...

:: BUG TROUVE (test utilisateur, 26.1.2, session portage Freelook/refmap) :
:: src\stubs etait inclus ICI dans la liste de SOURCES compilees vers
:: OUT_MAIN (qui finit dans le JAR final, voir Packaging plus bas) — en plus
:: d'etre deja compile separement vers OUT_STUBS et mis sur le -cp juste en
:: dessous pour la resolution de types. Resultat : les classes stub
:: (compile-only par design, voir commentaire "non inclus dans le JAR final"
:: ci-dessus) finissaient QUAND MEME embarquees dans launcher-agent.jar.
:: Repere concretement via le stub DrawContext (ajoute cette session pour
:: ClearOverlaysMixin) : sur 26.1.2, UiRenderer.flushPendingModernItemIcons
:: cherche par nom "net.minecraft.client.gui.DrawContext" AVANT de retomber
:: sur le vrai nom Mojang "GuiGraphicsExtractor" — Class.forName trouvait
:: notre stub vide (embarque par erreur dans le jar, visible par Knot) au
:: lieu d'echouer proprement, et getDeclaredConstructor() plantait dessus
:: (NoSuchMethodException, aucun constructeur (Minecraft,GuiRenderState,int,int)
:: sur un stub sans corps) — icones d'armure invisibles en boucle, 35k+ fois
:: dans le log. Fix : stubs UNIQUEMENT sur le classpath (-cp ci-dessous),
:: plus jamais dans la liste de sources de cette compilation.
::
:: Tout dossier "v1_21_11" est EXCLU de cette passe : il est compile plus bas,
:: contre les stubs 1.21.11 (meme noms de classes que la 26.1.2, API
:: differente — les deux ne tiennent pas sur un classpath). Le filtre porte
:: sur un SEGMENT de chemin complet (\v1_21_11\), jamais sur une sous-chaine
:: de nom de fichier. Idem pour tout dossier "v1_8_9" (unite 1.8.9), tout
:: dossier "v26_2" (unite 26.2), "v26_3" (unite 26.3), et pour la couche LWJGL 2 -> 3 (dossier
:: lwjgl2compat + arborescence org\lwjgl), compilee dans son propre jar.
set "SRCLIST=%TEMP%\launcheragent_sources.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -notmatch '\\v1_21_11\\' -and $_ -notmatch '\\v1_8_9\\' -and $_ -notmatch '\\v26_2\\' -and $_ -notmatch '\\v26_3\\' -and $_ -notmatch '\\lwjgl2compat\\' -and $_ -notmatch '\\src\\main\\java\\org\\lwjgl\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST%', $files)"

:: Meme garde-fou que pour STUBLIST — voir plus haut.
for %%A in ("%SRCLIST%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\main\java — chemin/checkout incorrect ?
    del "%SRCLIST%" 2>nul
    goto :error
)

:: -proc:none : mixin.jar sur le -cp expose l'annotation processor Sponge
:: Mixin (META-INF/services), auto-detecte par javac par defaut - on ne
:: l'utilise jamais (refmap genere a la compilation), tout est resolu a
:: l'execution via LauncherMixinService. Sur le runner CI, ce processor
:: plante (NoClassDefFoundError: com.google.gson.JsonParseException, Gson
:: absent du classpath minimal) et javac continue quand meme (exit 0) SANS
:: emettre la moindre classe - jar final de 2 Ko, silencieux avant les
:: gardes-fous ci-dessus. -proc:none l'empeche de tourner du tout, plutot
:: que de corriger un mecanisme qu'on ne veut pas.
"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\asm-9.5.jar;%LIB%\asm-tree-9.5.jar;%LIB%\jna.jar;%LIB%\jna-platform.jar;%LIB%\mixinextras.jar;%OUT_STUBS%;%CP_LWJGL3%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation echouee.
    goto :error
)
echo [Build] Compilation OK

:: --- Couche LWJGL 2 -> 3 (1.8.9) : lwjgl2-compat.jar -------------------------
:: Reprise de legacy-lwjgl3 (moehreag, LGPL-2.1) : API LWJGL 2 (Display,
:: Keyboard, Mouse, Sys, GLU, vecteurs...) au-dessus de LWJGL 3 / GLFW.
:: Sources : dossier com\yuyuframe\launcheragent\lwjgl2compat (notre code et
:: les implementations reprises) + src\main\java\org\lwjgl (l'API LWJGL 2,
:: qui doit garder ses noms de paquet). Compilee contre les jars LWJGL 3 de
:: lib\lwjgl3 uniquement, vers %OUT_COMPAT% puis un jar a part : jamais dans
:: launcher-agent.jar (voir la definition de COMPAT_JAR en haut).

if exist "%OUT_COMPAT%" rmdir /s /q "%OUT_COMPAT%"
mkdir "%OUT_COMPAT%"
echo [Build LWJGL2 compat] Compilation de la couche LWJGL 2 -^> 3...
set "SRCLIST_COMPAT=%TEMP%\launcheragent_sources_compat.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -match '\\lwjgl2compat\\' -or $_ -match '\\src\\main\\java\\org\\lwjgl\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST_COMPAT%', $files)"

for %%A in ("%SRCLIST_COMPAT%") do if %%~zA==0 (
    echo [ERREUR] Aucune source de la couche LWJGL 2 -^> 3 trouvee — chemin/checkout incorrect ?
    del "%SRCLIST_COMPAT%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none -nowarn ^
  -cp "%CP_LWJGL3%" ^
  -d "%OUT_COMPAT%" ^
  "@%SRCLIST_COMPAT%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST_COMPAT%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation de la couche LWJGL 2 -^> 3 echouee.
    goto :error
)
if not exist "%OUT_COMPAT%\META-INF" mkdir "%OUT_COMPAT%\META-INF"
copy /Y "%SRC_MAIN%\com\yuyuframe\launcheragent\lwjgl2compat\LICENSE-legacy-lwjgl3.txt" "%OUT_COMPAT%\META-INF\LICENSE-legacy-lwjgl3.txt" >nul
echo [Build LWJGL2 compat] Compilation OK

:: --- Unite 1.21.11 : stubs + code type, compiles A PART ---------------------
:: La 1.21.11 et la 26.1.2 exposent des classes com.mojang.blaze3d.* de MEME
:: NOM mais d'API differente (ColorTargetState/DepthStencilState absents en
:: 1.21.11, etc.) : impossible de les avoir toutes deux sur un meme classpath.
:: Cette unite est donc compilee contre SES stubs (src\stubs\v1_21_11)
:: UNIQUEMENT — surtout pas contre %OUT_STUBS% (26.1.2) — avec %OUT_MAIN% sur
:: le -cp pour voir les interfaces du moteur, et emise DANS %OUT_MAIN% pour
:: finir dans le meme jar. Comme pour les stubs 26.1.2 : les stubs 1.21.11
:: ne sont JAMAIS emis dans %OUT_MAIN% (ils ne doivent pas finir dans le jar).
::
:: Sources : TOUT dossier "v1_21_11" de src\main\java — code type du moteur
:: (apigraphic\era\blaze3d\v1_21_11) ET mixins/liaisons (apimixin\v1_21_11).
:: Les mixins n'y nomment aucun type du jeu (cibles en chaines, parametres en
:: Object) : cette passe ne change rien pour eux, elle les range avec leur
:: version. Voir ...\era\blaze3d\v1_21_11\package-info.java.

if exist "%OUT_STUBS_1211%" rmdir /s /q "%OUT_STUBS_1211%"
mkdir "%OUT_STUBS_1211%"
echo [Stubs 1.21.11] Compilation des stubs Blaze3D 1.21.11...

set "STUBLIST_1211=%TEMP%\launcheragent_stubs_1211.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_STUBS_1211%' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST_1211%', $files)"

:: Meme garde-fou que pour STUBLIST — voir plus haut.
for %%A in ("%STUBLIST_1211%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs\v1_21_11 — chemin/checkout incorrect ?
    del "%STUBLIST_1211%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -d "%OUT_STUBS_1211%" "@%STUBLIST_1211%"
set "JAVAC_RC=!errorlevel!"
del "%STUBLIST_1211%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation stubs 1.21.11 echouee.
    goto :error
)
echo [Stubs 1.21.11] OK

echo [Build 1.21.11] Compilation du code type 1.21.11...
set "SRCLIST_1211=%TEMP%\launcheragent_sources_1211.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -match '\\v1_21_11\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST_1211%', $files)"

:: Meme garde-fou — voir plus haut.
for %%A in ("%SRCLIST_1211%") do if %%~zA==0 (
    echo [ERREUR] Aucun dossier v1_21_11 trouve dans src\main\java — chemin/checkout incorrect ?
    del "%SRCLIST_1211%" 2>nul
    goto :error
)

:: -proc:none : meme raison que la compilation principale (voir plus haut).
"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\mixinextras.jar;%OUT_MAIN%;%OUT_STUBS_1211%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST_1211%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST_1211%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation du code type 1.21.11 echouee.
    goto :error
)
echo [Build 1.21.11] Compilation OK

:: --- Unite 26.2 : stubs + code type, compiles A PART ------------------------
:: Meme raison et meme montage que l'unite 1.21.11 : la 26.2 a refondu Blaze3D
:: (GpuSurface, RenderPassDescriptor, BindGroupLayout, GpuFormat,
:: PrimitiveTopology ; TextureFormat, VertexFormat$Mode et getMainRenderTarget
:: supprimes) sous les MEMES noms de classes que la 26.1 — les deux API ne
:: tiennent pas sur un classpath. Stubs src\stubs\v26_2 sur le -cp UNIQUEMENT,
:: jamais emis dans %OUT_MAIN%. Sources : tout dossier "v26_2" de src\main\java
:: (apimixin\v26_2 et apigraphic\era\blaze3d\v26_2).

if exist "%OUT_STUBS_262%" rmdir /s /q "%OUT_STUBS_262%"
mkdir "%OUT_STUBS_262%"
echo [Stubs 26.2] Compilation des stubs 26.2...

set "STUBLIST_262=%TEMP%\launcheragent_stubs_262.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_STUBS_262%' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST_262%', $files)"

:: Meme garde-fou que pour STUBLIST — voir plus haut.
for %%A in ("%STUBLIST_262%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs\v26_2 — chemin/checkout incorrect ?
    del "%STUBLIST_262%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -d "%OUT_STUBS_262%" "@%STUBLIST_262%"
set "JAVAC_RC=!errorlevel!"
del "%STUBLIST_262%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation stubs 26.2 echouee.
    goto :error
)
echo [Stubs 26.2] OK

echo [Build 26.2] Compilation du code type 26.2...
set "SRCLIST_262=%TEMP%\launcheragent_sources_262.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -match '\\v26_2\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST_262%', $files)"

:: Meme garde-fou — voir plus haut.
for %%A in ("%SRCLIST_262%") do if %%~zA==0 (
    echo [ERREUR] Aucun dossier v26_2 trouve dans src\main\java — chemin/checkout incorrect ?
    del "%SRCLIST_262%" 2>nul
    goto :error
)

:: -proc:none : meme raison que la compilation principale (voir plus haut).
"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\mixinextras.jar;%OUT_MAIN%;%OUT_STUBS_262%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST_262%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST_262%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation du code type 26.2 echouee.
    goto :error
)
echo [Build 26.2] Compilation OK

:: --- Unite 26.3 : stubs + code type, compiles A PART ------------------------
:: Meme montage que l'unite 26.2 : la 26.3 a renomme Blaze3D en renderpearl
:: (com.mojang.renderpearl.api.*) mais garde com.mojang.blaze3d.* pour
:: RenderTarget, Window, RenderSystem, InputConstants... avec une API
:: differente (SDL3 au lieu de GLFW) — incompatible avec les stubs 26.2 sur un
:: meme classpath. Sources : tout dossier "v26_3" de src\main\java.

if exist "%OUT_STUBS_263%" rmdir /s /q "%OUT_STUBS_263%"
mkdir "%OUT_STUBS_263%"
echo [Stubs 26.3] Compilation des stubs 26.3...

set "STUBLIST_263=%TEMP%\launcheragent_stubs_263.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_STUBS_263%' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST_263%', $files)"

:: Meme garde-fou que pour STUBLIST — voir plus haut.
for %%A in ("%STUBLIST_263%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs\v26_3 — chemin/checkout incorrect ?
    del "%STUBLIST_263%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -d "%OUT_STUBS_263%" "@%STUBLIST_263%"
set "JAVAC_RC=!errorlevel!"
del "%STUBLIST_263%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation stubs 26.3 echouee.
    goto :error
)
echo [Stubs 26.3] OK

echo [Build 26.3] Compilation du code type 26.3...
set "SRCLIST_263=%TEMP%\launcheragent_sources_263.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -match '\\v26_3\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST_263%', $files)"

:: Meme garde-fou — voir plus haut.
for %%A in ("%SRCLIST_263%") do if %%~zA==0 (
    echo [ERREUR] Aucun dossier v26_3 trouve dans src\main\java — chemin/checkout incorrect ?
    del "%SRCLIST_263%" 2>nul
    goto :error
)

:: -proc:none : meme raison que la compilation principale (voir plus haut).
"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\mixinextras.jar;%OUT_MAIN%;%OUT_STUBS_263%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST_263%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST_263%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation du code type 26.3 echouee.
    goto :error
)
echo [Build 26.3] Compilation OK

:: --- Unite 1.8.9 : stubs Yarn legacy + liaisons, compiles A PART ------------
:: Meme raison et meme montage que l'unite 1.21.11 : Yarn legacy reutilise les
:: noms des classes modernes (MinecraftClient, Window, Text...) avec une API
:: sans rapport (Window = ancien ScaledResolution, Text = interface...). Stubs
:: src\stubs\v1_8_9 sur le -cp UNIQUEMENT, jamais emis dans %OUT_MAIN%.
:: Sources : tout dossier "v1_8_9" de src\main\java (apimixin\v1_8_9 : mixins
:: nommant le jeu en chaines + AccessorBindings189 type).

if exist "%OUT_STUBS_189%" rmdir /s /q "%OUT_STUBS_189%"
mkdir "%OUT_STUBS_189%"
echo [Stubs 1.8.9] Compilation des stubs Yarn legacy 1.8.9...

set "STUBLIST_189=%TEMP%\launcheragent_stubs_189.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_STUBS_189%' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST_189%', $files)"

:: Meme garde-fou que pour STUBLIST — voir plus haut.
for %%A in ("%STUBLIST_189%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs\v1_8_9 — chemin/checkout incorrect ?
    del "%STUBLIST_189%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -d "%OUT_STUBS_189%" "@%STUBLIST_189%"
set "JAVAC_RC=!errorlevel!"
del "%STUBLIST_189%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation stubs 1.8.9 echouee.
    goto :error
)
echo [Stubs 1.8.9] OK

echo [Build 1.8.9] Compilation du code type 1.8.9...
set "SRCLIST_189=%TEMP%\launcheragent_sources_189.txt"
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%SRC_MAIN%' | Select-Object -ExpandProperty FullName | Where-Object { $_ -match '\\v1_8_9\\' } | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST_189%', $files)"

:: Meme garde-fou — voir plus haut.
for %%A in ("%SRCLIST_189%") do if %%~zA==0 (
    echo [ERREUR] Aucun dossier v1_8_9 trouve dans src\main\java — chemin/checkout incorrect ?
    del "%SRCLIST_189%" 2>nul
    goto :error
)

:: -proc:none : meme raison que la compilation principale (voir plus haut).
"%JAVAC_CMD%" --release %JAVA_RELEASE% -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\mixinextras.jar;%OUT_MAIN%;%OUT_STUBS_189%;%OUT_COMPAT%;%CP_LWJGL3%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST_189%"
set "JAVAC_RC=!errorlevel!"
del "%SRCLIST_189%" 2>nul
if not "!JAVAC_RC!"=="0" (
    echo [ERREUR] Compilation du code type 1.8.9 echouee.
    goto :error
)
echo [Build 1.8.9] Compilation OK

:: --- Copier les ressources (template Mixin apimixin, lang, textures, META-INF) -

echo [Build] Copie des ressources...
xcopy /s /e /y /q "%RES%\" "%OUT_MAIN%\" >nul
echo [Build] Ressources copiees

:: --- ASM NON embarque dans le JAR (volontaire) --------------------------------
:: launcher-agent.jar reste un JAR "fin" : les classes ASM ne sont jamais copiees
:: dedans. Raison : le jar d'un -javaagent est ajoute par la JVM au classpath du
:: system classloader — si on embarque ASM ET qu'une copie distincte d'ASM est
:: deja sur ce meme classpath (notre propre asm-9.5.jar ajoute par launcher.rs,
:: OU la copie de Fabric Loader quand l'instance utilise Fabric), Fabric Knot
:: detecte des classes ASM dupliquees au demarrage et refuse de lancer
:: ("duplicate ASM classes found on classpath"). Le launcher ajoute toujours
:: asm-9.5.jar/asm-tree-9.5.jar separement au -cp pour le mode vanilla, et les
:: omet quand Fabric est le loader (sa propre copie suffit) — voir launcher.rs.

:: --- Creer le JAR final ------------------------------------------------------

echo [Build] Packaging JAR...
if exist "%JAR%" del "%JAR%"
"%JAR_CMD%" --create --file="%JAR%" ^
    --manifest="%RES%\META-INF\MANIFEST.MF" ^
    -C "%OUT_MAIN%" .
if errorlevel 1 (
    echo [ERREUR] Packaging JAR echoue.
    goto :error
)
for %%F in ("%JAR%") do set /a JAR_KB=%%~zF / 1024
echo [Build] JAR cree : build\launcher-agent.jar (%JAR_KB% Ko)

if exist "%COMPAT_JAR%" del "%COMPAT_JAR%"
"%JAR_CMD%" --create --file="%COMPAT_JAR%" -C "%OUT_COMPAT%" .
if errorlevel 1 (
    echo [ERREUR] Packaging lwjgl2-compat.jar echoue.
    goto :error
)
echo [Build] JAR cree : build\lwjgl2-compat.jar

:: Garde-fou final, DEUX niveaux.
::
:: 1) Plancher absolu : un jar sous 50 Ko ne contient au mieux que le
::    manifeste — deja arrive (3 Ko), cause un ClassNotFoundException /
::    FATAL ERROR -javaagent au lancement du jeu.
::
:: 2) Comparaison au build PRECEDENT. Le plancher seul ne protegeait plus
::    rien : le jar reel fait ~890 Ko, et le commentaire d'origine parlait
::    encore de "~330 Ko". Le 2026-08-31, TROIS builds casses sont passes
::    (754, 628 et 543 Ko) — chacun avec une erreur javac, "Compilation OK"
::    affiche quand meme, et un deploiement effectif par-dessus un agent qui
::    marchait. javac peut echouer sur UNE classe et emettre toutes les
::    autres : le jar reste gros, mais amoure. Une chute brutale de taille
::    est le signal fiable, pas la taille absolue.
::
::    Seuil a 80 %% : laisse passer une suppression de code normale (le
::    retrait de l'apercu shulker a fait -6 Ko, soit -0,7 %%) et attrape les
::    trois cas ci-dessus (-15 %% au moins).
set "LAST_KB_FILE=%AGENT_DIR%build\.last-jar-kb"

if %JAR_KB% LSS 50 (
    echo [ERREUR] JAR anormalement petit ^(%JAR_KB% Ko^) — compilation probablement vide, build interrompu.
    goto :error
)

set "PREV_KB="
if exist "%LAST_KB_FILE%" set /p PREV_KB=<"%LAST_KB_FILE%"
if defined PREV_KB (
    set /a MIN_KB=%PREV_KB% * 80 / 100
    call :checkShrink
    if errorlevel 1 goto :error
)
echo %JAR_KB%>"%LAST_KB_FILE%"

:: --- Deployer dans AppData\YuyuFrame\agent\ ----------------------------------
:: Sous-dossier dedie, separe de %APPDATA%\YuyuFrame\p2p\ — ne jamais melanger
:: les jars/DLL des deux agents (voir docs/LauncherAgent/index.md).

set "AGENT_DEPLOY_DIR=%APPDATA%\YuyuFrame\agent"
set "LIBS_DEPLOY_DIR=%AGENT_DEPLOY_DIR%\libs"
echo [Deploy] Destination : %AGENT_DEPLOY_DIR%
if not exist "%AGENT_DEPLOY_DIR%" mkdir "%AGENT_DEPLOY_DIR%"
if not exist "%LIBS_DEPLOY_DIR%"  mkdir "%LIBS_DEPLOY_DIR%"
copy /Y "%JAR%"                       "%AGENT_DEPLOY_DIR%\launcher-agent.jar"    >nul
copy /Y "%LIB%\mixin.jar"             "%LIBS_DEPLOY_DIR%\mixin.jar"              >nul
copy /Y "%LIB%\asm-9.5.jar"           "%LIBS_DEPLOY_DIR%\asm-9.5.jar"            >nul
copy /Y "%LIB%\asm-tree-9.5.jar"      "%LIBS_DEPLOY_DIR%\asm-tree-9.5.jar"       >nul
copy /Y "%LIB%\asm-util-9.5.jar"      "%LIBS_DEPLOY_DIR%\asm-util-9.5.jar"       >nul
copy /Y "%LIB%\asm-analysis-9.5.jar"  "%LIBS_DEPLOY_DIR%\asm-analysis-9.5.jar"   >nul
copy /Y "%LIB%\asm-commons-9.5.jar"   "%LIBS_DEPLOY_DIR%\asm-commons-9.5.jar"    >nul
copy /Y "%LIB%\jna.jar"               "%LIBS_DEPLOY_DIR%\jna.jar"                >nul
copy /Y "%LIB%\jna-platform.jar"      "%LIBS_DEPLOY_DIR%\jna-platform.jar"       >nul
copy /Y "%LIB%\mixinextras.jar"       "%LIBS_DEPLOY_DIR%\mixinextras.jar"        >nul
copy /Y "%COMPAT_JAR%"                "%LIBS_DEPLOY_DIR%\lwjgl2-compat.jar"      >nul
if exist "%~dp0content-core\target\release\content_core.dll" (
    copy /Y "%~dp0content-core\target\release\content_core.dll" "%AGENT_DEPLOY_DIR%\content_core.dll" >nul
    echo [Deploy] content_core.dll deploye
) else (
    echo [Deploy] content_core.dll absente ^(non implementee — voir docs/LauncherAgent/index.md^)
)
if errorlevel 1 (
    echo [ERREUR] Deploiement echoue.
    goto :error
)
echo [Deploy] Deploye avec succes

:: --- Resume ------------------------------------------------------------------

echo.
echo  ================================================
echo    Build termine !  Version : %VER_MSG%
echo    %AGENT_DEPLOY_DIR%\launcher-agent.jar
echo    %LIBS_DEPLOY_DIR%\mixin.jar + asm-*.jar
echo  ================================================
echo.
if not defined CI pause
exit /b 0

:: Sous-routine et non test en ligne : dans un bloc if(...) parenthese, cmd
:: developpe TOUTES les variables au moment ou il lit le bloc — %MIN_KB%,
:: calcule juste au-dessus DANS le meme bloc, y vaudrait sa valeur d'AVANT.
:: Un call re-developpe a l'execution, sans dependre de setlocal
:: enabledelayedexpansion (que ce script n'active pas).
:checkShrink
if %JAR_KB% GEQ %MIN_KB% exit /b 0
echo.
echo [ERREUR] JAR anormalement PETIT par rapport au build precedent :
echo          %JAR_KB% Ko contre %PREV_KB% Ko ^(seuil : %MIN_KB% Ko^).
echo          javac a probablement echoue sur une classe tout en emettant
echo          les autres — relire la sortie de compilation ci-dessus, le
echo          "Compilation OK" ne veut rien dire dans ce cas.
echo          Si la baisse est VOULUE (grosse suppression de code) :
echo          supprimer build\.last-jar-kb puis relancer.
::
:: VOLONTAIREMENT pas d'auto-guerison (ecrire la nouvelle taille malgre
:: l'echec, pour qu'un second lancement passe) : c'est exactement le reflexe
:: qu'on a en cas d'echec — relancer — et le garde-fou serait alors muet la
:: ou il vient de detecter un vrai probleme. Il faut un geste explicite.
exit /b 1

:error
echo.
echo  [BUILD ECHOUE]
echo.
if not defined CI pause
exit /b 1
