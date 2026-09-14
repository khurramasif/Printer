package com.example.rawprinter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 101;
    private EditText txtIp, txtPort;
    private CheckBox chkDuplex;
    private TextView lblStatus;
    private File cachedFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("HP LaserJet 1320 Print Hub");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF0F172A);
        layout.addView(title);

        TextView lblIp = new TextView(this);
        lblIp.setText("Router / Print Server IP:");
        lblIp.setPadding(0, 30, 0, 10);
        layout.addView(lblIp);

        txtIp = new EditText(this);
        txtIp.setText("192.168.1.1");
        layout.addView(txtIp);

        TextView lblPort = new TextView(this);
        lblPort.setText("Raw Socket Port:");
        lblPort.setPadding(0, 20, 0, 10);
        layout.addView(lblPort);

        txtPort = new EditText(this);
        txtPort.setText("9100");
        txtPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(txtPort);

        chkDuplex = new CheckBox(this);
        chkDuplex.setText("Double-Sided Printing (Duplex)");
        chkDuplex.setChecked(true);
        chkDuplex.setPadding(0, 20, 0, 20);
        layout.addView(chkDuplex);

        Button btnPick = new Button(this);
        btnPick.setText("Choose File Manually");
        btnPick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE);
        });
        layout.addView(btnPick);

        Button btnPrint = new Button(this);
        btnPrint.setText("Print to HP 1320");
        btnPrint.setOnClickListener(v -> {
            if (cachedFile == null || !cachedFile.exists()) {
                Toast.makeText(this, "Please select or share a document first.", Toast.LENGTH_SHORT).show();
                return;
            }
            String ip = txtIp.getText().toString().trim();
            int port = Integer.parseInt(txtPort.getText().toString().trim());
            boolean duplex = chkDuplex.isChecked();
            new PrintTask(ip, port, duplex, cachedFile).execute();
        });
        layout.addView(btnPrint);

        lblStatus = new TextView(this);
        lblStatus.setText("Status: Ready");
        lblStatus.setTextSize(15);
        lblStatus.setPadding(0, 30, 0, 10);
        lblStatus.setTextColor(0xFF0284C7);
        layout.addView(lblStatus);

        scroll.addView(layout);
        setContentView(scroll);

        handleIncomingUri(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingUri(intent);
    }

    private void handleIncomingUri(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();

        if (Intent.ACTION_SEND.equals(action)) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            } else if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                uri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }

        if (uri != null) {
            importFile(uri);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData());
        }
    }

    private void importFile(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                lblStatus.setText("Error: Cannot read shared file.");
                return;
            }
            cachedFile = new File(getCacheDir(), "job_to_print.tmp");
            FileOutputStream out = new FileOutputStream(cachedFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();

            lblStatus.setText("File ready! Tap 'Print to HP 1320'");
            Toast.makeText(this, "Document loaded successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            lblStatus.setText("Error caching file: " + e.getMessage());
        }
    }

    private class PrintTask extends AsyncTask<Void, String, Boolean> {
        private final String ip;
        private final int port;
        private final boolean duplex;
        private final File file;
        private String error = "";

        PrintTask(String ip, int port, boolean duplex, File file) {
            this.ip = ip;
            this.port = port;
            this.duplex = duplex;
            this.file = file;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            lblStatus.setText(values[0]);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            try {
                publishProgress("Connecting to " + ip + ":" + port + "...");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 4000);
                OutputStream out = socket.getOutputStream();

                String duplexCmd = duplex ? "\u001B&l1S" : "\u001B&l0S";
                String initPcl = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE" + duplexCmd;
                out.write(initPcl.getBytes("US-ASCII"));

                // Try opening as an image first
                Bitmap imageBmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (imageBmp != null) {
                    publishProgress("Rendering image...");
                    out.write(renderBitmapToPcl(imageBmp));
                    imageBmp.recycle();
                } else {
                    // If not an image, render as PDF
                    publishProgress("Rendering PDF pages...");
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer = new PdfRenderer(pfd);
                    int count = renderer.getPageCount();

                    for (int i = 0; i < count; i++) {
                        publishProgress("Printing page " + (i + 1) + " of " + count + "...");
                        PdfRenderer.Page page = renderer.openPage(i);
                        int w = (page.getWidth() * 300) / 72;
                        int h = (page.getHeight() * 300) / 72;
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(0xFFFFFFFF);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        page.close();

                        out.write(renderBitmapToPcl(bmp));
                        bmp.recycle();
                        out.flush();
                    }
                    renderer.close();
                    pfd.close();
                }

                out.write("\u001B*rB\u001BE\u001B%-12345X".getBytes("US-ASCII"));
                out.flush();
                socket.close();
                return true;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : "Print error";
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                lblStatus.setText("Print job sent successfully!");
            } else {
                lblStatus.setText("Error: " + error);
            }
        }
    }

    private static byte[] renderBitmapToPcl(Bitmap bmp) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        bos.write("\u001B&l0E\u001B*t300R\u001B*r1A".getBytes("US-ASCII"));

        int rowBytes = (w + 7) / 8;
        byte[] row = new byte[rowBytes];
        int[] pix = new int[w];

        for (int y = 0; y < h; y++) {
            bmp.getPixels(pix, 0, w, 0, y, w, 1);
            for (int i = 0; i < rowBytes; i++) row[i] = 0;

            for (int x = 0; x < w; x++) {
                int c = pix[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if ((r * 299 + g * 587 + b * 114) / 1000 < 128) {
                    row[x / 8] |= (1 << (7 - (x % 8)));
                }
            }
            bos.write(("\u001B*b" + rowBytes + "W").getBytes("US-ASCII"));
            bos.write(row);
        }
        bos.write("\u001B*rB\u000C".getBytes("US-ASCII"));
        return bos.toByteArray();
    }
}
