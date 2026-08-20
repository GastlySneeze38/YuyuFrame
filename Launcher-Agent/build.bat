@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

set "AGENT_DIR=%~dp0"
set "SRC_MAIN=%AGENT_DIR%src\main\java"
set "SRC_STUBS=%AGENT_DIR%src\stubs"
set "RES=%AGENT_DIR%src\main\resources"
set "LIB=%AGENT_DIR%lib"
set "OUT_MAIN=%AGENT_DIR%build\main"
set "OUT_STUBS=%AGENT_DIR%build\stubs"
set "OUT_ASM=%AGENT_DIR%build\_asm_tmp"
set "JAR=%AGENT_DIR%build\launcher-agent.jar"
set "VER_TMP=%TEMP%\launcheragent_ver.txt"

echo.
echo  ================================================
echo    YuyuFrame LauncherAgent - Build + Deploy
echo  ================================================
echo.

:: --- Trouver javac.exe et jar.exe automatiquement ----------------------------

set "JAVAC_CMD="
set "JAR_CMD="

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
        set "JAR_CMD=%JAVA_HOME%\bin\jar.exe"
    )
)

if not defined JAVAC_CMD (
    for %%R in ("C:\Program Files\Java" "C:\Program Files\Eclipse Adoptium" "C:\Program Files\Microsoft" "C:\Program Files\BellSoft" "C:\Program Files\Amazon Corretto") do (
        if not defined JAVAC_CMD (
            for /d %%D in ("%%~R\jdk-*") do (
                if not defined JAVAC_CMD (
                    if exist "%%~D\bin\javac.exe" (
                        set "JAVAC_CMD=%%~D\bin\javac.exe"
                        set "JAR_CMD=%%~D\bin\jar.exe"
                    )
                )
            )
        )
    )
)

if not defined JAVAC_CMD (
    echo [ERREUR] javac.exe introuvable.
    echo  Installe un JDK 17+ et configure JAVA_HOME.
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

:: JNA (BorderlessWindowNative, module "Fenetre sans bordure") : appel direct
:: de l'API Win32 (User32/Kernel32) depuis du Java pur, sans ecrire/compiler
:: le moindre code natif nous-memes — contrairement a content_core.dll/
:: rust_core.dll, aucune nouvelle DLL Rust pour cette feature.
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
powershell -NoProfile -Command "$q=[char]34; $files=Get-ChildItem -Recurse -Filter '*.java' '%AGENT_DIR%src\stubs' | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%STUBLIST%', $files)"

:: Garde-fou : une liste vide fait "reussir" javac trivialement (0 fichier a
:: compiler, exit code 0) sans AUCUNE erreur — deja arrive en CI (jar final
:: de 3 Ko, juste le manifeste, aucune classe dedans, personne ne s'en rend
:: compte tant que le jeu ne plante pas au lancement). Mieux vaut echouer ICI,
:: bruyamment, que produire un agent silencieusement casse.
for %%A in ("%STUBLIST%") do if %%~zA==0 (
    echo [ERREUR] Aucun fichier .java trouve dans src\stubs — chemin/checkout incorrect ?
    del "%STUBLIST%" 2>nul
    goto :error
)

"%JAVAC_CMD%" --release 8 -encoding UTF-8 -d "%OUT_STUBS%" "@%STUBLIST%"
del "%STUBLIST%" 2>nul
if errorlevel 1 (
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
set "SRCLIST=%TEMP%\launcheragent_sources.txt"
powershell -NoProfile -Command "$q=[char]34; $dirs=@('%AGENT_DIR%src\main\java'); $files=$dirs | ForEach-Object { Get-ChildItem -Recurse -Filter '*.java' $_ } | Select-Object -ExpandProperty FullName | ForEach-Object { $q+$_.Replace('\','/')+$q }; [IO.File]::WriteAllLines('%SRCLIST%', $files)"

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
"%JAVAC_CMD%" --release 8 -encoding UTF-8 -proc:none ^
  -cp "%LIB%\mixin.jar;%LIB%\asm-9.5.jar;%LIB%\asm-tree-9.5.jar;%LIB%\jna.jar;%LIB%\jna-platform.jar;%LIB%\mixinextras.jar;%OUT_STUBS%" ^
  -d "%OUT_MAIN%" ^
  "@%SRCLIST%"
del "%SRCLIST%" 2>nul
if errorlevel 1 (
    echo [ERREUR] Compilation echouee.
    goto :error
)
echo [Build] Compilation OK

:: --- Copier les ressources (mixins.launcheragent.json + META-INF) ------------

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

:: Garde-fou final : le jar reel fait ~330 Ko (184 classes). En dessous de
:: 50 Ko, quelque chose s'est mal passe en amont (OUT_MAIN quasi vide malgre
:: les gardes-fous ci-dessus) — ne JAMAIS deployer/publier un agent dans cet
:: etat (deja arrive : jar de 3 Ko, juste le manifeste, cause un
:: ClassNotFoundException/FATAL ERROR -javaagent au lancement du jeu).
if %JAR_KB% LSS 50 (
    echo [ERREUR] JAR anormalement petit ^(%JAR_KB% Ko^) — compilation probablement vide, build interrompu.
    goto :error
)

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

:error
echo.
echo  [BUILD ECHOUE]
echo.
if not defined CI pause
exit /b 1
