package com.yayin.player;

import java.util.HashMap;

/**
 * MPEG-TS "içindekiler tablosu" (PAT + PMT) bulucu.
 *
 * Bazı IPTV sunucuları kanal açılınca hızlıca bir tam kare (IDR) gönderir ama PAT/PMT bu kareden
 * sonra gelir. ExoPlayer PMT'yi görmeden gelen video paketlerini atar ve bir sonraki tam kareyi
 * bekler (2-4 sn). Bu sınıf, akışın başında okunan veride PAT ve PMT paketlerini bulur; oynatıcıya
 * önce bunlar verilince o ilk tam kare de kullanılır ve görüntü hemen gelir.
 */
public final class TsPsi {
    private TsPsi() {}

    public static final int PACKET = 188;

    /** Akıştaki ilk geçerli TS paket başlangıcı (art arda 3 senkron baytı); yoksa -1. */
    public static int syncOffset(byte[] b, int len) {
        int max = Math.min(len - 2 * PACKET - 1, PACKET * 4);
        for (int i = 0; i <= max; i++) {
            if (b[i] == 0x47 && b[i + PACKET] == 0x47 && b[i + 2 * PACKET] == 0x47) return i;
        }
        return -1;
    }

    /** Veride PAT ve ona ait PMT paketi varsa ikisini art arda (376 bayt) döndürür; yoksa null. */
    public static byte[] findPatPmt(byte[] b, int len) {
        int s = syncOffset(b, len);
        if (s < 0) return null;
        byte[] pat = null;
        int pmtPid = -1;
        HashMap<Integer, byte[]> pmts = new HashMap<>();   // PAT'tan önce gelen PMT adayları
        for (int p = s; p + PACKET <= len; p += PACKET) {
            if (b[p] != 0x47) {                              // senkron kaybı: yeniden bul
                int r = -1;
                for (int i = p; i + 2 * PACKET < len; i++) {
                    if (b[i] == 0x47 && b[i + PACKET] == 0x47 && b[i + 2 * PACKET] == 0x47) { r = i; break; }
                }
                if (r < 0) break;
                p = r;
            }
            boolean pusi = (b[p + 1] & 0x40) != 0;
            if (!pusi) continue;
            int pid = ((b[p + 1] & 0x1F) << 8) | (b[p + 2] & 0xFF);
            int afc = (b[p + 3] >> 4) & 0x03;
            if (afc == 0 || afc == 2) continue;              // yük yok
            int start = p + 4;
            if (afc == 3) start += 1 + (b[p + 4] & 0xFF);
            if (start >= p + PACKET) continue;
            int t = start + 1 + (b[start] & 0xFF);           // pointer_field
            if (t + 3 > p + PACKET) continue;
            int tableId = b[t] & 0xFF;
            int secLen = ((b[t + 1] & 0x0F) << 8) | (b[t + 2] & 0xFF);
            if (pid == 0 && tableId == 0x00 && pat == null) {
                int end = Math.min(t + 3 + secLen - 4, p + PACKET);   // CRC hariç
                for (int e = t + 8; e + 4 <= end; e += 4) {
                    int prog = ((b[e] & 0xFF) << 8) | (b[e + 1] & 0xFF);
                    if (prog != 0) { pmtPid = ((b[e + 2] & 0x1F) << 8) | (b[e + 3] & 0xFF); break; }
                }
                if (pmtPid < 0) continue;
                pat = copy(b, p);
                byte[] early = pmts.get(pmtPid);
                if (early != null) return join(pat, early);
            } else if (tableId == 0x02 && pid != 0) {
                byte[] pk = copy(b, p);
                if (pat != null && pid == pmtPid) return join(pat, pk);
                if (!pmts.containsKey(pid)) pmts.put(pid, pk);
            }
        }
        return null;
    }

    private static byte[] copy(byte[] b, int p) {
        byte[] o = new byte[PACKET];
        System.arraycopy(b, p, o, 0, PACKET);
        return o;
    }

    private static byte[] join(byte[] a, byte[] c) {
        byte[] o = new byte[a.length + c.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(c, 0, o, a.length, c.length);
        return o;
    }
}
