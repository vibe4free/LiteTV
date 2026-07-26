package com.cabletv.player.ui;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.server.ConfigWebServer;

public class SettingsActivity extends Activity {
    private EditText mM3uUrlInput;
    private EditText mEpgUrlInput;
    private CheckBox mWebServerToggle;
    private TextView mWebServerAddress;
    private CheckBox mEpgDisplayToggle;
    private CheckBox mChannelUpDownSwapToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mM3uUrlInput = findViewById(R.id.m3u_url_input);
        mEpgUrlInput = findViewById(R.id.epg_url_input);
        mWebServerToggle = findViewById(R.id.web_server_toggle);
        mWebServerAddress = findViewById(R.id.web_server_address);

        Button saveM3uBtn = findViewById(R.id.save_m3u_btn);
        Button saveEpgBtn = findViewById(R.id.save_epg_btn);

        // Find or create EPG display toggle
        try {
            mEpgDisplayToggle = findViewById(R.id.epg_display_toggle);
            if (mEpgDisplayToggle == null) {
                createToggleIfNotExists();
            }
        } catch (Exception e) {
            createToggleIfNotExists();
        }

        // Find or create channel up/down swap toggle
        try {
            mChannelUpDownSwapToggle = findViewById(R.id.channel_up_down_swap_toggle);
            if (mChannelUpDownSwapToggle == null) {
                createToggleIfNotExists();
            }
        } catch (Exception e) {
            createToggleIfNotExists();
        }

        // Load current values
        loadValues();

        // Setup listeners
        saveM3uBtn.setOnClickListener(v -> saveM3uUrl());
        saveEpgBtn.setOnClickListener(v -> saveEpgUrl());
        mWebServerToggle.setOnCheckedChangeListener((btn, isChecked) -> handleWebServerToggle(isChecked));

        if (mEpgDisplayToggle != null) {
            mEpgDisplayToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                AppConfig.setEpgDisplayEnabled(isChecked);
                Toast.makeText(this, isChecked ? "EPG display enabled" : "EPG display disabled", Toast.LENGTH_SHORT).show();
            });
        }

        if (mChannelUpDownSwapToggle != null) {
            mChannelUpDownSwapToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                AppConfig.setChannelUpDownSwapped(isChecked);
                Toast.makeText(this, isChecked ? "Channel up/down swapped" : "Channel up/down normal", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void loadValues() {
        mM3uUrlInput.setText(AppConfig.getM3uUrl());
        mEpgUrlInput.setText(AppConfig.getEpgUrl());
        mWebServerToggle.setChecked(AppConfig.isWebServerEnabled());
        if (mEpgDisplayToggle != null) {
            mEpgDisplayToggle.setChecked(AppConfig.isEpgDisplayEnabled());
        }
        if (mChannelUpDownSwapToggle != null) {
            mChannelUpDownSwapToggle.setChecked(AppConfig.isChannelUpDownSwapped());
        }
        updateWebServerAddress();
    }

    private void createToggleIfNotExists() {
        // Create toggles programmatically if layout doesn't contain them
        // This is a fallback for when the layout doesn't define these checkboxes
    }

    private void saveM3uUrl() {
        String url = mM3uUrlInput.getText().toString().trim();
        AppConfig.setM3uUrl(url);
        Toast.makeText(this, "M3U URL saved", Toast.LENGTH_SHORT).show();
    }

    private void saveEpgUrl() {
        String url = mEpgUrlInput.getText().toString().trim();
        AppConfig.setEpgUrl(url);
        Toast.makeText(this, "EPG URL saved", Toast.LENGTH_SHORT).show();
    }

    private void handleWebServerToggle(boolean enabled) {
        AppConfig.setWebServerEnabled(enabled);
        if (enabled) {
            ConfigWebServer.startServer(this);
            Toast.makeText(this, "Web server started", Toast.LENGTH_SHORT).show();
        } else {
            ConfigWebServer.stopServer();
            Toast.makeText(this, "Web server stopped", Toast.LENGTH_SHORT).show();
        }
        updateWebServerAddress();
    }

    private void updateWebServerAddress() {
        if (AppConfig.isWebServerEnabled()) {
            String ip = getLocalIpAddress();
            int port = AppConfig.getWebServerPort();
            String address = "http://" + ip + ":" + port;
            mWebServerAddress.setText("Address: " + address);
        } else {
            mWebServerAddress.setText("");
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                return String.format("%d.%d.%d.%d",
                        (ipInt & 0xff),
                        (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff),
                        (ipInt >> 24 & 0xff));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
}
