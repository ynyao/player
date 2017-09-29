package com.zcy.mediaplayer.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
        initDecode2();
        doExtract();
    }


    private SpeedControlCallback mFrameCallback2;

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mSampleRate = 0;
    int channel = 0;
    private void initDecode2() {
        mFrameCallback2=new SpeedControlCallback();
        mFrameCallback2.setFixedPlaybackRate(15);
        //创建MediaExtractor对象用来解AAC封装
        mExtractor = new MediaExtractor();
        try {
            //设置需要MediaExtractor解析的文件的路径
            mExtractor.setDataSource(sourceFile.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }

        int track = selectTrack2(mExtractor);

        MediaFormat format = mExtractor.getTrackFormat(track);
        if (format == null)
        {
            Log.e(TAG,"format is null");
            return;
        }

        //判断当前帧的文件类型是否为audio
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime.startsWith("audio/")) {
            Log.d(TAG, "format : " + format);
            //获取当前帧的采样率
            mExtractor.selectTrack(track);
            mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            //获取当前帧的通道数
            channel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            //音频文件长度
            long duration = format.getLong(MediaFormat.KEY_DURATION);
            Log.d(TAG,"length:"+duration/1000000);
        }


        //创建MediaCodec对象
        try {
            mDecoder = MediaCodec.createDecoderByType(mime);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //配置MediaCodec
        mDecoder.configure(format, null, null, 0);

        if (mDecoder == null) {
            Log.e(TAG, "Can't find video info!");
            return;
        }
        //启动MediaCodec
        mDecoder.start();

        new Thread(AACDecoderAndPlayRunnable).start();
//        AACDecoderAndPlay();
    }


    private static int selectTrack2(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }


    Runnable AACDecoderAndPlayRunnable = new Runnable() {

        @Override
        public void run() {
            AACDecoderAndPlay();
        }
    };

    private static final int TIMEOUT_US = 1000;
    private boolean eosReceived;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    public void AACDecoderAndPlay() {
        ByteBuffer[] inputBuffers = mDecoder.getInputBuffers();
        ByteBuffer[] outputBuffers = mDecoder.getOutputBuffers();


        //TODO  注意这里的单双声道   音频采样深度 8bit和16bit
        int buffsize = AudioTrack.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // 创建AudioTrack对象
        AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffsize,
                AudioTrack.MODE_STREAM);
        //启动AudioTrack
        audioTrack.play();

        while (!eosReceived) {
            int inIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
            if (inIndex >= 0) {
                ByteBuffer buffer = inputBuffers[inIndex];
                //从MediaExtractor中读取一帧待解数据
                int sampleSize = mExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    // We shouldn't stop the playback at this point, just pass the EOS
                    // flag to mDecoder, we will get it again from the
                    // dequeueOutputBuffer
                    Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                    mDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                } else {
                    //向MediaDecoder输入一帧待解码数据
                    mDecoder.queueInputBuffer(inIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
                    mExtractor.advance();
                }
                //从MediaDecoder队列取出一帧解码后的数据
                int outIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_US);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                        outputBuffers = mDecoder.getOutputBuffers();
                        break;

                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        MediaFormat format = mDecoder.getOutputFormat();
                        Log.d(TAG, "New format " + format);
                        audioTrack.setPlaybackRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        break;

                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.d(TAG, "dequeueOutputBuffer timed out!");
                        break;

                    default:
                        ByteBuffer outBuffer = outputBuffers[outIndex];
                        //Log.v(TAG, "outBuffer: " + outBuffer);

                        final byte[] chunk = new byte[info.size];
                        // Read the buffer all at once
                        outBuffer.get(chunk);
                        //清空buffer,否则下一次得到的还会得到同样的buffer
                        outBuffer.clear();
                        // AudioTrack write data
                        audioTrack.write(chunk, info.offset, info.offset + info.size);
                        mDecoder.releaseOutputBuffer(outIndex, false);
                        break;
                }


//                boolean doRender = (info.size != 0);
//
//                if (doRender && mFrameCallback != null) {
//                    mFrameCallback2.preRender(mBufferInfo.presentationTimeUs);
//                }
//                //mDecoder.releaseOutputBuffer(outIndex, doRender);
//                if (doRender && mFrameCallback != null) {
//                    mFrameCallback2.postRender();
//                }
//
//                if (true) {
//                    Log.d(TAG, "Reached EOS, looping");
////                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
////                    inputDone = false;
////                    decoder.flush();    // reset decoder state
//                    mFrameCallback2.loopReset();
//                }


                // 所有帧都解码、播放完之后退出循环
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    break;
                }
            }
        }

        //释放MediaDecoder资源
        mDecoder.stop();
        mDecoder.release();
        mDecoder = null;

        //释放MediaExtractor资源
        mExtractor.release();
        mExtractor = null;

        //释放AudioTrack资源
        audioTrack.stop();
        audioTrack.release();
        audioTrack = null;
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
            Log.d("videodecode",mime);
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
