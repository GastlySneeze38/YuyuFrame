//! La coquille de l'application : tout ce qui tient au launcher lui-même —
//! sa fenêtre, son démarrage, les liens qui l'ouvrent, le dossier où il range
//! ses données — et à aucune de ses fonctionnalités.
//!
//!   startup      rattrapages joués une fois au démarrage
//!   tray         zone de notification, fenêtre fermée puis rouverte
//!   window       fenêtre masquée au lancement, arrière-plan
//!   deep_link    lien `yuyuframe://` reçu avant que l'interface soit prête
//!   pending      événements émis pendant que la fenêtre est fermée
//!   locale       pays deviné au premier démarrage
//!   storage      dossier de données, déplaçable
//!   system_info  mémoire de la machine
//!   process      lancement de processus sans console

pub mod deep_link;
pub mod locale;
pub mod pending;
pub mod process;
pub mod startup;
pub mod storage;
pub mod system_info;
pub mod tray;
pub mod window;
