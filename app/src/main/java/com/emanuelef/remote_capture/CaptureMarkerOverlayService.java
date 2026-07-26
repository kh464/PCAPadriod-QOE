/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

public class CaptureMarkerOverlayService extends Service {
    private static final String TAG = "CaptureMarkerOverlay";
    private static final String EXTRA_MARKER_URI = "marker_uri";

    private WindowManager mWindowManager;
    private View mOverlayView;
    private WindowManager.LayoutParams mLayoutParams;
    private Uri mMarkerUri;

    public static boolean canDrawOverlays(Context ctx) {
        return (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || Settings.canDrawOverlays(ctx);
    }

    public static Intent buildIntent(Context ctx, Uri markerUri) {
        Intent intent = new Intent(ctx, CaptureMarkerOverlayService.class);
        intent.putExtra(EXTRA_MARKER_URI, markerUri.toString());
        return intent;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (!canDrawOverlays(this)) {
            Log.w(TAG, "Missing overlay permission");
            stopSelf();
            return START_NOT_STICKY;
        }

        String markerUriString = (intent != null) ? intent.getStringExtra(EXTRA_MARKER_URI) : null;
        if (TextUtils.isEmpty(markerUriString)) {
            Log.w(TAG, "Missing marker URI");
            stopSelf();
            return START_NOT_STICKY;
        }

        mMarkerUri = Uri.parse(markerUriString);

        if (mOverlayView == null) {
            try {
                showOverlay();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not show marker overlay: " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void showOverlay() {
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(4), dp(4), dp(4), dp(4));

        Button openButton = buildButton(CaptureMarkerWriter.LABEL_OPEN);
        Button closeButton = buildButton(CaptureMarkerWriter.LABEL_CLOSE);
        layout.addView(openButton);
        layout.addView(closeButton);

        int windowType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.gravity = Gravity.TOP | Gravity.START;
        mLayoutParams.x = dp(12);
        mLayoutParams.y = dp(96);

        DragTouchListener dragTouchListener = new DragTouchListener();
        layout.setOnTouchListener(dragTouchListener);
        openButton.setOnTouchListener(dragTouchListener);
        closeButton.setOnTouchListener(dragTouchListener);

        mWindowManager.addView(layout, mLayoutParams);
        mOverlayView = layout;
    }

    private Button buildButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinWidth(dp(48));
        button.setMinHeight(dp(40));
        button.setOnClickListener(v -> appendMarker(label));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);

        return button;
    }

    private void appendMarker(String label) {
        if (!CaptureMarkerWriter.appendMarker(this, mMarkerUri, System.currentTimeMillis(), label))
            Utils.showToast(this, R.string.marker_write_failed);
    }

    private void removeOverlay() {
        if ((mWindowManager != null) && (mOverlayView != null)) {
            try {
                mWindowManager.removeView(mOverlayView);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove marker overlay: " + e.getMessage());
            }
        }

        mOverlayView = null;
        mLayoutParams = null;
        mWindowManager = null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class DragTouchListener implements View.OnTouchListener {
        private int mInitialX;
        private int mInitialY;
        private float mInitialTouchX;
        private float mInitialTouchY;
        private boolean mDragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mInitialX = mLayoutParams.x;
                    mInitialY = mLayoutParams.y;
                    mInitialTouchX = event.getRawX();
                    mInitialTouchY = event.getRawY();
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - mInitialTouchX);
                    int dy = (int) (event.getRawY() - mInitialTouchY);

                    if ((Math.abs(dx) > dp(4)) || (Math.abs(dy) > dp(4)))
                        mDragging = true;

                    if (mDragging) {
                        mLayoutParams.x = mInitialX + dx;
                        mLayoutParams.y = mInitialY + dy;
                        mWindowManager.updateViewLayout(view, mLayoutParams);
                    }

                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mDragging)
                        view.performClick();
                    return true;
                default:
                    return false;
            }
        }
    }
}
