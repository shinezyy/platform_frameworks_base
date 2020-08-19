/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.biometrics.fingerprint;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.content.Context;
import android.view.View.OnTouchListener;
import android.view.View;
import android.widget.ImageView;
import android.view.MotionEvent;
import android.util.Slog;

import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.view.Gravity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;

import vendor.xiaomi.hardware.fingerprintextension.V1_0.IXiaomiFingerprint;
import vendor.goodix.extend.service.V2_0.IGoodixFPExtendService;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.ServiceManager;

public class FacolaView extends ImageView implements OnTouchListener {
    private final int mX, mY, mW, mH;
    private final Paint mPaintFingerprint = new Paint();
    private final Paint mPaintShow = new Paint();
    private IXiaomiFingerprint mXiaomiFingerprint = null;
    private IGoodixFPExtendService mGoodixFingerprint = null;
    private boolean mInsideCircle = false;
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

    private final static float UNTOUCHED_DIM = .1f;
    private final static float TOUCHED_DIM = .9f;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final WindowManager mWM;
    private final boolean samsungFod = samsungHasCmd("fod_enable");
    private final boolean noDim;

    private boolean mHidden = true;
    FacolaView(Context context) {
        super(context);

        android.util.Log.d("PHH", "Samsung FOD " + samsungFod);

        mHandlerThread = new HandlerThread("FacolaThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        noDim = android.os.SystemProperties.getBoolean("persist.sys.phh.nodim", false);
        String[] location = android.os.SystemProperties.get("persist.vendor.sys.fp.fod.location.X_Y", "").split(",");
        String[] size = android.os.SystemProperties.get("persist.vendor.sys.fp.fod.size.width_height", "").split(",");
        Slog.d("PHH-Enroll", "FacolaView hello");
        if(size.length == 2 && location.length == 2) {
            Slog.d("PHH-Enroll", "Got real values");
            mX = Integer.parseInt(location[0]);
            mY = Integer.parseInt(location[1]);
            mW = Integer.parseInt(size[0]);
            mH = Integer.parseInt(size[1]);
        } else {
            mX = -1;
            mY = -1;
            mW = -1;
            mH = -1;
        }

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(Color.GREEN);

        mPaintShow.setAntiAlias(true);
        mPaintShow.setColor(Color.argb(0x18, 0x00, 0xff, 0x00));
        setOnTouchListener(this);
        mWM = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Slog.d("PHH-Enroll", "Created facola...");
        if(mW != -1) {
            try {
                mXiaomiFingerprint = IXiaomiFingerprint.getService();
            } catch(Exception e) {
                Slog.d("PHH-Enroll", "Failed getting xiaomi fingerprint service", e);
            }
            try {
                mGoodixFingerprint = IGoodixFPExtendService.getService();
            } catch(Exception e) {
                Slog.d("PHH-Enroll", "Failed getting goodix fingerprint service", e);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Slog.d("PHH-Enroll", "Drawing at " + mX + ", " + mY + ", " + mW + ", " + mH);
        //TODO w!=h?
        if(mInsideCircle) {
            try {
                int nitValue = 2;
                if(mXiaomiFingerprint != null)
                    mXiaomiFingerprint.extCmd(0xa, nitValue);
                if(mGoodixFingerprint != null)
                    mXiaomiFingerprint.extCmd(10, 1);
            } catch(Exception e) {
                Slog.d("PHH-Enroll", "Failed calling xiaomi fp extcmd");
            }

            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintFingerprint);
        } else {
            try {
                if(mXiaomiFingerprint != null)
                    mXiaomiFingerprint.extCmd(0xa, 0);
                if(mGoodixFingerprint != null)
                    mXiaomiFingerprint.extCmd(10, 0);
            } catch(Exception e) {
                Slog.d("PHH-Enroll", "Failed calling xiaomi fp extcmd");
            }
            canvas.drawCircle(mW/2, mH/2, (float) (mW/2.0f), this.mPaintShow);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newInside = (x > 0 && x < mW) && (y > 0 && y < mW);
        if(event.getAction() == MotionEvent.ACTION_UP)
            newInside = false;

        Slog.d("PHH-Enroll", "Got action " + event.getAction() + ", x = " + x + ", y = " + y + ", inside = " + mInsideCircle + "/" + newInside);
        if(newInside == mInsideCircle) return mInsideCircle;
        mInsideCircle = newInside;

        invalidate();

        if(!mInsideCircle) {
            mParams.screenBrightness = .0f;
            mParams.dimAmount = UNTOUCHED_DIM;
            mWM.updateViewLayout(this, mParams);
            return false;
        }

        if(!noDim) {
            mParams.dimAmount = TOUCHED_DIM;
            mParams.screenBrightness = 1.0f;
        }
        mWM.updateViewLayout(this, mParams);

        return true;
    }

    public void show() {
        Slog.d("PHH-Enroll", "Show", new Exception());
        if(!mHidden) return;
        mHidden = false;
        mInsideCircle = false;
        if(samsungFod) {
            samsungCmd("fod_enable,1,1");
        }
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        try {
            PrintWriter writer = new PrintWriter("/sys/devices/virtual/touch/tp_dev/fod_status", "UTF-8");
            writer.println("1");
            writer.close();
        } catch(Exception e) {
            Slog.d("PHH-Enroll", "Failed setting fod status for touchscreen");
        }

        mParams.x = mX;
        mParams.y = mY;

        mParams.height = mW;
        mParams.width = mH;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.setTitle("Fingerprint on display");
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
            WindowManager.LayoutParams.FLAG_DIM_BEHIND |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.dimAmount = UNTOUCHED_DIM;
        mParams.screenBrightness = .0f;

        mParams.packageName = "android";

        mParams.gravity = Gravity.TOP | Gravity.LEFT;
        mHandler.post( () -> {
            mWM.addView(this, mParams);
        });

    }

    public void hide() {
        mInsideCircle = false;
        Slog.d("PHH-Enroll", "Hide", new Exception());
        if(mHidden) return;
        mHidden = true;
        if(samsungFod) {
            samsungCmd("fod_enable,0");
        }
        if(mX == -1 || mY == -1 || mW == -1 || mH == -1) return;

        try {
            if(mXiaomiFingerprint != null)
                mXiaomiFingerprint.extCmd(0xa, 0);
        } catch(Exception e) {
            Slog.d("PHH-Enroll", "Failed calling xiaomi fp extcmd");
        }
        try {
            PrintWriter writer = new PrintWriter("/sys/devices/virtual/touch/tp_dev/fod_status", "UTF-8");
            writer.println("0");
            writer.close();
        } catch(Exception e) {
            Slog.d("PHH-Enroll", "Failed setting fod status for touchscreen");
        }

        Slog.d("PHH-Enroll", "Removed facola");
        mHandler.post( () -> {
            mWM.removeView(this);
        });
    }

    private static boolean samsungHasCmd(String cmd) {
        try {
            File f = new File("/sys/devices/virtual/sec/tsp/cmd_list");
            if(!f.exists()) return false;

            BufferedReader b = new BufferedReader(new FileReader(f));
            String line = null;
            while( (line = b.readLine()) != null) {
                if(line.equals(cmd)) return true;
            }
            return false;
        } catch(Exception e) {
            return false;
        }
    }

    private static String readFile(String path) {
        try {
            File f = new File(path);

            BufferedReader b = new BufferedReader(new FileReader(f));
            return b.readLine();
        } catch(Exception e) {
            return null;
        }
    }

    private static void samsungCmd(String cmd) {
        try {
            PrintWriter writer = new PrintWriter("/sys/devices/virtual/sec/tsp/cmd", "UTF-8");
            writer.println(cmd);
            writer.close();

            String status = readFile("/sys/devices/virtual/sec/tsp/cmd_status");
            String ret = readFile("/sys/devices/virtual/sec/tsp/cmd_result");

            android.util.Log.d("PHH", "Sending command " + cmd + " returned " + ret + ":" + status);
        } catch(Exception e) {
            android.util.Log.d("PHH", "Failed sending command " + cmd, e);
        }
    }

}
