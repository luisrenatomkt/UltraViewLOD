package com.ultraview.lod.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cache LRU (ordem de acesso) das representacoes distantes prontas, com contabilidade de memoria estimada. */
public final class DistantRepresentationCache {
    public interface Handle {
        long bytes();
        void release();
        long lastUse();
    }

    private final LinkedHashMap<Long, Handle> map = new LinkedHashMap<Long, Handle>(256, 0.75f, true);
    private long bytes;

    public Handle get(long key) { return map.get(key); }

    public void put(long key, Handle h) {
        Handle old = map.put(key, h);
        if (old != null) { bytes -= old.bytes(); old.release(); }
        bytes += h.bytes();
    }

    public void remove(long key) {
        Handle old = map.remove(key);
        if (old != null) { bytes -= old.bytes(); old.release(); }
    }

    public void clear() {
        for (Handle h : map.values()) h.release();
        map.clear();
        bytes = 0;
    }

    public long bytes() { return bytes; }
    public int size() { return map.size(); }

    /** Remove os menos usados (que nao foram usados desde minLastUse) ate bytes <= limit. Retorna quantos removeu. */
    public int evictTo(long limit, long minLastUse) {
        int n = 0;
        Iterator<Map.Entry<Long, Handle>> it = map.entrySet().iterator();
        while (it.hasNext() && bytes > limit) {
            Handle h = it.next().getValue();
            if (h.lastUse() >= minLastUse) break; // restantes sao ainda mais recentes
            bytes -= h.bytes();
            it.remove();
            h.release();
            n++;
        }
        return n;
    }
}
