package com.zcy.mediaplayer.media;

/**
 * Created by zcy on 2017/5/22.
 */

/**
 * Callback invoked when rendering video frames.  The MoviePlayer client must
 * provide one of these.
 */
public interface FrameCallback {
    /**
     * Called immediately before the frame is rendered.
     *
     * @param presentationTimeUsec The desired presentation time, in microseconds.
     */
    void preRender(long presentationTimeUsec);

    /**
     * Called immediately after the frame render call returns.  The frame may not have
     * actually been rendered yet.
     * TODO: is this actually useful?
     */
    void postRender();

    /**
     * Called after the last frame of a looped movie has been rendered.  This allows the
     * callback to adjust its expectations of the next presentation time stamp.
     */
    void loopReset();
}