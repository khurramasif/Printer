package com.example.rawprinter;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("printer_config", MODE_PRIVATE);
        
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("HP 1320 Print Service");
        title.setTextSize(22);
        layout.addView(title);

        EditText txtIp = new EditText(this);
        txtIp.setText(prefs.getString("ip", "192.168.1.1"));
        layout.addView(txtIp);

        Button btnSave = new Button(this);
        btnSave.setText("Save IP");
        btnSave.setOnClickListener(v -> {
            prefs.edit().putString("ip", txtIp.getText().toString().trim()).apply();
            Toast.makeText(this, "Saved! Open System Print Settings next.", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSave);

        Button btnEnable = new Button(this);
        btnEnable.setText("Open Android Print Settings");
        btnEnable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS)));
        layout.addView(btnEnable);

        scroll.addView(layout);
        setContentView(scroll);
    }
}
