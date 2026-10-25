#!/usr/bin/env python3
"""Builds the game catalog from the ROM share: app/assets/games.js plus cover thumbnails in
app/assets/covers/<console>/. The ROMs themselves stay on the NAS; the app downloads each one
the first time it is played.

Usage: catalog.py ROMS_DIR NAS_HOST [SHARE]      (update.sh passes these from local.conf)
  ROMS_DIR: the ROM folder of the NAS share, mounted on this Mac (e.g. /Volumes/Public/ROMS)
  NAS_HOST: the NAS's IP address; the TV downloads from smb://NAS_HOST/SHARE/<ROMS_DIR's name>/...
  SHARE:    the SMB share name (default Public)
Needs curl and ImageMagick (magick). Box art comes from the libretro-thumbnails project; console
photos (app/assets/consoles/) from Wikimedia Commons, credited in app/assets/consoles/CREDITS.txt.
"""
import json, os, re, subprocess, sys, urllib.parse, zipfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

if len(sys.argv) < 3: sys.exit(__doc__)
ROMS, HOST = sys.argv[1], sys.argv[2]
SHARE = sys.argv[3] if len(sys.argv) > 3 else 'Public'
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'assets')
CORES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'third_party')
THUMB = '160x160'

# Share folder -> console shown in the app. "core" is the emulator library the game runs on,
# "photo" a Wikimedia Commons file (transparent PNG) shown in the console carousel.
CONSOLES = [
    {'id': 'nes', 'folder': 'Nintendo - NES', 'name': 'NES', 'color': '#c62828', 'core': 'fceumm',
     'thumbs': 'Nintendo - Nintendo Entertainment System',
     'photo': ('NES-Console-Set.png', 'Evan Amos, public domain')},
    {'id': 'snes', 'folder': 'Nintendo - SNES', 'name': 'Super Nintendo', 'color': '#6a1b9a', 'core': 'snes9x2005',
     'thumbs': 'Nintendo - Super Nintendo Entertainment System',
     'photo': ('SNES-Mod1-Console-Set.png', 'Evan Amos, CC BY-SA 3.0')},
    {'id': 'sms', 'folder': 'Sega - Master System', 'name': 'Master System', 'color': '#1565c0', 'core': 'genesis_plus_gx',
     'thumbs': 'Sega - Master System - Mark III',
     'photo': ('Sega-Master-System-Set.png', 'Evan Amos, public domain')},
    {'id': 'md', 'folder': 'Sega - Mega Drive', 'name': 'Mega Drive', 'color': '#212121', 'core': 'genesis_plus_gx',
     'thumbs': 'Sega - Mega Drive - Genesis',
     'photo': ('Sega-Mega-Drive-EU-Mk1-wController-FL.png', 'Evan Amos, public domain')},
    {'id': 'gb', 'folder': 'Nintendo - Game Boy', 'name': 'Game Boy', 'color': '#6b8e23', 'core': 'gambatte',
     'thumbs': 'Nintendo - Game Boy',
     'photo': ('Game-Boy-FL.png', 'Evan Amos, public domain')},
    # Arcade: MAME 0.37b5 sets (file names like kof98_c1.rom), run by the MAME 2000 core. Its game list
    # gives titles and each set's parent/BIOS; zips stay zipped ("raw") and their parent/BIOS zips are
    # downloaded with them. Snaps (screenshots) back up the sparse arcade box art.
    {'id': 'arcade', 'folder': 'Arcade - MAME Legacy', 'name': 'Arcade', 'color': '#ef6c00', 'core': 'mame2000',
     'thumbs': 'MAME', 'art': ('Named_Boxarts', 'Named_Snaps'),
     'dat': os.path.join(CORES, 'mame2000', 'metadata', 'MAME 0.37b5 XML.dat'),
     'photo': ('Borne arcade Pacman.png', 'Fan de Rétro, CC BY 4.0')},
]
EXTS = ('.zip', '.nes', '.sfc', '.smc', '.sms', '.md', '.gen', '.bin', '.gb', '.gbc')

def title(name):
    """'Adventures of Batman & Robin, The (USA)' -> 'The Adventures of Batman & Robin'"""
    # tags like (USA) [T-En by ...] all come after; arcade titles also list alternate names after " / "
    # (" _ " in thumbnail names, where "/" isn't allowed)
    t = re.split(r'\s[\(\[]|\s[/_]\s', name, maxsplit=1)[0].strip()
    m = re.match(r'^(.*), (The|A|An)(\b.*)$', t)
    return (m.group(2) + ' ' + m.group(1) + m.group(3)) if m else t

def norm(t):
    return re.sub(r'[^a-z0-9]', '', title(t).lower())

_index = {}
def index(system, kind='Named_Boxarts'):
    """Every box art name for a console, by normalized title, best region first.
    Used when a ROM's exact name has no art (fan translations, other regions)."""
    if (system, kind) not in _index:
        html = subprocess.run(['curl', '-sf', 'https://thumbnails.libretro.com/%s/%s/' % (urllib.parse.quote(system), kind)],
                              capture_output=True).stdout.decode('utf-8', 'replace')
        names = [urllib.parse.unquote(h)[:-4] for h in re.findall(r'href="([^"/]+\.png)"', html)]
        rank = lambda n: next((i for i, r in enumerate(['(USA)', '(World)', '(USA, Europe)', '(Europe)', '(Japan)']) if r in n), 9)
        by = {}
        for n in sorted(names, key=rank):
            by.setdefault(norm(n), n)
        _index[system, kind] = by
    return _index[system, kind]

def fetch(system, name, dst, kind='Named_Boxarts'):
    # libretro-thumbnails replaces these characters in file names
    safe = re.sub(r'[&*/:`<>?\\|"]', '_', name)
    url = 'https://thumbnails.libretro.com/%s/%s/%s.png' % (
        urllib.parse.quote(system), kind, urllib.parse.quote(safe))
    try:
        png = subprocess.run(['curl', '-sf', '--retry', '2', url], check=True, capture_output=True).stdout
        subprocess.run(['magick', '-', '-resize', THUMB + '^', '-gravity', 'center', '-extent', THUMB,
                        '-strip', '-quality', '60', 'webp:' + dst], input=png, check=True, capture_output=True)
        return True
    except subprocess.CalledProcessError:
        return False

def cover(job):
    system, name, dst, kinds = job
    if os.path.exists(dst): return True
    for kind in kinds:
        if fetch(system, name, dst, kind): return True
        alt = index(system, kind).get(norm(name))
        if alt and fetch(system, alt, dst, kind): return True
    return False

_dats = {}
def dat(path):
    """MAME game list: set name -> (description, romof, CRCs of the ROMs it needs, cloneof)"""
    if path not in _dats:
        sets = {}
        for g in ET.parse(path).getroot().iter('game'):
            crcs = {r.get('crc').lower() for r in g.iter('rom') if r.get('crc') and r.get('status') != 'nodump'}
            sets[g.get('name')] = (g.findtext('description') or g.get('name'), g.get('romof'), crcs, g.get('cloneof'))
        _dats[path] = sets
    return _dats[path]

def arcade(c, folder, files):
    """Arcade sets that can actually run: [(file, title, deps, name)], plus what was left out and why"""
    sets = dat(c['dat'])
    on_share = {os.path.splitext(f)[0]: f for f in files}
    # A BIOS (e.g. neogeo) is what games point to with romof without being their parent (cloneof)
    bios = {r for _, r, _, clone in sets.values() if r and not clone}
    crcs = {}
    def contents(name):
        if name not in crcs:
            try: crcs[name] = {'%08x' % i.CRC for i in zipfile.ZipFile(os.path.join(folder, on_share[name])).infolist()}
            except (zipfile.BadZipFile, OSError): crcs[name] = set()
        return crcs[name]
    ok, skipped = [], []
    for name, f in sorted(on_share.items()):
        if name not in sets: skipped.append((name, 'not in the MAME 0.37b5 list')); continue
        if name in bios: continue
        chain, x = [], sets[name][1]
        while x and x in sets and x not in chain:     # parent set, then its BIOS
            chain.append(x); x = sets[x][1]
        missing_deps = [d for d in chain if d not in on_share]
        have = set(contents(name))
        for d in chain:
            if d in on_share: have |= contents(d)
        if sets[name][2] - have:
            skipped.append((name, 'needs ' + ', '.join(d + '.zip' for d in missing_deps) if missing_deps
                            else 'incomplete or wrong-version zip'))
            continue
        ok.append((f, title(sets[name][0]), [on_share[d] for d in chain if d in on_share], name, sets[name][0]))
    return ok, skipped

def photo(c):
    """Console photo for the carousel: transparent edges trimmed, 640 px wide WebP with alpha"""
    dst = os.path.join(ASSETS, 'consoles', c['id'] + '.webp')
    if os.path.exists(dst): return True
    url = 'https://commons.wikimedia.org/wiki/Special:FilePath/%s?width=960' % urllib.parse.quote(c['photo'][0])
    try:
        png = subprocess.run(['curl', '-sfL', '-A', 'RetroGamesCatalog/1.0 (personal TV app)', url],
                             check=True, capture_output=True).stdout
        # Some photos come with a white background instead of transparency: clear it from the corners
        subprocess.run(['magick', '-', '-alpha', 'set', '-fuzz', '4%', '-fill', 'none',
                        '-draw', 'color 0,0 floodfill', '-rotate', '180', '-draw', 'color 0,0 floodfill', '-rotate', '180', '-trim', '+repage', '-resize', '640x360>', '-strip', '-quality', '80',
                        'webp:' + dst], input=png, check=True, capture_output=True)
        return True
    except subprocess.CalledProcessError:
        return False

consoles, games, jobs, skipped = [], [], [], []
for c in CONSOLES:
    folder = os.path.join(ROMS, c['folder'])
    if not os.path.isdir(folder): continue
    files = sorted(f for f in os.listdir(folder) if f.lower().endswith(EXTS) and not f.startswith('.'))
    if not files: continue
    os.makedirs(os.path.join(ASSETS, 'covers', c['id']), exist_ok=True)
    os.makedirs(os.path.join(ASSETS, 'consoles'), exist_ok=True)
    entry = dict({k: c[k] for k in ('id', 'name', 'color', 'core', 'folder')}, art=1 if photo(c) else 0)
    kinds = c.get('art', ('Named_Boxarts',))
    if 'dat' in c:
        entry['raw'] = 1
        playable, left_out = arcade(c, folder, files)
        skipped += [(c['name'], n, why) for n, why in left_out]
        for f, t, deps, name, desc in playable:
            games.append([c['id'], f, t, name, deps])
            jobs.append((c['thumbs'], desc, os.path.join(ASSETS, 'covers', c['id'], name + '.webp'), kinds))
    else:
        for f in files:
            name = os.path.splitext(f)[0]
            key = re.sub(r'[^A-Za-z0-9]+', '-', name).strip('-').lower()
            games.append([c['id'], f, title(name), key, None])
            jobs.append((c['thumbs'], name, os.path.join(ASSETS, 'covers', c['id'], key + '.webp'), kinds))
    consoles.append(entry)

sys.stderr.write('%d games in %d consoles, fetching covers...\n' % (len(games), len(consoles)))
for c in CONSOLES:                      # load art indexes before the threads start
    if any(x['id'] == c['id'] for x in consoles):
        for kind in c.get('art', ('Named_Boxarts',)): index(c['thumbs'], kind)
with ThreadPoolExecutor(6) as pool:
    ok = list(pool.map(cover, jobs))

with open(os.path.join(ASSETS, 'consoles', 'CREDITS.txt'), 'w') as out:
    for c in CONSOLES:
        out.write('%s.webp: https://commons.wikimedia.org/wiki/File:%s (%s)\n' % (c['id'], c['photo'][0], c['photo'][1]))

# Drop covers for games no longer on the share
keep = {j[2] for j in jobs}
for root, _, files in os.walk(os.path.join(ASSETS, 'covers')):
    for f in files:
        if os.path.join(root, f) not in keep: os.remove(os.path.join(root, f))

# Each game: [console, file on share, title, key (cover + save names), has cover, (arcade) zips it also needs]
games = [g[:4] + [1 if good else 0] + ([g[4]] if g[4] is not None else []) for g, good in zip(games, ok)]
games.sort(key=lambda g: ([c['id'] for c in consoles].index(g[0]), g[2].lower()))
with open(os.path.join(ASSETS, 'games.js'), 'w') as out:
    out.write('var SOURCE=' + json.dumps({'host': HOST, 'share': SHARE, 'dir': os.path.basename(ROMS.rstrip('/'))}) + ';\n')
    out.write('var CONSOLES=' + json.dumps(consoles, ensure_ascii=False) + ';\n')
    out.write('var GAMES=' + json.dumps(games, ensure_ascii=False, separators=(',', ':')) + ';\n')
if skipped:
    sys.stderr.write('left out %d games that cannot run:\n' % len(skipped))
    for con, n, why in skipped: sys.stderr.write('  %s: %s (%s)\n' % (con, n, why))
missing = [g[2] + ' (' + g[0] + ')' for g in games if not g[4]]
sys.stderr.write('wrote %d games, %d without cover%s\n' % (len(games), len(missing),
                 (': ' + ', '.join(missing[:15]) + ('...' if len(missing) > 15 else '')) if missing else ''))
