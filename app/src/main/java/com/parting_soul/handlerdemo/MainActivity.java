package com.parting_soul.handlerdemo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Handler==>>";
    private TextView mTv;

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(@NonNull Message msg) {
            //收到消息后的操作

            switch (msg.what) {
                case MSG_UPDATE_TV:
                    mTv.setText(msg.obj.toString());
                    break;
                default:
                    break;
            }
        }
    };

    public static final int MSG_UPDATE_TV = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTv = findViewById(R.id.tv_msg);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 模拟网络请求
                SystemClock.sleep(3000);

                Message message = Message.obtain();
                message.what = MSG_UPDATE_TV;
                message.obj = "接口数据";
                mHandler.sendMessage(message);
            }
        }).start();

        threadLocal();
    }


    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private void threadLocal() {
        threadLocal.set("这边是主线程的数据");

        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg = threadLocal.get();
                Log.e(TAG, "threadLocal " + Thread.currentThread() + " msg = " + msg);
                threadLocal.set("子线程中的数据");
            }
        }).start();


        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String msg = threadLocal.get();
                Log.e(TAG, "threadLocal " + Thread.currentThread() + " msg = " + msg);
            }
        },2000);
    }


    private Handler mHandler1 = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            return false;
        }
    });

}
