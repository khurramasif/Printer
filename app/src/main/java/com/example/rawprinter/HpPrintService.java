package com.example.rawprinter;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class HpPrintService extends PrintService {

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new PrinterDiscoverySession() {
            @Override
            public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
                PrinterId printerId = generatePrinterId("hp_laserjet_1320");
                PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(printerId)
                        .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                        .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                        .addResolution(new PrintAttributes.Resolution("300dpi", "300 DPI", 300, 300), true)
                        .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                        .setDuplexModes(PrintAttributes.DUPLEX_MODE_LONG_EDGE | PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_LONG_EDGE)
                        .build();

                PrinterInfo printer = new PrinterInfo.Builder(printerId, "HP LaserJet 1320 (Network RAW)", PrinterInfo.STATUS_IDLE)
                        .setDescription("HP LaserJet 1320 on Router Port 9100")
                        .setCapabilities(caps)
                        .build();

                List<PrinterInfo> printers = new ArrayList<>();
                printers.add(printer);
                addPrinters(printers);
            }

            @Override public void onStopPrinterDiscovery() {}
            @Override public void onValidatePrinters(List<PrinterId> printerIds) {}
            @Override public void onStartPrinterStateTracking(PrinterId printerId) {}
            @Override public void onStopPrinterStateTracking(PrinterId printerId) {}
            @Override public void onDestroy() {}
        };
    }

    @Override
    protected void onPrintJobQueued(PrintJob printJob) {
        printJob.start();
        new Thread(() -> {
            SharedPreferences prefs = getSharedPreferences("printer_config", MODE_PRIVATE);
            String ip = prefs.getString("printer_ip", "192.168.1.1");
            int port = prefs.getInt("printer_port", 9100);

            PrintAttributes attrs = printJob.getInfo().getAttributes();
            boolean isDuplex = (attrs.getDuplexMode() & PrintAttributes.DUPLEX_MODE_LONG_EDGE) != 0;

            try {
                ParcelFileDescriptor pfd = printJob.getDocument().getData();
                if (pfd == null) {
                    printJob.fail("No document data received from Android.");
                    return;
                }

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000);
                OutputStream out = socket.getOutputStream();

                String duplexCmd = isDuplex ? "\u001B&l1S" : "\u001B&l0S";
                String initPcl = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BE" + duplexCmd;
                out.write(initPcl.getBytes("US-ASCII"));

                PdfRenderer renderer = new PdfRenderer(pfd);
                int total = renderer.getPageCount();

                for (int i = 0; i < total; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    int w = (page.getWidth() * 300) / 72;
                    int h = (page.getHeight() * 300) / 72;
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(0xFFFFFFFF);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    page.close();

                    byte[] pcl = convertToPcl(bmp);
                    bmp.recycle();

                    out.write(pcl);
                    out.flush();
                }

                out.write("\u001B*rB\u001BE\u001B%-12345X".getBytes("US-ASCII"));
                out.flush();

                renderer.close();
                pfd.close();
                socket.close();

                printJob.complete();
            } catch (Exception e) {
                printJob.fail(e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        printJob.cancel();
    }

    private static byte[] convertToPcl(Bitmap bmp) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        bos.write("\u001B&l0E\u001B*t300R\u001B*r1A".getBytes("US-ASCII"));

        int rowBytes = (w + 7) / 8;
        byte[] rowBuf = new byte[rowBytes];
        int[] pix = new int[w];

        for (int y = 0; y < h; y++) {
            bmp.getPixels(pix, 0, w, 0, y, w, 1);
            for (int i = 0; i < rowBytes; i++) rowBuf[i] = 0;

            for (int x = 0; x < w; x++) {
                int c = pix[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if ((r * 299 + g * 587 + b * 114) / 1000 < 128) {
                    rowBuf[x / 8] |= (1 << (7 - (x % 8)));
                }
            }
            bos.write(("\u001B*b" + rowBytes + "W").getBytes("US-ASCII"));
            bos.write(rowBuf);
        }
        bos.write("\u001B*rB\u000C".getBytes("US-ASCII"));
        return bos.toByteArray();
    }
}
