/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.display;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.util.Slog;

import com.android.internal.app.NightDisplayController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tints the display at night.
 */
public final class NightDisplayService extends SystemService
        implements NightDisplayController.Callback {

    private static final String TAG = "NightDisplayService";
    private static final boolean DEBUG = false;

    /**
     * Night display ~= 3400 K.
     */
    private static final float[] MATRIX_NIGHT = new float[] {
        1,      0,      0, 0,
        0, 0.754f,      0, 0,
        0,      0, 0.516f, 0,
        0,      0,      0, 1
    };

    private final Handler mHandler;

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private NightDisplayController mController;
    private Boolean mIsActivated;
    private AutoMode mAutoMode;

    public NightDisplayService(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        // Nothing to publish.
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendents */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        // Create a new controller for the current user and start listening for changes.
        mController = new NightDisplayController(getContext(), mCurrentUser);
        mController.setListener(this);

        // Initialize the current auto mode.
        onAutoModeChanged(mController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsActivated == null) {
            onActivated(mController.isActivated());
        }
    }

    private void tearDown() {
        if (mController != null) {
            mController.setListener(null);
            mController = null;
        }

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        mIsActivated = null;
    }

    @Override
    public void onActivated(boolean activated) {
        if (mIsActivated == null || mIsActivated != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            mIsActivated = activated;

            if (mAutoMode != null) {
                mAutoMode.onActivated(activated);
            }

            // Update the current color matrix.
            final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
            dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY,
                    activated ? MATRIX_NIGHT : null);
        }
    }

    @Override
    public void onAutoModeChanged(int autoMode) {
        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (autoMode == NightDisplayController.AUTO_MODE_CUSTOM) {
            mAutoMode = new CustomAutoMode();
        } else if (autoMode == NightDisplayController.AUTO_MODE_TWILIGHT) {
            mAutoMode = new TwilightAutoMode();
        }

        if (mAutoMode != null) {
            mAutoMode.onStart();
        }
    }

    @Override
    public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    @Override
    public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
        if (mAutoMode != null) {
            mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private abstract class AutoMode implements NightDisplayController.Callback {
        public abstract void onStart();
        public abstract void onStop();
    }

    private class CustomAutoMode extends AutoMode implements AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private NightDisplayController.LocalTime mStartTime;
        private NightDisplayController.LocalTime mEndTime;

        private Calendar mLastActivatedTime;

        public CustomAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final Calendar now = Calendar.getInstance();
            final Calendar startTime = mStartTime.getDateTimeBefore(now);
            final Calendar endTime = mEndTime.getDateTimeAfter(startTime);
            final boolean activated = now.before(endTime);

            boolean setActivated = mIsActivated == null || mLastActivatedTime == null;
            if (!setActivated && mIsActivated != activated) {
                final TimeZone currentTimeZone = now.getTimeZone();
                if (!currentTimeZone.equals(mLastActivatedTime.getTimeZone())) {
                    final int year = mLastActivatedTime.get(Calendar.YEAR);
                    final int dayOfYear = mLastActivatedTime.get(Calendar.DAY_OF_YEAR);
                    final int hourOfDay = mLastActivatedTime.get(Calendar.HOUR_OF_DAY);
                    final int minute = mLastActivatedTime.get(Calendar.MINUTE);

                    mLastActivatedTime.setTimeZone(currentTimeZone);
                    mLastActivatedTime.set(Calendar.YEAR, year);
                    mLastActivatedTime.set(Calendar.DAY_OF_YEAR, dayOfYear);
                    mLastActivatedTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    mLastActivatedTime.set(Calendar.MINUTE, minute);
                }

                if (mIsActivated) {
                    setActivated = now.before(mStartTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mEndTime.getDateTimeAfter(mLastActivatedTime));
                } else {
                    setActivated = now.before(mEndTime.getDateTimeBefore(mLastActivatedTime))
                            || now.after(mStartTime.getDateTimeAfter(mLastActivatedTime));
                }
            }

            if (setActivated) {
                mController.setActivated(activated);
            }
            updateNextAlarm();
        }

        private void updateNextAlarm() {
            if (mIsActivated != null) {
                final Calendar now = Calendar.getInstance();
                final Calendar next = mIsActivated ? mEndTime.getDateTimeAfter(now)
                        : mStartTime.getDateTimeAfter(now);
                mAlarmManager.setExact(AlarmManager.RTC, next.getTimeInMillis(), TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mController.getCustomStartTime();
            mEndTime = mController.getCustomEndTime();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = Calendar.getInstance();
            updateNextAlarm();
        }

        @Override
        public void onCustomStartTimeChanged(NightDisplayController.LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(NightDisplayController.LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            if (DEBUG) Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private class TwilightAutoMode extends AutoMode implements TwilightListener {

        private final TwilightManager mTwilightManager;

        private boolean mIsNight;

        public TwilightAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated() {
            final TwilightState state = mTwilightManager.getCurrentState();
            final boolean isNight = state != null && state.isNight();
            if (mIsNight != isNight) {
                mIsNight = isNight;

                if (mIsActivated == null || mIsActivated != isNight) {
                    mController.setActivated(isNight);
                }
            }
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
        }

        @Override
        public void onTwilightStateChanged() {
            if (DEBUG) Slog.d(TAG, "onTwilightStateChanged");
            updateActivated();
        }
    }
}
