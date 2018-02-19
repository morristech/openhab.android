/*
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.core;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import org.openhab.habdroid.R;
import org.openhab.habdroid.core.connection.Connection;
import org.openhab.habdroid.core.connection.ConnectionFactory;
import org.openhab.habdroid.core.connection.exception.ConnectionException;
import org.openhab.habdroid.util.MyHttpClient;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Headers;

/**
 * This service handles voice commands and sends them to OpenHAB.
 * It will use the openHAB base URL if passed in the intent's extra.
 */
public class OpenHABVoiceService extends Service {
    private static final String TAG = OpenHABVoiceService.class.getSimpleName();

    private final List<Pair<String, Integer>> mPendingCommands = new ArrayList<>();
    private boolean mListenerRegistered;
    private final BroadcastReceiver mConnectionChangeListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Connection conn = ConnectionFactory.getUsableConnection();
                for (Pair<String, Integer> entry : mPendingCommands) {
                    sendItemCommand("VoiceCommand", entry.first, conn, entry.second);
                }
                mPendingCommands.clear();
                context.unregisterReceiver(this);
                mListenerRegistered = false;
            } catch (ConnectionException e) {
                Log.w(TAG, "Couldn't determine OpenHAB URL", e);
                showToast(getString(R.string.error_couldnt_determine_openhab_url));
            }
        }
    };

    public OpenHABVoiceService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        final String voiceCommand = extractVoiceCommand(intent);
        boolean hasSentCommand = false;
        if (!voiceCommand.isEmpty()) {
            try {
                Connection conn = ConnectionFactory.getUsableConnection();
                if (conn != null) {
                    sendItemCommand("VoiceCommand", voiceCommand, conn, startId);
                } else {
                    mPendingCommands.add(Pair.create(voiceCommand, startId));
                    if (!mListenerRegistered) {
                        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
                        lbm.registerReceiver(mConnectionChangeListener,
                                new IntentFilter(ConnectionFactory.ACTION_NETWORK_CHANGED));
                        mListenerRegistered = true;
                    }
                }
                hasSentCommand = true;
            } catch (ConnectionException e) {
                Log.w(TAG, "Couldn't determine OpenHAB URL", e);
                showToast(getString(R.string.error_couldnt_determine_openhab_url));
            }
        }
        if (!hasSentCommand) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String extractVoiceCommand(Intent data) {
        String voiceCommand = "";
        List<String> textMatchList = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (!textMatchList.isEmpty()) {
            voiceCommand = textMatchList.get(0);
        }
        Log.i(TAG, "Recognized text: " + voiceCommand);
        showToast(getString(R.string.info_voice_recognized_text, voiceCommand));
        return voiceCommand;
    }


    private void sendItemCommand(final String itemName, final String command,
                                 final Connection conn, final int startId) {
        Log.d(TAG, "sendItemCommand(): itemName=" + itemName + ", command=" + command);
        conn.getAsyncHttpClient().post("/rest/items/" + itemName,
                command, "text/plain;charset=UTF-8", new MyHttpClient.ResponseHandler() {
                    @Override
                    public void onSuccess(Call call, int statusCode, Headers headers, byte[] responseBody) {
                        Log.d(TAG, "Command was sent successfully");
                        stopSelf(startId);
                    }

                    @Override
                    public void onFailure(Call call, int statusCode, Headers headers, byte[] responseBody, Throwable error) {
                        Log.e(TAG, "Got command error " + statusCode, error);
                        stopSelf(startId);
                    }
                });
    }

    /**
     * Displays the given message as a toast
     *
     * @param message The message to be displayed.
     */
    private void showToast(final String message) {
        // Display toast on main looper because OpenHABVoiceService might be destroyed
        // before to toast has finished displaying
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(OpenHABVoiceService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
