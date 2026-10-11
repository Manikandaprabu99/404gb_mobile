package com.fourzerofourgb.app;

import android.os.Bundle;
import android.webkit.CookieManager;
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
    public void onPause() {
        super.onPause();
        // WebView writes cookies to disk only every so often, so a sign-in made
        // just before the app is swiped away (or killed by Android) could be
        // lost, and the next launch would ask to sign in again.
        CookieManager.getInstance().flush();
    }
}
