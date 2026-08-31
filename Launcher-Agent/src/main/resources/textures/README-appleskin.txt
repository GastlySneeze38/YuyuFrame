appleskin_icons.png
===================

Origine : https://github.com/squeek502/AppleSkin
Fichier : resources/assets/appleskin/textures/icons.png (480 octets, 256x256, PNG palettise)
Licence : The Unlicense — domaine public. Copie, modification et
          redistribution explicitement autorisees, sans obligation
          d'attribution. Cette note est volontaire, pas une exigence.

Pourquoi cette copie
--------------------
Les indicateurs de saturation et d'epuisement n'ont AUCUN equivalent dans les
assets vanilla : ce sont des donnees que l'interface du jeu ne montre nulle
part, donc pour lesquelles il n'existe pas de sprite a reutiliser. Les
redessiner a la main donnait un liseré qui ne ressemblait pas a AppleSkin.

Les APERCUS (faim et coeurs fantomes), eux, n'utilisent PAS ce fichier : ce
sont les vrais sprites vanilla (hud/food_full, hud/heart/full...), charges
depuis le jar du jeu — c'est ce que fait AppleSkin lui-meme, et la norme du
projet.

Disposition (verifiee en decodant le PNG, voir SaturationModule)
---------------------------------------------------------------
  y  0..8   quatre cellules 9x9, SATURATION, dore  — x 0/9/18/27
             remplissage croissant : vide, quart, moitie, plein
  y  9..17  la meme serie en ROUGE (variante effet Faim / nourriture pourrie)
  y 18..26  barre d'EPUISEMENT, damier gris, 81x9, revelee par la DROITE
  y 27..31  petites icones (non utilisees ici)
