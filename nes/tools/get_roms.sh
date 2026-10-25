#!/bin/sh
# Downloads the freely licensed homebrew test games into app/assets/roms/ and writes CREDITS.txt.
set -e
cd "$(dirname "$0")/../app/assets/roms"
get() {  # file name, license, source repo, download url
  curl -sfL -o "$1" "$4"
  printf '%s\n  License: %s\n  Source: https://github.com/%s\n\n' "$1" "$2" "$3" >> ../CREDITS.txt
}
rm -f ../CREDITS.txt   # only our own files get replaced; other ROMs in the folder stay
get Nova_the_Squirrel.nes GPL-3.0 NovaSquirrel/NovaTheSquirrel https://github.com/NovaSquirrel/NovaTheSquirrel/releases/download/v1.0.6a/nova.nes
get Thwaite.nes GPL-3.0 pinobatch/thwaite-nes https://github.com/pinobatch/thwaite-nes/releases/download/v0.04/thwaite.nes
get Concentration_Room.nes GPL-3.0 pinobatch/croom-nes https://github.com/pinobatch/croom-nes/releases/download/v0.02a/croom.nes
get robotfindskitten.nes Zlib pinobatch/rfk-nes https://github.com/pinobatch/rfk-nes/releases/download/v0.10/robotfindskitten.nes
get Coco_Battle_Royale_2.nes GPL-3.0 psywave-games/coco-battle-royale-2 https://github.com/psywave-games/coco-battle-royale-2/releases/download/0.4.0/cocobattleroyale221106.nes
get RoboRun.nes GPL-3.0 heaven-hm/roborun-nes https://github.com/heaven-hm/roborun-nes/releases/download/1.1/RoboRun-NES_1.1.nes
get Table_Tennis.nes MIT mike42/8bit-table-tennis https://github.com/mike42/8bit-table-tennis/releases/download/v1.0/table_tennis_v1.0.nes
get Stallar.nes MIT wendelscardua/stallar https://github.com/wendelscardua/stallar/releases/download/0.1.0/stallar.nes
get File_Fixers.nes MIT wendelscardua/file-fixers https://github.com/wendelscardua/file-fixers/releases/download/0.1.0/file-fixers.nes
get zz_Controller_Test.nes Zlib pinobatch/allpads-nes https://github.com/pinobatch/allpads-nes/releases/download/v0.09/allpads.nes
ls -l
