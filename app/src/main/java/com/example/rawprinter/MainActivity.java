package com.example.rawprinter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText txtIp, txtPort;
    private TextView lblStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("printer_config", MODE_PRIVATE);
        txtIp = findViewById(R.id.txtIp);
        txtPort = findViewById(R.id.txtPort);
        lblStatus = findViewById(R.id.lblStatus);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnOpenSettings = findViewById(R.id.btnOpenSystemSettings);

        String savedIp = prefs.getString("printer_ip", "192.168.1.1");
        int savedPort = prefs.getInt("printer_port", 9100);
        txtIp.setText(savedIp);
        txtPort.setText(String.valueOf(savedPort));

        btnSave.setOnClickListener(v -> {
            String ip = txtIp.getText().toString().trim();
            int port = Integer.parseInt(txtPort.getText().toString().trim());
            prefs.edit().putString("printer_ip", ip).putInt("printer_port", port).apply();
            lblStatus.setText("Settings Saved. HP 1320 ready for system printing.");
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
        });

        btnOpenSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Print Settings from Android Settings", Toast.LENGTH_LONG).show();
            }
        });
    }
}
