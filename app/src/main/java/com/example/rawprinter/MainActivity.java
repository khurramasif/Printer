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
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 101;
    private EditText txtIp, txtPort;
    private CheckBox chkDuplex;
    private TextView lblStatus;
    private Uri targetUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Dynamic, robust UI layout (zero XML ID dependencies)
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
            if (targetUri == null) {
                Toast.makeText(this, "Please select or share a document first.", Toast.LENGTH_SHORT).show();
                return;
            }
            String ip = txtIp.getText().toString().trim();
            int port = Integer.parseInt(txtPort.getText().toString().trim());
            boolean duplex = chkDuplex.isChecked();
            new PrintTask(ip, port, duplex, targetUri).execute();
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

        processIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processIntent(intent);
    }

    private void processIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;

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
            targetUri = uri;
            lblStatus.setText("File loaded! Tap 'Print to HP 1320'");
            Toast.makeText(this, "Document ready to print", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            targetUri = data.getData();
            lblStatus.setText("File selected! Tap 'Print to HP 1320'");
        }
    }

    private class PrintTask extends AsyncTask<Void, String, Boolean> {
        private final String ip;
        private final int port;
        private final boolean duplex;
        private final Uri uri;
        private String error = "";

        PrintTask(String ip, int port, boolean duplex, Uri uri) {
            this.ip = ip;
            this.port = port;
            this.duplex = duplex;
            this.uri = uri;
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

                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "";

                if (mime.startsWith("image/") || uri.toString().matches("(?i).*\\.(png|jpg|jpeg|webp)$")) {
                    publishProgress("Rendering image...");
                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();
                    if (bmp != null) {
                        out.write(renderBitmapToPcl(bmp));
                        bmp.recycle();
                    }
                } else {
                    publishProgress("Rendering document pages...");
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd != null) {
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
                }

                out.write("\u001B*rB\u001BE\u001B%-12345X".getBytes("US-ASCII"));
                out.flush();
                socket.close();
                return true;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : "Unknown print error";
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
