package com.parting_soul.handlerdemo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Handler==>>";
    private TextView mTv;

    private Handler mHandler = new Handler() {
        @Override
        public void dispatchMessage(@NonNull Message msg) {
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
    }


    private Handler mHandler1 = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            return false;
        }
    });

}
