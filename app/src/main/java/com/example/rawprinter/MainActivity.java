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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int PICK_FILE_REQUEST = 1001;
    private EditText txtIp, txtPort;
    private CheckBox chkDuplex;
    private TextView lblStatus;
    private File localPrintFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView heading = new TextView(this);
        heading.setText("HP LaserJet 1320 Print Hub");
        heading.setTextSize(20);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(0xFF0F172A);
        root.addView(heading);

        TextView lblIp = new TextView(this);
        lblIp.setText("Router / Print Server IP:");
        lblIp.setPadding(0, 30, 0, 8);
        root.addView(lblIp);

        txtIp = new EditText(this);
        txtIp.setText("192.168.1.1");
        root.addView(txtIp);

        TextView lblPort = new TextView(this);
        lblPort.setText("RAW Socket Port:");
        lblPort.setPadding(0, 16, 0, 8);
        root.addView(lblPort);

        txtPort = new EditText(this);
        txtPort.setText("9100");
        txtPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(txtPort);

        chkDuplex = new CheckBox(this);
        chkDuplex.setText("Double-Sided Printing (Duplex)");
        chkDuplex.setChecked(true);
        chkDuplex.setPadding(0, 20, 0, 20);
        root.addView(chkDuplex);

        Button btnPick = new Button(this);
        btnPick.setText("Choose File Manually");
        btnPick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE_REQUEST);
        });
        root.addView(btnPick);

        Button btnPrint = new Button(this);
        btnPrint.setText("Print to HP 1320");
        btnPrint.setOnClickListener(v -> {
            if (localPrintFile == null || !localPrintFile.exists() || localPrintFile.length() == 0) {
                Toast.makeText(this, "Please share or select a file first.", Toast.LENGTH_SHORT).show();
                return;
            }
            String ip = txtIp.getText().toString().trim();
            int port = 9100;
            try {
                port = Integer.parseInt(txtPort.getText().toString().trim());
            } catch (Exception ignored) {}
            boolean duplex = chkDuplex.isChecked();
            new PrintEngineTask(ip, port, duplex, localPrintFile).execute();
        });
        root.addView(btnPrint);

        lblStatus = new TextView(this);
        lblStatus.setText("Status: Ready. Share a document or choose a file.");
        lblStatus.setTextSize(14);
        lblStatus.setPadding(0, 30, 0, 10);
        lblStatus.setTextColor(0xFF0284C7);
        root.addView(lblStatus);

        scrollView.addView(root);
        setContentView(scrollView);

        handleIncomingData(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingData(intent);
    }

    private void handleIncomingData(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri targetUri = null;

        if (Intent.ACTION_SEND.equals(action)) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                targetUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            } else if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                targetUri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            targetUri = intent.getData();
        }

        if (targetUri != null) {
            copyStreamToPrivateCache(targetUri);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            copyStreamToPrivateCache(data.getData());
        }
    }

    private void copyStreamToPrivateCache(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                lblStatus.setText("Error: Android denied stream access.");
                return;
            }

            File target = new File(getCacheDir(), "active_print_job.dat");
            if (target.exists()) {
                target.delete();
            }

            FileOutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.close();
            in.close();

            localPrintFile = target;
            lblStatus.setText("File loaded successfully (" + (target.length() / 1024) + " KB). Ready to Print!");
            Toast.makeText(this, "Document loaded for HP 1320", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            lblStatus.setText("Error loading document: " + e.getMessage());
        }
    }

    private class PrintEngineTask extends AsyncTask<Void, String, Boolean> {
        private final String ip;
        private final int port;
        private final boolean duplex;
        private final File file;
        private String failReason = "";

        PrintEngineTask(String ip, int port, boolean duplex, File file) {
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
            Socket socket = null;
            try {
                publishProgress("Connecting to " + ip + ":" + port + "...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000);
                OutputStream socketOut = socket.getOutputStream();

                // Duplex commands: \u001B&l1S = Duplex (Long Edge), \u001B&l0S = Simplex
                String duplexCode = duplex ? "\u001B&l1S" : "\u001B&l0S";
                String pjlHeader = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE" + duplexCode;
                socketOut.write(pjlHeader.getBytes("US-ASCII"));

                boolean isPdf = isPdfFile(file);

                if (isPdf) {
                    publishProgress("Parsing PDF document...");
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer = new PdfRenderer(pfd);
                    int totalPages = renderer.getPageCount();

                    for (int i = 0; i < totalPages; i++) {
                        publishProgress("Printing page " + (i + 1) + " of " + totalPages + "...");
                        PdfRenderer.Page page = renderer.openPage(i);

                        // 300 DPI scaling
                        int renderWidth = (page.getWidth() * 300) / 72;
                        int renderHeight = (page.getHeight() * 300) / 72;

                        Bitmap bmp = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(0xFFFFFFFF);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        page.close();

                        byte[] pclPage = convertBitmapToPclRaster(bmp);
                        bmp.recycle();

                        socketOut.write(pclPage);
                        socketOut.flush();
                    }
                    renderer.close();
                    pfd.close();
                } else {
                    publishProgress("Rendering image...");
                    Bitmap imageBmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (imageBmp == null) {
                        throw new Exception("File format not recognized as PDF or standard image.");
                    }
                    byte[] pclImage = convertBitmapToPclRaster(imageBmp);
                    imageBmp.recycle();

                    socketOut.write(pclImage);
                    socketOut.flush();
                }

                // Universal PCL Flush and Reset sequence
                socketOut.write("\u001B*rB\u001BE\u001B%-12345X".getBytes("US-ASCII"));
                socketOut.flush();
                socket.close();
                return true;
            } catch (Exception ex) {
                failReason = ex.getMessage() != null ? ex.getMessage() : "Unknown print error";
                try {
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (Exception ignored) {}
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                lblStatus.setText("Print job sent successfully!");
                Toast.makeText(MainActivity.this, "Printing sent to HP 1320!", Toast.LENGTH_LONG).show();
            } else {
                lblStatus.setText("Print failed: " + failReason);
            }
        }
    }

    private static boolean isPdfFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            int read = fis.read(header);
            if (read == 4) {
                // PDF magic bytes are %PDF (0x25, 0x50, 0x44, 0x46)
                return header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static byte[] convertBitmapToPclRaster(Bitmap bmp) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int width = bmp.getWidth();
        int height = bmp.getHeight();

        // PCL setup: Top margin 0, Resolution 300 DPI, Start Raster at left margin
        bos.write("\u001B&l0E\u001B*t300R\u001B*r1A".getBytes("US-ASCII"));

        int rowBytes = (width + 7) / 8;
        byte[] rowBuffer = new byte[rowBytes];
        int[] pixelRow = new int[width];

        for (int y = 0; y < height; y++) {
            bmp.getPixels(pixelRow, 0, width, 0, y, width, 1);
            for (int i = 0; i < rowBytes; i++) rowBuffer[i] = 0;

            for (int x = 0; x < width; x++) {
                int pixel = pixelRow[x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // Luminance formula
                if ((r * 299 + g * 587 + b * 114) / 1000 < 128) {
                    rowBuffer[x / 8] |= (1 << (7 - (x % 8)));
                }
            }
            bos.write(("\u001B*b" + rowBytes + "W").getBytes("US-ASCII"));
            bos.write(rowBuffer);
        }
        // End raster graphics and eject page
        bos.write("\u001B*rB\u000C".getBytes("US-ASCII"));
        return bos.toByteArray();
    }
}
