package com.fourzerofourgb.app;

import android.os.Bundle;
import com.fourzerofourgb.app.photowatcher.PhotoWatcherPlugin;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must register before super.onCreate(), which is where
        // BridgeActivity actually builds the Bridge from the accumulated
        // plugin list (this class's Bridge.Builder field is already
        // constructed by the time this method runs).
        registerPlugin(PhotoWatcherPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
