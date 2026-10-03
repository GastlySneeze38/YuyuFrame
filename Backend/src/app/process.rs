/// `tokio::process::Command` sans fenêtre console sous Windows.
///
/// `java.exe` et `reg.exe` sont des applis console : lancées depuis le build
/// release (windows_subsystem "windows", aucune console à hériter), Windows leur
/// en alloue une neuve, visible le temps de leur exécution — les fenêtres qui
/// clignotaient au lancement du jeu (`java -version`, préférence GPU dans le
/// registre). Invisible en dev, où la console du launcher est héritée.
/// Tout processus enfant du launcher passe par ici.
pub fn hidden_command(program: impl AsRef<std::ffi::OsStr>) -> tokio::process::Command {
    #[allow(unused_mut)]
    let mut cmd = tokio::process::Command::new(program);
    #[cfg(target_os = "windows")]
    {
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}
