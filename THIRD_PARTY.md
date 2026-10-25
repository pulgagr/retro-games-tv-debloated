# Third-party code and content

The MIT license in [LICENSE](LICENSE) covers this project's own code only. The emulator cores are
git submodules, not part of that license, and each keeps its own terms:

| Core | Used for | License | What it means for a built APK |
|---|---|---|---|
| [libretro-fceumm](https://github.com/libretro/libretro-fceumm) | NES | GPL-2.0 | Share its source with the build (the submodule link does this) |
| [snes9x2005](https://github.com/libretro/snes9x2005) | SNES | Snes9x license: **non-commercial only** | Free to share, never sell |
| [Genesis Plus GX](https://github.com/libretro/Genesis-Plus-GX) | Master System, Mega Drive | Genesis Plus GX license: **non-commercial only** | Free to share, never sell |
| [gambatte-libretro](https://github.com/libretro/gambatte-libretro) | Game Boy | GPL-2.0 | Share its source with the build |
| [mame2000-libretro](https://github.com/libretro/mame2000-libretro) | Arcade | MAME license: **non-commercial only** | Free to share, never sell |

`retro/app/jni/libretro.h` is the libretro API header, MIT licensed (notice inside the file).

**So:** sharing a built APK for free, with a link to this repository, is fine. Selling it, or putting
it in a paid product, isn't.

## Content fetched when you build

`retro/tools/catalog.py` downloads artwork for your own build. None of it is stored in this repository:

- **Box art** from the [libretro-thumbnails](https://github.com/libretro-thumbnails) project
  (`thumbnails.libretro.com`). The artwork belongs to the games' publishers.
- **Console photos** from Wikimedia Commons, credited in `retro/app/assets/consoles/CREDITS.txt`
  after a build. Most are public domain photos by Evan Amos. The SNES photo is CC BY-SA 3.0
  (Evan Amos), and the arcade cabinet photo is CC BY 4.0 (Fan de Rétro).

## Games

No ROMs are included, and none should ever be committed. Use games you're entitled to use, for
example dumps of cartridges you own. `nes/tools/get_roms.sh` downloads a set of freely licensed
homebrew NES games for testing, with their licenses recorded in `nes/app/assets/CREDITS.txt`.
