package com.zcy.mediaplayer;

import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;

import com.zcy.mediaplayer.media.Player;

public class MainActivity extends AppCompatActivity {

    private Player mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPlayer =new Player(this);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mPlayer.start();
            }
        },1000);

    }
}
