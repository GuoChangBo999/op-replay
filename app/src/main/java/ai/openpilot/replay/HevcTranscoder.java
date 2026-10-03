package ai.openpilot.replay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrap an openpilot HEVC raw stream (Annex-B, e.g. fcamera.hevc / ecamera.hevc)
 * into a proper MP4 container so any Android player (VideoView / MediaPlayer /
 * MX Player) can hardware-decode it.
 *
 * Container remux only: the compressed picture data is copied verbatim.
 */
public final class HevcTranscoder {

    public interface Progress { void onProgress(int percent); }

    /** openpilot logs at a fixed 20 Hz; the video is encoded at the same cadence. */
    private static final int DEFAULT_FPS = 20;

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

    /** Rewrap [src] (raw Annex-B HEVC) into [out] (an .mp4 that plays natively). */
    public static boolean transcode(File src, File out, Progress cb) throws IOException {
        logFile = new File(out.getParentFile(), "tc.log");
        try { if (logFile.exists()) logFile.delete(); } catch (Throwable ignored) {}
        log("=== transcode start ===");
        log("src=" + src + " size=" + src.length());
        log("out=" + out);

        final long total = src.length();
        RandomAccessFile in = new RandomAccessFile(src, "r");
        try {
            final long fileLen = in.length();

            // ---- 1. scan for NAL start codes (00 00 01 / 00 00 00 01) ----
            List<long[]> nals = new ArrayList<>();
            byte[] buf = new byte[1 << 20];
            long scanPos = 0;
            int bufLen;
            in.seek(0);
            while ((bufLen = in.read(buf)) > 0) {
                for (int i = 0; i < bufLen; i++) {
                    if ((buf[i] & 0xFF) != 0) continue;
                    int i1 = i + 1, i2 = i + 2;
                    if (i2 >= bufLen) continue;
                    if ((buf[i1] & 0xFF) == 0 && (buf[i2] & 0xFF) == 1) {
                        if (i >= 1 && (buf[i - 1] & 0xFF) == 0)
                            nals.add(new long[]{scanPos + i - 1, 4});
                        else
                            nals.add(new long[]{scanPos + i, 3});
                    }
                }
                scanPos += bufLen;
            }
            if (nals.isEmpty()) throw new IOException("no NAL start codes (not Annex-B HEVC?)");
            log("nal start codes=" + nals.size());

            // ---- 2. split into csd (VPS+SPS+PPS) + one sample per PICTURE ----
            ByteArrayOutputStream csdW = new ByteArrayOutputStream();
            int w = 0, h = 0;
            List<List<long[]>> samples = new ArrayList<>();
            List<long[]> curSlices = new ArrayList<>();

            for (int k = 0; k < nals.size(); k++) {
                long scOff = nals.get(k)[0];
                int scLen = (int) nals.get(k)[1];
                long nalDataOff = scOff + scLen;
                long nalDataEnd = (k + 1 < nals.size()) ? nals.get(k + 1)[0] : fileLen;
                int nalLen = (int) (nalDataEnd - nalDataOff);
                if (nalLen <= 0) continue;

                in.seek(nalDataOff);
                int nalHeader = in.readByte() & 0xFF;
                int nalType = (nalHeader >> 1) & 0x3F;

                if (nalType == 32 || nalType == 33 || nalType == 34) {
                    byte[] nal = new byte[nalLen];
                    in.seek(nalDataOff);
                    in.readFully(nal);
                    writeLengthPrefixed(csdW, nal);
                    if (nalType == 33 && w == 0) {
                        int[] wh = parseSpsSize(nal);
                        if (wh != null) { w = wh[0]; h = wh[1]; }
                    }
                } else if (nalType < 32) {
                    boolean firstSlice;
                    if (nalLen >= 3) {
                        in.seek(nalDataOff + 2);
                        firstSlice = ((in.readByte() & 0x80) != 0);
                    } else {
                        firstSlice = true;
                    }
                    long trimmed = trimTrailingZeros(in, nalDataOff, nalLen);
                    if (firstSlice && !curSlices.isEmpty()) {
                        samples.add(curSlices);
                        curSlices = new ArrayList<>();
                    }
                    curSlices.add(new long[]{nalDataOff, trimmed});
                    if (cb != null && (samples.size() & 0xFF) == 0 && total > 0)
                        cb.onProgress((int) Math.min(99, (nalDataOff * 100) / total));
                }
            }
            if (!curSlices.isEmpty()) samples.add(curSlices);

            if (samples.isEmpty()) throw new IOException("no VCL NAL units found");
            if (csdW.size() == 0) throw new IOException("no VPS/SPS/PPS found");
            if (w == 0 || h == 0) { w = 1928; h = 1208; }
            log("pictures=" + samples.size() + " csdBytes=" + csdW.size() + " size=" + w + "x" + h);

            // ---- 3. write MP4 (zero re-encode) ----
            if (out.exists()) out.delete();
            MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat fmt = MediaFormat.createVideoFormat("video/hevc", w, h);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS);
            fmt.setInteger("max-input-size", 4 * 1024 * 1024);
            // IMPORTANT: csd-0 must be a DIRECT ByteBuffer. With a heap (wrap) buffer,
            // some Qualcomm/Android MediaMuxer builds fail to build the 'stsd' box and
            // then silently DROP every writeSampleData() -> an empty 585-byte mp4 whose
            // 'stbl' is empty. That was the black-screen root cause.
            byte[] csd = csdW.toByteArray();
            ByteBuffer csdBuf = ByteBuffer.allocateDirect(csd.length);
            csdBuf.put(csd);
            csdBuf.flip();
            fmt.setByteBuffer("csd-0", csdBuf);

            int track;
            try {
                track = muxer.addTrack(fmt);
                log("addTrack ok, track=" + track);
                muxer.start();
                log("muxer.start ok");
            } catch (Throwable t) {
                log("addTrack/start FAILED: " + t);
                try { muxer.release(); } catch (Throwable ignored) {}
                throw new IOException("muxer setup failed: " + t);
            }

            ByteBuffer sampleBuf = ByteBuffer.allocate(4 * 1024 * 1024).order(ByteOrder.BIG_ENDIAN);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long ptsUs = 0;
            int written = 0;
            int skipped = 0;
            for (int i = 0; i < samples.size(); i++) {
                List<long[]> pic = samples.get(i);

                long need = 0;
                for (long[] sl : pic) need += 4 + sl[1];
                if (need <= 0 || need > Integer.MAX_VALUE - 8) { skipped++; continue; }

                if (need > sampleBuf.capacity())
                    sampleBuf = ByteBuffer.allocate((int) need + 1024).order(ByteOrder.BIG_ENDIAN);
                sampleBuf.clear();

                for (long[] sl : pic) {
                    int len = (int) sl[1];
                    sampleBuf.putInt(len);
                    long off = sl[0], remaining = len;
                    while (remaining > 0) {
                        int chunk = (int) Math.min(1 << 20, remaining);
                        byte[] tmp = new byte[chunk];
                        in.seek(off);
                        in.readFully(tmp);
                        sampleBuf.put(tmp);
                        off += chunk;
                        remaining -= chunk;
                    }
                }
                sampleBuf.flip();

                info.set(0, (int) need, ptsUs, 0);
                try {
                    muxer.writeSampleData(track, sampleBuf, info);
                    written++;
                } catch (Throwable t) {
                    log("writeSampleData[" + i + "] FAILED: " + t);
                    if (skipped == 0) throw new IOException("writeSampleData failed: " + t);
                }

                ptsUs += 1_000_000L / DEFAULT_FPS;
                if ((i % 500) == 0) log("after " + i + ": out=" + out.length());
                if (cb != null && (i & 0xFF) == 0)
                    cb.onProgress((int) Math.min(100, (i * 100L) / samples.size()));
            }

            log("loop done: written=" + written + " skipped=" + skipped);
            // Sanity: a real clip is many MB. If MediaMuxer silently dropped everything
            // (no stsd/stbl), the file stays tiny -> report it loudly instead of "success".
            long sz = out.length();
            if (written > 0 && sz < 100_000) {
                log("WARNING: muxer wrote " + written + " samples but file is only " + sz + " bytes");
                throw new IOException("muxer dropped all samples (file=" + sz + "B)");
            }
            try {
                muxer.stop();
                log("muxer.stop ok");
            } catch (Throwable t) {
                log("muxer.stop FAILED: " + t);
            } finally {
                try { muxer.release(); } catch (Throwable ignored) {}
            }
            if (cb != null) cb.onProgress(100);
            log("done: out size=" + out.length());
            return out.length() > 0;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            log("=== transcode end ===");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void writeLengthPrefixed(ByteArrayOutputStream o, byte[] nal) {
        int n = nal.length;
        o.write((n >>> 24) & 0xFF); o.write((n >>> 16) & 0xFF);
        o.write((n >>> 8) & 0xFF);  o.write(n & 0xFF);
        o.write(nal, 0, nal.length);
    }

    /** Return effective NAL length with trailing 0x00 bytes removed. */
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

    /** Parse SPS to get width/height. Returns null if it cannot be parsed. */
    private static int[] parseSpsSize(byte[] nal) {
        try {
            byte[] rbsp = removeEmulationPrevention(nal, 0, nal.length);
            BitReader br = new BitReader(rbsp);
            br.readBits(16); // NAL header
            br.readUE(); // sps_video_parameter_set_id
            int spsMaxSubLayersMinus1 = br.readBits(3);
            br.skipBits(1); // sps_temporal_id_nesting_flag
            br.skipBits(2 + 1 + 5 + 32 + 48 + 8); // profile_tier_level (general)
            for (int i = 0; i < spsMaxSubLayersMinus1; i++) {
                br.readBit(); // profile_tier_present
                br.skipBits(88);
            }
            br.readUE(); // sps_seq_parameter_set_id
            int chromaFormatIdc = br.readUE();
            if (chromaFormatIdc == 3) br.skipBits(1);
            int picWidthInLumaSamples = br.readUE();
            int picHeightInLumaSamples = br.readUE();
            if (picWidthInLumaSamples > 0 && picHeightInLumaSamples > 0)
                return new int[]{picWidthInLumaSamples, picHeightInLumaSamples};
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] removeEmulationPrevention(byte[] in, int from, int len) {
        byte[] out = new byte[len];
        int o = 0;
        int zeros = 0;
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