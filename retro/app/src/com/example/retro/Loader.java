package com.example.retro;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Gets a game ready off the UI thread: downloads the ROM from the NAS the first time (unzipping it),
// then loads it into the emulator. Reports back to GameActivity through its handler.
// Arcade sets ("raw") stay zipped under their exact set name, and the zips they need (parent set,
// BIOS) are downloaded next to them, because MAME looks for them by name in the same folder.
public class Loader implements Runnable {
    private final GameActivity game;
    private final String host, share, path;
    private final File dir;      // where downloaded ROMs are kept
    private final String key;    // file name for this game's ROM (its extension is added)
    private final boolean raw;
    private final String deps;

    Loader(GameActivity game, String host, String share, String path, File dir, String key, boolean raw, String deps) {
        this.game = game; this.host = host; this.share = share; this.path = path; this.dir = dir; this.key = key;
        this.raw = raw; this.deps = deps;
    }

    @Override
    public void run() {
        try {
            if (raw) {
                dir.mkdirs();
                if (deps != null) for (String dep : deps.split("\\|"))
                    if (!dep.isEmpty()) fetch(dep);
                game.status("Loading…");
                game.loaded(fetch(path));
                return;
            }
            File rom = find();
            if (rom == null) {
                game.status("Downloading…");
                Smb smb = new Smb(host, share);
                byte[] data;
                try { data = smb.read(path); } finally { smb.close(); }
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.toLowerCase().endsWith(".zip")) {
                    // ROMs are zipped on the share; keep the file inside, with its own extension
                    // (some cores tell systems apart by extension, e.g. .sms vs .md)
                    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null && e.isDirectory()) {}
                    if (e == null) throw new Exception("empty zip");
                    name = e.getName();
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    for (int n; (n = zip.read(buf)) > 0; ) out.write(buf, 0, n);
                    data = out.toByteArray();
                }
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : ".bin";
                dir.mkdirs();
                rom = new File(dir, key + ext);
                File tmp = new File(dir, key + ".part");
                FileOutputStream f = new FileOutputStream(tmp);
                f.write(data);
                f.close();
                if (!tmp.renameTo(rom)) throw new Exception("could not save the ROM");
            }
            game.status("Loading…");
            game.loaded(rom);
        } catch (Exception e) {
            game.failed("Couldn't get this game from the NAS (" + host + ").\n" + e.getMessage() + "\n\nPress Back.");
        }
    }

    /** Downloads a share file as is, keeping its name; returns the local copy */
    private File fetch(String sharePath) throws Exception {
        File f = new File(dir, sharePath.substring(sharePath.lastIndexOf('/') + 1));
        if (f.exists()) return f;
        game.status("Downloading…");
        File tmp = new File(f.getPath() + ".part");
        java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 65536);
        Smb smb = new Smb(host, share);
        try { smb.read(sharePath, out); } finally { smb.close(); out.close(); }
        if (!tmp.renameTo(f)) throw new Exception("could not save " + f.getName());
        return f;
    }

    private File find() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files)
            if (f.getName().startsWith(key + ".") && !f.getName().endsWith(".part")) return f;
        return null;
    }
}
