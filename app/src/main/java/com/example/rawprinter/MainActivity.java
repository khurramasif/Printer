package com.example.rawprinter;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {
    private EditText txtIp, txtPort, txtApi;
    private CheckBox chkDuplex;
    private TextView lblStatus;
    private File localPrintFile = null;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("printer_prefs", MODE_PRIVATE);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView heading = new TextView(this);
        heading.setText("HP LaserJet 1320 Print Hub");
        heading.setTextSize(20);
        heading.setTextColor(0xFF0F172A);
        root.addView(heading);

        txtIp = new EditText(this);
        txtIp.setText(prefs.getString("ip", "192.168.1.1"));
        root.addView(txtIp);

        txtPort = new EditText(this);
        txtPort.setText(String.valueOf(prefs.getInt("port", 9100)));
        root.addView(txtPort);

        txtApi = new EditText(this);
        txtApi.setHint("ConvertAPI Secret Key");
        txtApi.setText(prefs.getString("apikey", ""));
        root.addView(txtApi);

        chkDuplex = new CheckBox(this);
        chkDuplex.setText("Double-Sided Printing (Duplex)");
        chkDuplex.setChecked(prefs.getBoolean("duplex", true));
        root.addView(chkDuplex);

        Button btnPrint = new Button(this);
        btnPrint.setText("Print to HP 1320");
        btnPrint.setOnClickListener(v -> {
            saveSettings();
            if (localPrintFile != null && localPrintFile.exists()) {
                new PrintEngineTask(txtIp.getText().toString(), Integer.parseInt(txtPort.getText().toString()), chkDuplex.isChecked(), localPrintFile).execute();
            } else {
                Toast.makeText(this, "Please share a file first.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnPrint);

        lblStatus = new TextView(this);
        lblStatus.setText("Status: Ready.");
        lblStatus.setPadding(0, 30, 0, 0);
        root.addView(lblStatus);

        scrollView.addView(root);
        setContentView(scrollView);
        handleIncomingData(getIntent());
    }

    private void saveSettings() {
        prefs.edit().putString("ip", txtIp.getText().toString()).putInt("port", Integer.parseInt(txtPort.getText().toString())).putString("apikey", txtApi.getText().toString().trim()).putBoolean("duplex", chkDuplex.isChecked()).apply();
    }

    @Override
    protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIncomingData(intent); }

    private void handleIncomingData(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            else if (intent.getClipData() != null) uri = intent.getClipData().getItemAt(0).getUri();
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) { uri = intent.getData(); }
        
        if (uri != null) {
            String mime = getContentResolver().getType(uri);
            String format = "raw";
            if (mime != null) {
                if (mime.contains("spreadsheet") || mime.contains("excel") || mime.contains("xls")) format = "xlsx";
                else if (mime.contains("word") || mime.contains("document") || mime.contains("doc")) format = "docx";
            }
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                File tempFile = new File(getCacheDir(), "shared_temp.dat");
                FileOutputStream out = new FileOutputStream(tempFile);
                byte[] buffer = new byte[32768]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.close(); in.close();

                if (format.equals("xlsx") || format.equals("docx")) {
                    String secret = txtApi.getText().toString().trim();
                    if (secret.isEmpty()) { lblStatus.setText("Error: ConvertAPI Secret Key missing!"); return; }
                    new CloudConvertTask(secret, format, tempFile).execute();
                } else {
                    localPrintFile = tempFile; lblStatus.setText("Local File (PDF/Image) ready to print.");
                }
            } catch (Exception e) { lblStatus.setText("File Error: " + e.getMessage()); }
        }
    }

    private class CloudConvertTask extends AsyncTask<Void, String, Boolean> {
        String secret, format; File sourceFile; String err = "";
        CloudConvertTask(String secret, String format, File sourceFile) { this.secret = secret; this.format = format; this.sourceFile = sourceFile; }
        @Override protected void onProgressUpdate(String... values) { lblStatus.setText(values[0]); }
        @Override protected Boolean doInBackground(Void... voids) {
            try {
                publishProgress("Uploading " + format.toUpperCase() + " for conversion...");
                OkHttpClient client = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
                RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("File", "document." + format, RequestBody.create(sourceFile, MediaType.parse("application/octet-stream"))).build();
                Request request = new Request.Builder().url("https://v2.convertapi.com/convert/" + format + "/to/pdf?Secret=" + secret).post(requestBody).build();
                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) throw new Exception("API Error " + response.code());
                publishProgress("Downloading converted PDF...");
                JSONObject obj = new JSONObject(response.body().string());
                byte[] pdfBytes = Base64.decode(obj.getJSONArray("Files").getJSONObject(0).getString("FileData"), Base64.DEFAULT);
                File resultFile = new File(getCacheDir(), "cloud_converted.pdf");
                FileOutputStream fos = new FileOutputStream(resultFile); fos.write(pdfBytes); fos.close();
                localPrintFile = resultFile; return true;
            } catch (Exception e) { err = e.getMessage(); return false; }
        }
        @Override protected void onPostExecute(Boolean success) { lblStatus.setText(success ? "Cloud conversion successful! Ready to Print." : "Cloud Error: " + err); }
    }

    private class PrintEngineTask extends AsyncTask<Void, String, Boolean> {
        String ip; int port; boolean duplex; File file;
        PrintEngineTask(String ip, int port, boolean duplex, File file) { this.ip = ip; this.port = port; this.duplex = duplex; this.file = file; }
        @Override protected void onProgressUpdate(String... values) { lblStatus.setText(values[0]); }
        @Override protected Boolean doInBackground(Void... voids) {
            try {
                publishProgress("Connecting to Printer...");
                Socket s = new Socket(); s.connect(new InetSocketAddress(ip, port), 5000);
                OutputStream out = s.getOutputStream();
                out.write(("\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE" + (duplex ? "\u001B&l1S" : "\u001B&l0S")).getBytes());
                publishProgress("Rendering to HP PCL...");
                if (isPdf(file)) {
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer = new PdfRenderer(pfd);
                    for (int i = 0; i < renderer.getPageCount(); i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        Bitmap bmp = Bitmap.createBitmap((page.getWidth() * 300) / 72, (page.getHeight() * 300) / 72, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(0xFFFFFFFF); page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        page.close(); out.write(convertToPcl(bmp)); bmp.recycle(); out.flush();
                    }
                    renderer.close(); pfd.close();
                } else {
                    Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bmp != null) { out.write(convertToPcl(bmp)); bmp.recycle(); out.flush(); }
                }
                out.write("\u001B*rB\u001BE\u001B%-12345X".getBytes()); out.flush(); s.close(); return true;
            } catch (Exception e) { return false; }
        }
        @Override protected void onPostExecute(Boolean success) { lblStatus.setText(success ? "Printed!" : "Print Failed."); }
    }
    private boolean isPdf(File f) { try (FileInputStream fis = new FileInputStream(f)) { byte[] h = new byte[4]; if (fis.read(h)==4) return h[0]==0x25 && h[1]==0x50; } catch (Exception e){} return false; }
    private byte[] convertToPcl(Bitmap bmp) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); int w = bmp.getWidth(), h = bmp.getHeight();
        bos.write("\u001B&l0E\u001B*t300R\u001B*r1A".getBytes());
        int rB = (w + 7) / 8; byte[] rBuf = new byte[rB]; int[] pix = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(pix, 0, w, 0, y, w, 1); for (int i = 0; i < rB; i++) rBuf[i] = 0;
            for (int x = 0; x < w; x++) { int c = pix[x]; if (((((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000) < 128) rBuf[x / 8] |= (1 << (7 - (x % 8))); }
            bos.write(("\u001B*b" + rB + "W").getBytes()); bos.write(rBuf);
        }
        bos.write("\u001B*rB\u000C".getBytes()); return bos.toByteArray();
    }
}
