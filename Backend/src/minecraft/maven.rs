use std::path::PathBuf;

/// Coordonnée Maven `group:artifact:version[:classifier]`, telle qu'utilisée
/// par les manifestes de libs Mojang/Fabric/Forge — parsée une seule fois ici
/// plutôt que par chaque loader séparément.
pub struct MavenCoord<'a> {
    pub group_path: String,
    pub artifact: &'a str,
    pub version: &'a str,
    pub classifier: Option<&'a str>,
}

impl<'a> MavenCoord<'a> {
    pub fn parse(name: &'a str) -> Option<Self> {
        let parts: Vec<&str> = name.split(':').collect();
        if parts.len() < 3 {
            return None;
        }
        Some(Self {
            group_path: parts[0].replace('.', "/"),
            artifact: parts[1],
            version: parts[2],
            // Un classifier vide ("group:art:ver:") compte comme absent.
            classifier: parts.get(3).copied().filter(|c| !c.is_empty()),
        })
    }

    pub fn filename(&self) -> String {
        match self.classifier {
            Some(c) => format!("{}-{}-{}.jar", self.artifact, self.version, c),
            None => format!("{}-{}.jar", self.artifact, self.version),
        }
    }

    /// Chemin relatif standard `<group>/<artifact>/<version>/<filename>.jar`.
    pub fn relative_path(&self) -> PathBuf {
        PathBuf::from(&self.group_path).join(self.artifact).join(self.version).join(self.filename())
    }
}
