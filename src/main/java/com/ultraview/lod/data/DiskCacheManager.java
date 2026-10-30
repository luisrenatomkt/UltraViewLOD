package com.ultraview.lod.data;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** Cache em disco por mundo/dimensao, com CRC32, versao de formato e escrita atomica. */
public final class DiskCacheManager {
    private static final int MAGIC = 0x55564C31;
    public static final int FORMAT_VERSION = 1;
    private final File baseDir;

    public DiskCacheManager(File baseDir) { this.baseDir = baseDir; }

    public static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        if (s.length() > 60) s = s.substring(0, 60);
        return s + "_" + Integer.toHexString(raw.hashCode());
    }

    public File worldDir(String worldId) { return new File(baseDir, sanitize(worldId)); }
    public File dimDir(String worldId, int dim) { return new File(worldDir(worldId), "dim" + dim); }
    private File file(String w, int dim, int rx, int rz) { return new File(dimDir(w, dim), "r." + rx + "." + rz + ".uvl"); }

    public boolean save(String w, int dim, Region r) {
        File dir = dimDir(w, dim);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File f = file(w, dim, r.rx, r.rz);
        File tmp = new File(dir, f.getName() + ".tmp");
        DataOutputStream dos = null;
        try {
            CRC32 crc = new CRC32();
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            OutputStream out = new CheckedOutputStream(new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)), def), crc);
            dos = new DataOutputStream(new BufferedOutputStream(out));
            dos.writeInt(MAGIC);
            dos.writeInt(FORMAT_VERSION);
            dos.writeInt(r.rx);
            dos.writeInt(r.rz);
            dos.writeLong(r.lastUpdateMillis == 0 ? System.currentTimeMillis() : r.lastUpdateMillis);
            for (short s : r.heights) dos.writeShort(s);
            for (int c : r.colors) dos.writeInt(c);
            dos.write(r.flags);
            dos.flush();
            long v = crc.getValue();
            dos.writeLong(v);
            dos.close();
            dos = null;
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            if (dos != null) try { dos.close(); } catch (IOException ignored) { }
            tmp.delete();
            return false;
        }
    }

    /** Retorna null se ausente, corrompido, de outra versao ou antigo demais (arquivos invalidos sao descartados). */
    public Region load(String w, int dim, int rx, int rz, long maxAgeMillis) {
        File f = file(w, dim, rx, rz);
        if (!f.isFile()) return null;
        DataInputStream dis = null;
        try {
            CRC32 crc = new CRC32();
            dis = new DataInputStream(new CheckedInputStream(new InflaterInputStream(new BufferedInputStream(new FileInputStream(f))), crc));
            if (dis.readInt() != MAGIC) throw new IOException("magic");
            if (dis.readInt() != FORMAT_VERSION) throw new IOException("versao");
            if (dis.readInt() != rx || dis.readInt() != rz) throw new IOException("coordenadas");
            long saved = dis.readLong();
            Region r = new Region(rx, rz);
            for (int i = 0; i < r.heights.length; i++) r.heights[i] = dis.readShort();
            for (int i = 0; i < r.colors.length; i++) r.colors[i] = dis.readInt();
            dis.readFully(r.flags);
            long actual = crc.getValue();
            long stored = dis.readLong();
            dis.close();
            dis = null;
            if (actual != stored) throw new IOException("crc");
            if (maxAgeMillis > 0 && System.currentTimeMillis() - saved > maxAgeMillis) {
                f.delete();
                return null;
            }
            r.lastUpdateMillis = saved;
            r.recomputeHasData();
            r.dirtyDisk = false;
            return r;
        } catch (IOException | RuntimeException e) {
            if (dis != null) try { dis.close(); } catch (IOException ignored) { }
            f.delete();
            return null;
        }
    }

    private List<File> listFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] arr = dir.listFiles();
        if (arr == null) return out;
        for (File f : arr) {
            if (f.isDirectory()) out.addAll(listFiles(f));
            else if (f.getName().endsWith(".uvl")) out.add(f);
        }
        return out;
    }

    public long sizeBytes(String w) {
        long s = 0;
        for (File f : listFiles(worldDir(w))) s += f.length();
        return s;
    }

    /** Remove os arquivos mais antigos DESTE mundo ate caber no limite. Nunca toca em outros mundos. */
    public long enforceLimit(String w, long maxBytes) {
        List<File> files = listFiles(worldDir(w));
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return 0;
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        long freed = 0;
        for (File f : files) {
            if (total - freed <= maxBytes) break;
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }

    /** Apaga somente o cache do mundo informado. Retorna a quantidade de arquivos removidos. */
    public int clearWorld(String w) {
        File dir = worldDir(w);
        try {
            String base = baseDir.getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(base)) return 0;
        } catch (IOException e) { return 0; }
        int n = 0;
        for (File f : listFiles(dir)) if (f.delete()) n++;
        return n;
    }
}
