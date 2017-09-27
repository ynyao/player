package com.zcy.mediaplayer.media;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.zcy.mediaplayer.R;
import com.zcy.mediaplayer.opengl.GLFrameRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Created by zcy on 2017/9/27.
 */

public class Player implements VideoDecode.PlayVideo {

    private Activity act;

    private VideoDecode mVideoDecode;
    File mFile;

    private GLSurfaceView glSurfaceView;
    private GLFrameRenderer glRenderer;

    public Player(Activity act) {
        this.act=act;
        init();

    }

    private void init() {
        glSurfaceView= (GLSurfaceView) act.findViewById(R.id.gl_surface);
        glSurfaceView.setEGLContextClientVersion(2);

        glRenderer = new GLFrameRenderer(null, glSurfaceView, getDM(act));
        // set our renderer to be the main renderer with
        // the current activity context
        glSurfaceView.setRenderer(glRenderer);

        mVideoDecode=new VideoDecode(this);
//        mFile=new File("file:///android_asset/ttt.mp4");
        copyFilesFassets(act,"ttt.mp4","/sdcard/ttt.mp4");
        mVideoDecode.setSourceFile(new File("/sdcard/ttt.mp4"));
        SpeedControlCallback controlCallback=new SpeedControlCallback();
        controlCallback.setFixedPlaybackRate(30);
        mVideoDecode.setFrameCallback(controlCallback);

    }

    /**
     *  从assets目录中复制整个文件夹内容
     *  @param  context  Context 使用CopyFiles类的Activity
     *  @param  oldPath  String  原文件路径  如：/aa
     *  @param  newPath  String  复制后路径  如：xx:/bb/cc
     */
    public void copyFilesFassets(Context context,String oldPath,String newPath) {
        try {
            InputStream is = context.getAssets().open(oldPath);
            FileOutputStream fos = new FileOutputStream(new File(newPath));
            byte[] buffer = new byte[1024];
            int byteCount=0;
            while((byteCount=is.read(buffer))!=-1) {//循环从输入流读取 buffer字节
                fos.write(buffer, 0, byteCount);//将读取的输入流写入到输出流
            }
            fos.flush();//刷新缓冲区
            is.close();
            fos.close();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void start(){
        mVideoDecode.start();
    }

    @Override
    public void startFrame() {

    }

    @Override
    public void doFrame(byte[] frame, int frameCount) {
        mVideoDecode.playFrame(frame,glRenderer);
    }

    @Override
    public void playFinish() {

    }

    @Override
    public void stopPlay() {

    }

    public DisplayMetrics getDM(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);
        return outMetrics;
    }
}
