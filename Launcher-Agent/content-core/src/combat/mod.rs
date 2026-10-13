//! Le combat vu par le client : ce que Java récolte en jeu arrive ici, et
//! tout ce qui est décision ou calcul s'y fait (docs/scoring/).
//!
//! Java ne fait que relever et transmettre — positions, coups, paquets de
//! dégâts — sans rien retenir ni rien juger. Ce module décide de ce qui vaut
//! d'être gardé et l'écrit dans le journal du combat, seule source des
//! features et de la note calculées ensuite.
//!
//!   record    ce que Java envoie, tel quel
//!   journal   quoi écrire et quand (aucun fichier, aucune horloge : testable)
//!   writer    le fil qui tient le fichier
//!   bridge    les fonctions `yf_combat_*` appelées par Java
//!   score     du journal d'un duel à sa note
//!
//! Le fil du jeu ne fait que déposer un enregistrement dans une file : il
//! n'attend jamais le disque.

mod bridge;
mod journal;
mod record;
mod score;
mod writer;
