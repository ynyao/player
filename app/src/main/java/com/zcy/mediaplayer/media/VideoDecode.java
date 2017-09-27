package com.zcy.mediaplayer.media;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.zcy.mediaplayer.opengl.GLFrameRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static android.content.ContentValues.TAG;

/**
 * Created by zcy on 2017/9/27.
 */

public class VideoDecode {

    private File sourceFile;

    private FrameCallback mFrameCallback;

    public int mVideoWidth;

    public int mVideoHeight;

    private static final boolean VERBOSE = false;

    private Player mPlayer;

    public VideoDecode(Player player) {
        mPlayer=player;
    }

    public void setFrameCallback(FrameCallback frameCallback) {
        mFrameCallback = frameCallback;
    }

    private boolean isPause;

    public File getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(File sourceFile) {
        this.sourceFile = sourceFile;
    }


    public void start(){
        initDecode();
        doExtract();
    }

    /**
     * Returns the width, in pixels, of the video.
     */
    public int getVideoWidth() {
        return mVideoWidth;
    }

    /**
     * Returns the height, in pixels, of the video.
     */
    public int getVideoHeight() {
        return mVideoHeight;
    }

    private void initDecode() {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.toString()); //抛异常
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + sourceFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private static int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    // Declare this here to reduce allocations.
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private boolean mLoop;
    // May be set/read by different threads.
    private volatile boolean mIsStopRequested;
    /**
     * Work loop.  We execute here until we run out of video or are told to stop.
     */
    private void doExtract()  {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        // The MediaExtractor error messages aren't very useful.  Check to see if the input
        // file exists so we can throw a better one if it's not there.
        if (!sourceFile.canRead()) {
            //            throw new FileNotFoundException("Unable to read " + sourceFile);
        }
        int trackIndex;
        extractor = new MediaExtractor();
        try {
            extractor.setDataSource(sourceFile.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        trackIndex = selectTrack(extractor);
        if (trackIndex < 0) {
            throw new RuntimeException("No video track found in " + sourceFile);
        }
        extractor.selectTrack(trackIndex);

        MediaFormat format = extractor.getTrackFormat(trackIndex);

        // Create a MediaCodec decoder, and configure it with the MediaFormat from the
        // extractor.  It's very important to use the format from the extractor because
        // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
        String mime = format.getString(MediaFormat.KEY_MIME);
        try {
            decoder = MediaCodec.createDecoderByType(mime);
        } catch (IOException e) {
            e.printStackTrace();
        }
        decoder.configure(format, null, null, 0);
        decoder.start();


        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        int inputChunk = 0;
        long firstInputTimeNsec = -1;
        PlayVideo playFrame = mPlayer;

        int outputFrameCount = 0;

        boolean outputDone = false;
        boolean inputDone = false;
        while (!outputDone) {
            if (VERBOSE)
                Log.d(TAG, "loop");
            if (mIsStopRequested) {
                Log.d(TAG, "Stop requested");
                playFrame.stopPlay();
                return;
            }
            if (!isPause) {
                // Feed more data to the decoder.
                if (!inputDone) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        if (firstInputTimeNsec == -1) {
                            firstInputTimeNsec = System.nanoTime();
                        }
                        ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                        // Read the sample data into the ByteBuffer.  This neither respects nor
                        // updates inputBuf's position, limit, etc.
                        int chunkSize = extractor.readSampleData(inputBuf, 0);
                        if (chunkSize < 0) {
                            // End of stream -- send empty frame with EOS flag set.
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            if (VERBOSE)
                                Log.d(TAG, "sent input EOS");
                        } else {
                            if (extractor.getSampleTrackIndex() != trackIndex) {
                                Log.w(TAG, "WEIRD: got sample from track " +
                                        extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                            }
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                    presentationTimeUs, 0 /*flags*/);
                            if (VERBOSE) {
                                Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                        chunkSize);
                            }
                            inputChunk++;
                            extractor.advance();
                        }
                    } else {
                        if (VERBOSE)
                            Log.d(TAG, "input buffer not available");
                    }
                }

                if (!outputDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        if (VERBOSE)
                            Log.d(TAG, "no output from decoder available");
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // not important for us, since we're using Surface
                        if (VERBOSE)
                            Log.d(TAG, "decoder output buffers changed");
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = decoder.getOutputFormat();
                        if (VERBOSE)
                            Log.d(TAG, "decoder output format changed: " + newFormat);
                    } else if (decoderStatus < 0) {
                        throw new RuntimeException(
                                "unexpected result from decoder.dequeueOutputBuffer: " +
                                        decoderStatus);
                    } else { // decoderStatus >= 0
                        if (firstInputTimeNsec != 0) {
                            // Log the delay from the first buffer of input to the first buffer
                            // of output.
                            long nowNsec = System.nanoTime();
                            Log.d(TAG, "startup lag " + ((nowNsec - firstInputTimeNsec) / 1000000.0) + " ms");
                            firstInputTimeNsec = 0;
                        }
                        boolean doLoop = false;
                        if (VERBOSE)
                            Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                                    " (size=" + mBufferInfo.size + ")");
                        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (VERBOSE)
                                Log.d(TAG, "output EOS");
                            if (mLoop) {
                                doLoop = true;
                            } else {
                                outputDone = true;
                            }
                        }

                        boolean doRender = (mBufferInfo.size != 0);

                        if (doRender) {
                            Image image = decoder.getOutputImage(decoderStatus);
                            if (image != null) {
                                byte[] bytes = ImageToData.getDataFromImage(image, ImageToData.COLOR_FormatI420, VERBOSE);

                                playFrame.doFrame(bytes, outputFrameCount);
                                outputFrameCount++;
                            }
                        }
                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  We can't control when it
                        // appears on-screen, but we can manage the pace at which we release
                        // the buffers.
                        if (doRender && mFrameCallback != null) {
                            mFrameCallback.preRender(mBufferInfo.presentationTimeUs);
                        }
                        decoder.releaseOutputBuffer(decoderStatus, doRender);
                        if (doRender && mFrameCallback != null) {
                            mFrameCallback.postRender();
                        }

                        if (doLoop) {
                            Log.d(TAG, "Reached EOS, looping");
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            inputDone = false;
                            decoder.flush();    // reset decoder state
                            mFrameCallback.loopReset();
                        }
                    }
                }
            }
        }
        playFrame.playFinish();
    }

    public interface PlayVideo {
        /**
         * 解码帧之前
         */
        void startFrame();
        /**
         * 处理帧
         * @param frame
         * @param frameCount
         */
        void doFrame(byte[] frame, int frameCount);
        /**
         * 播放视频完成
         */
        void playFinish();
        /**
         * 停止播放
         */
        void stopPlay();
    }

    public void playFrame(byte[] yuv, GLFrameRenderer glRenderer) {
        // TODO Auto-generated method stub
        glRenderer.update(getVideoWidth(), getVideoHeight());
        glRenderer.copyFrom(yuv, getVideoWidth(), getVideoHeight());
    }
}
