package ai.openpilot.replay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * WHY THIS EXISTS
 * ---------------
 * openpilot's "*.hevc" files are raw HEVC elementary streams: a flat sequence of
 * NAL units prefixed with 00 00 00 01 start codes, with NO container, NO timestamps
 * and NO index. Android's MediaExtractor (and therefore VideoView) does not reliably
 * parse these -> "no video track" / a black screen. That is the real reason the video
 * "won't play", independent of any UI.
 *
 * THE FIX (no re-encoding!)
 * -------------------------
 * We do NOT touch the compressed picture data at all:
 *   1. scan the byte stream, split it into NAL units at the start codes
 *   2. keep VPS/SPS/PPS as the codec-specific data (csd-0)
 *   3. turn every VCL NAL into an MP4 "length-prefixed" sample
 *      (4-byte big-endian length + NAL payload, no start code)
 *   4. write them with MediaMuxer, stamping pts = index / fps
 *
 * Container remux: a 60s / 75MB clip finishes in well under a second and never
 * depends on the device having a particular MediaCodec.
 */
public final class HevcTranscoder {

    public interface Progress { void onProgress(int percent); }

    /** openpilot logs at a fixed 20 Hz; the video is encoded at the same cadence. */
    private static final int DEFAULT_FPS = 20;

    private HevcTranscoder() {}

    /** Rewrap [src] (raw Annex-B HEVC) into [out] (an .mp4 that plays natively). */
    public static boolean transcode(File src, File out, Progress cb) throws IOException {
        final long total = src.length();
        RandomAccessFile in = new RandomAccessFile(src, "r");
        try {
            final long fileLen = in.length();

            // ---- 1. scan for NAL start codes (00 00 01 / 00 00 00 01) ----
            List<long[]> nals = new ArrayList<>();   // {startCodeOffset, startCodeLen}
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

            // ---- 2. split into csd (VPS+SPS+PPS) + one sample per PICTURE ----
            ByteArrayOutputStream csdW = new ByteArrayOutputStream();
            int w = 0, h = 0;
            // Each sample = one picture; store the list of its slice NALs so the
            // muxer step can emit [len][nal]...[len][nal] with NO start codes.
            List<List<long[]>> samples = new ArrayList<>();   // picture -> list of {dataOffset,len}
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
                    // VPS / SPS / PPS -> codec-specific data (all of them, in order)
                    byte[] nal = new byte[nalLen];
                    in.seek(nalDataOff);
                    in.readFully(nal);
                    writeLengthPrefixed(csdW, nal);
                    if (nalType == 33 && w == 0) {
                        int[] wh = parseSpsSize(nal);
                        if (wh != null) { w = wh[0]; h = wh[1]; }
                    }
                } else if (nalType < 32) {
                    // VCL NAL. Check first_slice_segment_in_pic_flag (bit 7 of byte 2).
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
                // SEI / other NAL types are skipped (optional for playback)
            }
            if (!curSlices.isEmpty()) samples.add(curSlices);

            if (samples.isEmpty()) throw new IOException("no VCL NAL units found");
            if (csdW.size() == 0) throw new IOException("no VPS/SPS/PPS found");
            if (w == 0 || h == 0) { w = 1928; h = 1208; }  // openpilot 3-cam default

            // ---- 3. write MP4 (zero re-encode) ----
            if (out.exists()) out.delete();
            MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat fmt = MediaFormat.createVideoFormat("video/hevc", w, h);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            fmt.setInteger("max-input-size", 4 * 1024 * 1024);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csdW.toByteArray()));
            int track = muxer.addTrack(fmt);
            muxer.start();

            ByteBuffer sampleBuf = ByteBuffer.allocate(4 * 1024 * 1024).order(ByteOrder.BIG_ENDIAN);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long ptsUs = 0;
            for (int i = 0; i < samples.size(); i++) {
                List<long[]> pic = samples.get(i);

                // total = Σ (4-byte length prefix + slice bytes)
                long need = 0;
                for (long[] sl : pic) need += 4 + sl[1];
                if (need <= 0 || need > Integer.MAX_VALUE - 8) continue;

                if (need > sampleBuf.capacity())
                    sampleBuf = ByteBuffer.allocate((int) need + 1024).order(ByteOrder.BIG_ENDIAN);
                sampleBuf.clear();

                for (long[] sl : pic) {
                    int len = (int) sl[1];
                    sampleBuf.putInt(len);                 // 4-byte big-endian length prefix
                    long off = sl[0], remaining = len;
                    while (remaining > 0) {
                        int chunk = (int) Math.min(1 << 20, remaining);
                        byte[] tmp = new byte[chunk];
                        in.seek(off);
                        // read exactly `chunk` (RandomAccessFile.readFully)
                        in.readFully(tmp);
                        sampleBuf.put(tmp);
                        off += chunk;
                        remaining -= chunk;
                    }
                }
                sampleBuf.flip();

                info.set(0, (int) need, ptsUs, 0);
                muxer.writeSampleData(track, sampleBuf, info);

                ptsUs += 1_000_000L / DEFAULT_FPS;
                if (cb != null && (i & 0xFF) == 0)
                    cb.onProgress((int) Math.min(100, (i * 100L) / samples.size()));
            }

            try { muxer.stop(); } finally { muxer.release(); }
            if (cb != null) cb.onProgress(100);
            return out.length() > 0;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void writeLengthPrefixed(ByteArrayOutputStream o, byte[] nal) {
        int n = nal.length;
        o.write((n >>> 24) & 0xFF); o.write((n >>> 16) & 0xFF);
        o.write((n >>> 8) & 0xFF);  o.write(n & 0xFF);
        o.write(nal, 0, n);
    }

    /** Read [len] bytes at [off]; return length with trailing 0x00 trimmed. */
    private static long trimTrailingZeros(RandomAccessFile f, long off, int len) throws IOException {
        if (len <= 1) return len;
        int tail = Math.min(16, len);
        f.seek(off + len - tail);
        byte[] t = new byte[tail];
        f.readFully(t);
        int end = tail;
        while (end > 0 && t[end - 1] == 0) end--;
        return (long) (len - tail) + end;
    }

    /** Best-effort SPS parse for width/height; null on failure (caller falls back). */
    private static int[] parseSpsSize(byte[] nal) {
        try {
            byte[] rbsp = removeEmulationPrevention(nal, 2);
            BitReader br = new BitReader(rbsp);
            br.u(4);                        // sps_video_parameter_set_id
            int maxSubLayers = br.u(3);     // sps_max_sub_layers_minus1
            br.u(1);                        // sps_temporal_id_nesting_flag
            br.profileTierLevel(maxSubLayers);
            br.ue();                         // sps_seq_parameter_set_id
            int chromaFormatIdc = br.ue();
            if (chromaFormatIdc == 3) br.u(1);
            int width = br.ue();
            int height = br.ue();
            if (width > 0 && height > 0) return new int[]{width, height};
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] removeEmulationPrevention(byte[] data, int from) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length);
        int zeros = 0;
        for (int i = from; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (zeros >= 2 && b == 0x03) { zeros = 0; continue; }
            o.write(b);
            if (b == 0) zeros++; else zeros = 0;
        }
        return o.toByteArray();
    }

    /** Tiny MSB-first bit reader. */
    private static final class BitReader {
        private final byte[] d; private int bit = 0;
        BitReader(byte[] d) { this.d = d; }
        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int bytePos = bit >> 3, bitPos = 7 - (bit & 7);
                int b = (bytePos < d.length) ? ((d[bytePos] >> bitPos) & 1) : 0;
                v = (v << 1) | b; bit++;
            }
            return v;
        }
        int ue() {
            int zeros = 0;
            while (u(1) == 0 && zeros < 31) zeros++;
            if (zeros == 0) return 0;
            return (1 << zeros) - 1 + u(zeros);
        }
        void profileTierLevel(int maxSubLayers) {
            u(2); u(1); u(5);   // profile_space, tier_flag, profile_idc
            u(32);              // profile_compatibility_flags
            u(48);              // constraint flags
            u(8);               // level_idc
            boolean[] sub = new boolean[maxSubLayers];
            for (int i = 0; i < maxSubLayers; i++) sub[i] = u(1) == 1;
            if (maxSubLayers > 0) for (int i = maxSubLayers; i < 8; i++) u(2);
            for (int i = 0; i < maxSubLayers; i++) if (sub[i]) u(88);
        }
    }
}