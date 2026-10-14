package com.fourzerofourgb.app;

import android.os.Bundle;
import com.fourzerofourgb.app.backup.AppVisibility;
import com.fourzerofourgb.app.backup.BackupPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must register before super.onCreate(), which is where
        // BridgeActivity actually builds the Bridge from the accumulated
        // plugin list (this class's Bridge.Builder field is already
        // constructed by the time this method runs).
        registerPlugin(BackupPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        // First, so a background People run starts stepping aside right away
        // (and a queued one is cancelled: the app's own scanner takes over).
        AppVisibility.onStart(getApplicationContext());
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        // Queues a People run about 90 s from now, so matching goes on with the app closed.
        AppVisibility.onStop(getApplicationContext());
    }
}
