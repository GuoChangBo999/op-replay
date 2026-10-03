package ai.openpilot.replay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Pure-Java HEVC -> H.264 transcoder that works entirely on ByteBuffers.
 *
 * Why no EGL/OpenGL? The earlier surface/EGL path produced black frames on some
 * Qualcomm devices (SurfaceTexture + external-OES texture never latched a frame).
 * Going through ByteBuffers -- decoder outputs YUV420, we copy it to the encoder
 * input -- avoids GL entirely and is far more robust, at a small CPU cost.
 *
 * Pipeline:
 *   MediaExtractor(hevc raw) -> MediaCodec(hevc decoder, ByteBuffer out)
 *       -> copy planes -> MediaCodec(avc encoder, ByteBuffer in)
 *           -> MediaMuxer -> .mp4
 *
 * This is plain Java (not Kotlin) on purpose: the MediaCodec BufferInfo dance is
 * the classic, well-trodden Java sample territory, so it's the lowest-risk code.
 */
public class HevcTranscoder {

    public interface Progress { void onProgress(int percent); }

    /** Transcode [src] (an HEVC raw stream) into [out] (an H.264 mp4). Returns true on success. */
    public static boolean transcode(File src, File out, Progress cb) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.getAbsolutePath());

        int vTrack = -1;
        MediaFormat inFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { vTrack = i; inFmt = f; break; }
        }
        if (vTrack < 0 || inFmt == null) { ex.release(); throw new IOException("no video track"); }
        ex.selectTrack(vTrack);

        int width = inFmt.getInteger(MediaFormat.KEY_WIDTH);
        int height = inFmt.getInteger(MediaFormat.KEY_HEIGHT);
        int fps = inFmt.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? inFmt.getInteger(MediaFormat.KEY_FRAME_RATE) : 20;
        if (fps <= 0) fps = 20;
        long totalUs = inFmt.containsKey(MediaFormat.KEY_DURATION)
                ? inFmt.getLong(MediaFormat.KEY_DURATION) : 0L;

        String inMime = inFmt.getString(MediaFormat.KEY_MIME);

        // ---- encoder (H.264, byte-buffer input) ----
        MediaFormat outFmt = MediaFormat.createVideoFormat("video/avc", width, height);
        outFmt.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        outFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        outFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        outFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        enc.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        // ---- decoder (HEVC, byte-buffer output) ----
        MediaCodec dec = MediaCodec.createDecoderByType(inMime);
        dec.configure(inFmt, null, null, 0);
        dec.start();

        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int outTrack = -1;
        boolean muxStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long lastEncPts = 0;

        try {
            while (!outputDone) {
                // 1) feed the decoder from the extractor
                if (!inputDone) {
                    int inIdx = dec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(inIdx);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            dec.queueInputBuffer(inIdx, 0, sz, pts, 0);
                            ex.advance();
                        }
                    }
                }

                // 2) drain the decoder -> copy YUV into the encoder
                int dIdx = dec.dequeueOutputBuffer(info, 10_000);
                if (dIdx >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0 && !eos) {
                        // copy this decoded frame straight into the encoder
                        feedEncoder(enc, dec, dIdx, info, width, height);
                        if (totalUs > 0 && cb != null) {
                            cb.onProgress((int) Math.min(100,
                                    (info.presentationTimeUs * 100) / totalUs));
                        }
                        lastEncPts = info.presentationTimeUs;
                    }
                    dec.releaseOutputBuffer(dIdx, false);
                    if (eos) {
                        // signal encoder end (queue a zero-size buffer)
                        int eIn = -1;
                        for (int tries = 0; tries < 200 && eIn < 0; tries++)
                            eIn = enc.dequeueInputBuffer(10_000);
                        if (eIn >= 0)
                            enc.queueInputBuffer(eIn, 0, 0, lastEncPts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }

                // 3) drain the encoder -> muxer
                int eIdx = enc.dequeueOutputBuffer(info, 10_000);
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outTrack = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (eIdx >= 0) {
                    ByteBuffer eb = enc.getOutputBuffer(eIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted) {
                        eb.position(info.offset);
                        eb.limit(info.offset + info.size);
                        muxer.writeSampleData(outTrack, eb, info);
                    }
                    enc.releaseOutputBuffer(eIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } finally {
            try { if (muxStarted) muxer.stop(); } catch (Exception ignored) {}
            try { muxer.release(); } catch (Exception ignored) {}
            try { enc.stop(); enc.release(); } catch (Exception ignored) {}
            try { dec.stop(); dec.release(); } catch (Exception ignored) {}
            ex.release();
        }
        return out.length() > 0;
    }

    /**
     * Copy one decoded frame (YUV420) from the decoder output buffer at [dIdx]
     * into the encoder's next input buffer. Uses the Image API so we respect the
     * real plane strides/pixel-strides instead of assuming a tight packing.
     */
    private static void feedEncoder(MediaCodec enc, MediaCodec dec, int dIdx,
                                    MediaCodec.BufferInfo info,
                                    int width, int height) throws IOException {
        android.media.Image srcImg = null;
        android.media.Image dstImg = null;
        int inIdx = -1;
        try {
            srcImg = dec.getOutputImage(dIdx);
            // grab an encoder input buffer
            for (int tries = 0; tries < 200 && inIdx < 0; tries++)
                inIdx = enc.dequeueInputBuffer(10_000);
            if (inIdx < 0) return; // drop this frame rather than stall forever

            dstImg = enc.getInputImage(inIdx);
            if (srcImg == null || dstImg == null) {
                enc.queueInputBuffer(inIdx, 0, 0, info.presentationTimeUs, 0);
                return;
            }
            copyYuv(srcImg, dstImg);
            enc.queueInputBuffer(inIdx, 0, dstImgSize(dstImg), info.presentationTimeUs, 0);
        } finally {
            if (srcImg != null) srcImg.close();
            if (dstImg != null) dstImg.close();
        }
    }

    /** Copy Y/U/V planes between two YUV420 Images, honoring row & pixel strides. */
    private static void copyYuv(android.media.Image src, android.media.Image dst) {
        android.media.Image.Plane[] sp = src.getPlanes();
        android.media.Image.Plane[] dp = dst.getPlanes();
        int planes = Math.min(sp.length, dp.length);
        for (int p = 0; p < planes; p++) {
            ByteBuffer sb = sp[p].getBuffer();
            ByteBuffer db = dp[p].getBuffer();
            int sRowStride = sp[p].getRowStride();
            int sPixStride = sp[p].getPixelStride();
            int dRowStride = dp[p].getRowStride();
            int dPixStride = dp[p].getPixelStride();
            int w = src.getWidth() >> (p == 0 ? 0 : 1);
            int h = src.getHeight() >> (p == 0 ? 0 : 1);

            sb.rewind();
            db.rewind();
            if (sPixStride == 1 && dPixStride == 1) {
                // simple row-by-row copy
                int rowBytes = Math.min(sRowStride, dRowStride);
                byte[] line = new byte[rowBytes];
                for (int row = 0; row < h; row++) {
                    int sPos = row * sRowStride;
                    int dPos = row * dRowStride;
                    if (sPos + rowBytes > sb.capacity() || dPos + rowBytes > db.capacity()) break;
                    sb.position(sPos); sb.get(line, 0, rowBytes);
                    db.position(dPos); db.put(line, 0, rowBytes);
                }
            } else {
                // pixel-by-pixel (slower, but correct for interleaved/odd strides)
                int copyW = Math.min(w, Math.min(sb.capacity() / Math.max(1, sPixStride),
                        db.capacity() / Math.max(1, dPixStride)));
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < copyW; col++) {
                        int sPos = row * sRowStride + col * sPixStride;
                        int dPos = row * dRowStride + col * dPixStride;
                        if (sPos >= sb.capacity() || dPos >= db.capacity()) continue;
                        db.put(dPos, sb.get(sPos));
                    }
                }
            }
        }
    }

    /** Total size of the YUV planes we wrote into [img] (used for queueInputBuffer). */
    private static int dstYuvSize(android.media.Image img) {
        int total = 0;
        for (android.media.Image.Plane pl : img.getPlanes()) {
            ByteBuffer b = pl.getBuffer();
            total += b.capacity();
        }
        return total;
    }

    // encoder inputs in byte-buffer mode don't carry a real "size"; using the
    // image plane capacity is what the samples do.
    private static int dstImgSize(android.media.Image img) { return dstYuvSize(img); }
}