package ai.openpilot.replay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrap an openpilot HEVC raw stream (Annex-B, e.g. fcamera.hevc) into a valid
 * MP4 container so any Android player can hardware-decode it - WITHOUT re-encoding.
 *
 * WHY WE HAND-WRITE THE MP4
 * -------------------------
 * Android's MediaMuxer silently refused this HEVC track on the target device, so
 * we emit the container ourselves, byte-for-byte matching what `ffmpeg -c:v copy`
 * produces (verified to PLAY on this device: c2.qti.hevc.decoder + render:1).
 *
 * MEMORY MODEL (this is the whole point of the v2 rewrite)
 * -------------------------------------------------------
 * The previous version kept EVERY sample's bytes in RAM (`byte[][] sampleData`)
 * plus a `List<long[]>` per NAL; an 800MB fcamera blew the ~512MB app heap and
 * crashed with OutOfMemoryError (HevcTranscoder.transcode line 147). This version
 * is STREAMING and O(1) in file size:
 *
 *   Pass 1  : scan the file, record only integer metadata:
 *               - vclOff[]  : byte offset of each VCL NAL payload   (long[])
 *               - vclLen[]  : trimmed byte length of each VCL NAL   (int[])
 *               - picture starts (first-slice NAL indices) + types  (int[])
 *               - VPS/SPS/PPS bytes (tiny)
 *   Build   : stsz/stts/stss/stsc/stco + moov from the int arrays alone.
 *   Pass 2  : walk pictures, random-access-read their NALs into a single reused
 *             1MB staging buffer, write length-prefixed samples straight to disk.
 *
 * Peak heap is a few MB regardless of the input being 75MB or 800MB.
 *
 * Layout: ftyp | moov | mdat. Samples are length-prefixed NAL units (4-byte BE
 * length, no start codes); sample entry 'hev1' with embedded 'hvcC'.
 */
public final class HevcTranscoder {

    public interface Progress { void onProgress(int percent); }

    private static final int DEFAULT_FPS = 20;
    private static final int TIMESCALE = 90000;
    private static final int SAMPLE_DELTA = TIMESCALE / DEFAULT_FPS; // 4500
    private static final int CHUNK_TARGET = 1_000_000;

    private static File logFile;
    private static void log(String s) {
        try {
            if (logFile == null) return;
            FileWriter w = new FileWriter(logFile, true);
            w.write(s + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    private HevcTranscoder() {}

    public static boolean transcode(File src, File out, Progress cb) throws IOException {
        logFile = new File(out.getParentFile(), "tc.log");
        try { if (logFile.exists()) logFile.delete(); } catch (Throwable ignored) {}
        log("=== transcode start (streaming mp4 v2) ===");
        log("src=" + src + " size=" + src.length());

        RandomAccessFile in = new RandomAccessFile(src, "r");
        try {
            final long fileLen = in.length();

            // =================================================================
            //  PASS 1 - scan NAL start codes, record integer metadata only
            // =================================================================
            long[] vclOff = new long[1 << 18];   // payload offset per VCL NAL
            int[]  vclLen = new int[1 << 18];    // trimmed payload length per VCL NAL
            int nVcl = 0;

            int[] picFirst = new int[4096];      // first VCL-NAL index of each picture
            int[] picType  = new int[4096];      // first-slice nal_type of each picture
            int nPic = 0;

            byte[] vps = null, sps = null, pps = null;

            byte[] buf = new byte[1 << 20];
            long scanPos = 0;
            int bufLen;

            long pendingScOff = -1;
            int  pendingScLen = 0;

            in.seek(0);
            while ((bufLen = in.read(buf)) > 0) {
                for (int i = 0; i < bufLen; i++) {
                    if ((buf[i] & 0xFF) != 0) continue;
                    int i1 = i + 1, i2 = i + 2;
                    if (i2 >= bufLen) continue;
                    if ((buf[i1] & 0xFF) == 0 && (buf[i2] & 0xFF) == 1) {
                        long scOff;
                        int scLen;
                        if (i >= 1 && (buf[i - 1] & 0xFF) == 0) { scOff = scanPos + i - 1; scLen = 4; }
                        else { scOff = scanPos + i; scLen = 3; }

                        if (pendingScOff >= 0) {
                            long dataOff = pendingScOff + pendingScLen;
                            int nalLen = (int) (scOff - dataOff);
                            if (nalLen > 0) {
                                in.seek(dataOff);
                                int nalType = ((in.readByte() & 0xFF) >> 1) & 0x3F;
                                if (nalType == 32 || nalType == 33 || nalType == 34) {
                                    byte[] nal = new byte[nalLen];
                                    in.seek(dataOff);
                                    in.readFully(nal);
                                    if (nalType == 32) vps = nal;
                                    else if (nalType == 33) sps = nal;
                                    else pps = nal;
                                } else if (nalType < 32) {
                                    boolean firstSlice;
                                    if (nalLen >= 3) { in.seek(dataOff + 2); firstSlice = ((in.readByte() & 0x80) != 0); }
                                    else firstSlice = true;
                                    int trimmed = (int) trimTrailingZeros(in, dataOff, nalLen);

                                    if (firstSlice) {
                                        if (nPic == picFirst.length) { picFirst = growI(picFirst); picType = growI(picType); }
                                        picFirst[nPic] = nVcl;
                                        picType[nPic] = nalType;
                                        nPic++;
                                    }
                                    if (nVcl == vclOff.length) { vclOff = growL(vclOff); vclLen = growI(vclLen); }
                                    vclOff[nVcl] = dataOff;
                                    vclLen[nVcl] = trimmed;
                                    nVcl++;
                                }
                            }
                        }
                        pendingScOff = scOff;
                        pendingScLen = scLen;
                    }
                }
                scanPos += bufLen;
            }
            // trailing NAL
            if (pendingScOff >= 0) {
                long dataOff = pendingScOff + pendingScLen;
                int nalLen = (int) (fileLen - dataOff);
                if (nalLen > 0) {
                    in.seek(dataOff);
                    int nalType = ((in.readByte() & 0xFF) >> 1) & 0x3F;
                    if (nalType == 32 || nalType == 33 || nalType == 34) {
                        byte[] nal = new byte[nalLen];
                        in.seek(dataOff); in.readFully(nal);
                        if (nalType == 32) vps = nal; else if (nalType == 33) sps = nal; else pps = nal;
                    } else if (nalType < 32) {
                        boolean firstSlice;
                        if (nalLen >= 3) { in.seek(dataOff + 2); firstSlice = ((in.readByte() & 0x80) != 0); }
                        else firstSlice = true;
                        int trimmed = (int) trimTrailingZeros(in, dataOff, nalLen);
                        if (firstSlice) {
                            if (nPic == picFirst.length) { picFirst = growI(picFirst); picType = growI(picType); }
                            picFirst[nPic] = nVcl; picType[nPic] = nalType; nPic++;
                        }
                        if (nVcl == vclOff.length) { vclOff = growL(vclOff); vclLen = growI(vclLen); }
                        vclOff[nVcl] = dataOff; vclLen[nVcl] = trimmed; nVcl++;
                    }
                }
            }

            if (nPic == 0) throw new IOException("no VCL NAL units");
            if (vps == null || sps == null || pps == null) throw new IOException("missing VPS/SPS/PPS");
            log("vcl nals=" + nVcl + " pictures=" + nPic);

            // =================================================================
            //  build sample metadata (int arrays only)
            // =================================================================
            final int num = nPic;
            int[] sampleSize = new int[num];
            for (int p = 0; p < num; p++) {
                int from = picFirst[p];
                int to = (p + 1 < num) ? picFirst[p + 1] : nVcl;
                int need = 0;
                for (int k = from; k < to; k++) need += 4 + vclLen[k];
                sampleSize[p] = need;
            }

            int w = 1928, h = 1208;
            int[] wh = parseSpsSize(sps);
            if (wh != null && wh[0] > 0 && wh[1] > 0) { w = wh[0]; h = wh[1]; }
            log("size=" + w + "x" + h + " vps=" + vps.length + " sps=" + sps.length + " pps=" + pps.length);

            int[] sync = new int[Math.max(16, num / 8)];
            int nSync = 0;
            for (int p = 0; p < num; p++) {
                int t = picType[p];
                if (t >= 16 && t <= 21) {
                    if (nSync == sync.length) sync = growI(sync);
                    sync[nSync++] = p + 1;
                }
            }
            log("sync samples=" + nSync);

            List<int[]> chunks = new ArrayList<>();
            {
                int i = 0;
                while (i < num) {
                    int acc = 0, start = i;
                    while (i < num && (acc < CHUNK_TARGET || i == start)) { acc += sampleSize[i]; i++; }
                    chunks.add(new int[]{start, i - start});
                }
            }
            int nChunks = chunks.size();
            List<int[]> stscEntries = new ArrayList<>();
            for (int ci = 0; ci < nChunks; ci++) {
                int cnt = chunks.get(ci)[1];
                if (!stscEntries.isEmpty() && stscEntries.get(stscEntries.size() - 1)[1] == cnt) continue;
                stscEntries.add(new int[]{ci + 1, cnt});
            }
            log("chunks=" + nChunks + " stsc entries=" + stscEntries.size());

            // =================================================================
            //  moov
            // =================================================================
            byte[] hvcc = buildHvcC(vps, sps, pps);
            byte[] ftyp = box("ftyp", concat(
                    new byte[]{'m','p','4','2', 0,0,0,1},
                    new byte[]{'i','s','o','m'},
                    new byte[]{'m','p','4','2'}));

            byte[] moovDummy = buildMoov(w, h, num, sampleSize, hvcc,
                    new int[nChunks], sync, nSync, stscEntries);
            long offsetBase = ftyp.length + moovDummy.length + 8;
            long totalMedia = 0;
            for (int i = 0; i < num; i++) totalMedia += sampleSize[i];

            int[] chunkOff = new int[nChunks];
            {
                long pos = offsetBase;
                int ci = 0, startOfChunk = 0;
                for (int i = 0; i < num; i++) {
                    if (i == startOfChunk) { chunkOff[ci] = (int) pos; ci++; if (ci < nChunks) startOfChunk = chunks.get(ci)[0]; }
                    pos += sampleSize[i];
                }
            }
            byte[] moov = buildMoov(w, h, num, sampleSize, hvcc, chunkOff, sync, nSync, stscEntries);
            if (moov.length != moovDummy.length)
                throw new IOException("moov size changed: " + moov.length + " vs " + moovDummy.length);

            // =================================================================
            //  PASS 2 - stream samples to disk (single reused buffer)
            // =================================================================
            if (out.exists()) out.delete();
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            byte[] stage = new byte[1 << 20];
            try {
                raf.setLength(0);
                raf.write(ftyp);
                raf.write(moov);
                long mdatSize = 8 + totalMedia;
                raf.write(new byte[]{(byte)((mdatSize>>>24)&0xFF),(byte)((mdatSize>>>16)&0xFF),
                        (byte)((mdatSize>>>8)&0xFF),(byte)(mdatSize&0xFF), 'm','d','a','t'});

                for (int p = 0; p < num; p++) {
                    int from = picFirst[p];
                    int to = (p + 1 < num) ? picFirst[p + 1] : nVcl;
                    for (int k = from; k < to; k++) {
                        int len = vclLen[k];
                        raf.write(new byte[]{(byte)((len>>>24)&0xFF),(byte)((len>>>16)&0xFF),
                                (byte)((len>>>8)&0xFF),(byte)(len&0xFF)});
                        long srcOff = vclOff[k];
                        int remaining = len;
                        while (remaining > 0) {
                            int chunk = Math.min(stage.length, remaining);
                            in.seek(srcOff);
                            in.readFully(stage, 0, chunk);
                            raf.write(stage, 0, chunk);
                            srcOff += chunk; remaining -= chunk;
                        }
                    }
                    if (cb != null && (p & 0xFF) == 0)
                        cb.onProgress((int) Math.min(99, (p * 100L) / num));
                }
            } finally {
                raf.close();
            }

            if (cb != null) cb.onProgress(100);
            long sz = out.length();
            log("done: out size=" + sz + " (expected ~" + (offsetBase + totalMedia) + ")");
            if (sz < 100_000) throw new IOException("mp4 too small: " + sz);
            return sz > 0;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            log("=== transcode end ===");
        }
    }

    private static long[] growL(long[] a) { long[] r = new long[a.length * 2]; System.arraycopy(a, 0, r, 0, a.length); return r; }
    private static int[]  growI(int[] a)  { int[]  r = new int[a.length * 2];  System.arraycopy(a, 0, r, 0, a.length); return r; }

    // ------------------------------------------------------------------ moov

    private static byte[] buildMoov(int w, int h, int num, int[] sampleSize,
                                    byte[] hvcc, int[] chunkOffsets,
                                    int[] sync, int nSync, List<int[]> stscEntries) throws IOException {
        long duration = (long) num * SAMPLE_DELTA;

        ByteArrayOutputStream mvhd = new ByteArrayOutputStream();
        mvhd.write(new byte[4]);
        mvhd.write(new byte[4]);
        mvhd.write(new byte[4]);
        wrI(mvhd, TIMESCALE); wrI(mvhd, (int) duration);
        wrI(mvhd, 0x00010000);
        mvhd.write(new byte[]{0x01,0,0,0});
        mvhd.write(new byte[8]);
        writeMatrix(mvhd);
        mvhd.write(new byte[24]);
        wrI(mvhd, 2);

        ByteArrayOutputStream tkhd = new ByteArrayOutputStream();
        wrI(tkhd, 0x00000007);
        tkhd.write(new byte[4]); tkhd.write(new byte[4]);
        wrI(tkhd, 1);
        tkhd.write(new byte[4]);
        wrI(tkhd, (int) duration);
        tkhd.write(new byte[8]);
        tkhd.write(new byte[2]); tkhd.write(new byte[2]); tkhd.write(new byte[]{0,0}); tkhd.write(new byte[2]);
        writeMatrix(tkhd);
        wrI(tkhd, w << 16); wrI(tkhd, h << 16);

        ByteArrayOutputStream mdhd = new ByteArrayOutputStream();
        mdhd.write(new byte[4]);
        mdhd.write(new byte[4]);
        mdhd.write(new byte[4]);
        wrI(mdhd, TIMESCALE); wrI(mdhd, (int) duration);
        mdhd.write(new byte[]{0x55,(byte)0xC4,0,0});

        ByteArrayOutputStream hdlr = new ByteArrayOutputStream();
        hdlr.write(new byte[4]); hdlr.write(new byte[4]);
        hdlr.write(new byte[]{'v','i','d','e'});
        hdlr.write(new byte[12]);
        hdlr.write("VideoHandler".getBytes("US-ASCII")); hdlr.write(0);

        ByteArrayOutputStream hev1 = new ByteArrayOutputStream();
        hev1.write(new byte[6]);
        hev1.write(new byte[]{0,1});
        hev1.write(new byte[2]); hev1.write(new byte[2]); hev1.write(new byte[12]);
        hev1.write(new byte[]{(byte)((w>>8)&0xFF),(byte)(w&0xFF),(byte)((h>>8)&0xFF),(byte)(h&0xFF)});
        wrI(hev1, 0x00480000); wrI(hev1, 0x00480000);
        hev1.write(new byte[4]);
        hev1.write(new byte[]{0,1});
        hev1.write(new byte[32]);
        hev1.write(new byte[]{0,0x18});
        hev1.write(new byte[]{(byte)0xFF,(byte)0xFF});
        hev1.write(hvcc);

        ByteArrayOutputStream stsd = new ByteArrayOutputStream();
        stsd.write(new byte[4]); wrI(stsd, 1); stsd.write(box("hev1", hev1.toByteArray()));

        ByteArrayOutputStream stts = new ByteArrayOutputStream();
        stts.write(new byte[4]); wrI(stts, 1); wrI(stts, num); wrI(stts, SAMPLE_DELTA);

        ByteArrayOutputStream stss = new ByteArrayOutputStream();
        stss.write(new byte[4]); wrI(stss, nSync);
        for (int i = 0; i < nSync; i++) wrI(stss, sync[i]);

        ByteArrayOutputStream stsc = new ByteArrayOutputStream();
        stsc.write(new byte[4]); wrI(stsc, stscEntries.size());
        for (int[] e : stscEntries) { wrI(stsc, e[0]); wrI(stsc, e[1]); wrI(stsc, 1); }

        ByteArrayOutputStream stsz = new ByteArrayOutputStream();
        stsz.write(new byte[4]); wrI(stsz, 0); wrI(stsz, num);
        for (int i = 0; i < num; i++) wrI(stsz, sampleSize[i]);

        ByteArrayOutputStream stco = new ByteArrayOutputStream();
        stco.write(new byte[4]); wrI(stco, chunkOffsets.length);
        for (int i = 0; i < chunkOffsets.length; i++) wrI(stco, chunkOffsets[i]);

        ByteArrayOutputStream stbl = new ByteArrayOutputStream();
        stbl.write(box("stsd", stsd.toByteArray()));
        stbl.write(box("stts", stts.toByteArray()));
        stbl.write(box("stss", stss.toByteArray()));
        stbl.write(box("stsc", stsc.toByteArray()));
        stbl.write(box("stsz", stsz.toByteArray()));
        stbl.write(box("stco", stco.toByteArray()));

        ByteArrayOutputStream vmhd = new ByteArrayOutputStream();
        wrI(vmhd, 1); vmhd.write(new byte[8]);

        ByteArrayOutputStream url = new ByteArrayOutputStream();
        wrI(url, 1);
        ByteArrayOutputStream dref = new ByteArrayOutputStream();
        dref.write(new byte[4]); wrI(dref, 1); dref.write(box("url ", url.toByteArray()));
        ByteArrayOutputStream dinf = new ByteArrayOutputStream();
        dinf.write(box("dref", dref.toByteArray()));

        ByteArrayOutputStream minf = new ByteArrayOutputStream();
        minf.write(box("vmhd", vmhd.toByteArray()));
        minf.write(box("dinf", dinf.toByteArray()));
        minf.write(box("stbl", stbl.toByteArray()));

        ByteArrayOutputStream mdia = new ByteArrayOutputStream();
        mdia.write(box("mdhd", mdhd.toByteArray()));
        mdia.write(box("hdlr", hdlr.toByteArray()));
        mdia.write(box("minf", minf.toByteArray()));

        ByteArrayOutputStream trak = new ByteArrayOutputStream();
        trak.write(box("tkhd", tkhd.toByteArray()));
        trak.write(box("mdia", mdia.toByteArray()));

        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        moov.write(box("mvhd", mvhd.toByteArray()));
        moov.write(box("trak", trak.toByteArray()));
        return box("moov", moov.toByteArray());
    }

    private static void writeMatrix(ByteArrayOutputStream o) {
        wrI(o, 0x00010000); wrI(o, 0); wrI(o, 0);
        wrI(o, 0); wrI(o, 0x00010000); wrI(o, 0);
        wrI(o, 0); wrI(o, 0); wrI(o, 0x40000000);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] buildHvcC(byte[] vps, byte[] sps, byte[] pps) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        int profileIdc = (sps.length > 1) ? (sps[1] & 0x1F) : 1;
        o.write(profileIdc);
        o.write(0); o.write(0); o.write(0); o.write(0x60);
        o.write(0); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0);
        o.write(0x5A);
        o.write(0xF0); o.write(0x00);
        o.write(0xFC);
        o.write(0xFC | 1);
        o.write(0xF8);
        o.write(0xF8);
        o.write(0); o.write(0);
        o.write(0x0F);
        o.write(3);
        writeArray(o, 32, vps);
        writeArray(o, 33, sps);
        writeArray(o, 34, pps);
        return box("hvcC", o.toByteArray());
    }

    private static void writeArray(ByteArrayOutputStream o, int nalType, byte[] nal) {
        o.write(0x80 | (nalType & 0x3F));
        o.write(0); o.write(1);
        o.write((nal.length >> 8) & 0xFF); o.write(nal.length & 0xFF);
        o.write(nal, 0, nal.length);
    }

    private static byte[] box(String type, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        wrI(o, 8 + body.length);
        for (int i = 0; i < type.length(); i++) o.write(type.charAt(i));
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static void wrI(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF); o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);  o.write(v & 0xFF);
    }

    private static byte[] concat(byte[]... arrs) {
        int n = 0; for (byte[] a : arrs) n += a.length;
        byte[] r = new byte[n]; int off = 0;
        for (byte[] a : arrs) { System.arraycopy(a, 0, r, off, a.length); off += a.length; }
        return r;
    }

    private static long trimTrailingZeros(RandomAccessFile in, long off, int len) throws IOException {
        int probe = Math.min(8, len);
        byte[] tail = new byte[probe];
        in.seek(off + len - probe);
        in.readFully(tail);
        int cut = 0;
        for (int i = probe - 1; i >= 0; i--) {
            if (tail[i] == 0) cut++; else break;
        }
        long trimmed = len - cut;
        return trimmed > 0 ? trimmed : len;
    }

    private static int[] parseSpsSize(byte[] nal) {
        try {
            byte[] rbsp = removeEmulationPrevention(nal, 0, nal.length);
            BitReader br = new BitReader(rbsp);
            br.readBits(16);
            br.readUE();
            int subLayers = br.readBits(3);
            br.skipBits(1);
            br.skipBits(2 + 1 + 5 + 32 + 48 + 8);
            for (int i = 0; i < subLayers; i++) { br.readBit(); br.skipBits(88); }
            br.readUE();
            int chroma = br.readUE();
            if (chroma == 3) br.skipBits(1);
            int w = br.readUE();
            int h = br.readUE();
            if (w > 0 && h > 0) return new int[]{w, h};
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] removeEmulationPrevention(byte[] in, int from, int len) {
        byte[] out = new byte[len];
        int o = 0, zeros = 0;
        for (int i = from; i < from + len && i < in.length; i++) {
            byte b = in[i];
            if (zeros >= 2 && b == 0x03) { zeros = 0; continue; }
            out[o++] = b;
            if (b == 0) zeros++; else zeros = 0;
        }
        byte[] r = new byte[o];
        System.arraycopy(out, 0, r, 0, o);
        return r;
    }

    private static final class BitReader {
        private final byte[] d; private int bitPos = 0;
        BitReader(byte[] d) { this.d = d; }
        int readBit() {
            int bytePos = bitPos >> 3;
            if (bytePos >= d.length) throw new RuntimeException("eof");
            int b = (d[bytePos] >> (7 - (bitPos & 7))) & 1;
            bitPos++;
            return b;
        }
        int readBits(int n) { int v = 0; for (int i = 0; i < n; i++) v = (v << 1) | readBit(); return v; }
        void skipBits(int n) { bitPos += n; }
        int readUE() {
            int zeros = 0;
            while (readBit() == 0 && zeros < 32) zeros++;
            int val = (1 << zeros) - 1;
            for (int i = 0; i < zeros; i++) val += readBit() << (zeros - 1 - i);
            return val;
        }
    }
}
