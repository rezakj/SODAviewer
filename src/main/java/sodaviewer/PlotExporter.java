package sodaviewer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.JComponent;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** Export any plot component to PNG, JPG, or a single-page PDF (image embedded). */
public final class PlotExporter {
    public enum Format { PNG, JPG, PDF }

    private PlotExporter() {}

    public static Format fromExtension(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return Format.PNG;
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return Format.JPG;
        }
        if (n.endsWith(".pdf")) {
            return Format.PDF;
        }
        throw new IllegalArgumentException("Unsupported format: " + name);
    }

    public static void export(JComponent plot, Path file, Format format) throws IOException {
        export(plot, file, format, 2, -1, -1);
    }

    public static void export(JComponent plot, Path file, Format format, int scale) throws IOException {
        export(plot, file, format, scale, -1, -1);
    }

    /**
     * @param width  export width in pixels ({@code <=0} = component width)
     * @param height export height in pixels ({@code <=0} = component height)
     */
    public static void export(
            JComponent plot, Path file, Format format, int scale, int width, int height
    ) throws IOException {
        int w = width > 0 ? width : Math.max(400, plot.getWidth());
        int h = height > 0 ? height : Math.max(300, plot.getHeight());
        if (w < 10 || h < 10) {
            w = 900;
            h = 700;
        }
        if (scale < 1) {
            scale = 1;
        }
        java.awt.Dimension old = plot.getSize();
        boolean resized = width > 0 || height > 0;
        if (resized) {
            plot.setSize(w, h);
            plot.doLayout();
            plot.validate();
        }
        int type = format == Format.JPG ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage img = new BufferedImage(w * scale, h * scale, type);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(scale, scale);
            if (format == Format.JPG) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, w, h);
            }
            plot.putClientProperty("sodaviewer.exporting", Boolean.TRUE);
            try {
                plot.paint(g);
            } finally {
                plot.putClientProperty("sodaviewer.exporting", null);
            }
        } finally {
            g.dispose();
            if (resized) {
                plot.setSize(old);
                plot.doLayout();
                plot.validate();
                plot.repaint();
            }
        }
        Files.createDirectories(file.getParent() == null
                ? Path.of(".")
                : file.getParent());
        switch (format) {
            case PNG -> ImageIO.write(img, "png", file.toFile());
            case JPG -> writeJpeg(img, file, 0.92f);
            case PDF -> writePdfWithJpeg(img, file, w, h);
        }
    }

    private static void writeJpeg(BufferedImage img, Path file, float quality) throws IOException {
        // Ensure RGB (no alpha)
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ImageIO.write(rgb, "jpg", file.toFile());
            return;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** Minimal one-page PDF embedding a JPEG of the plot. */
    private static void writePdfWithJpeg(BufferedImage img, Path file, int pageW, int pageH)
            throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ByteArrayOutputStream jpegBuf = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", jpegBuf);
        byte[] jpeg = jpegBuf.toByteArray();
        int imgW = rgb.getWidth();
        int imgH = rgb.getHeight();

        // Page size in points ≈ on-screen CSS pixels
        int pw = pageW;
        int ph = pageH;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.List<Integer> offsets = new java.util.ArrayList<>();
        write(out, "%PDF-1.4\n");
        offsets.add(out.size());
        write(out, "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n");
        offsets.add(out.size());
        write(out, "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n");
        offsets.add(out.size());
        write(out, "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                + pw + " " + ph + "] /Contents 4 0 R /Resources << /XObject << /Im0 5 0 R >> >> >>endobj\n");
        String content = "q " + pw + " 0 0 " + ph + " 0 0 cm /Im0 Do Q\n";
        offsets.add(out.size());
        write(out, "4 0 obj<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length
                + " >>stream\n" + content + "endstream\nendobj\n");
        offsets.add(out.size());
        write(out, "5 0 obj<< /Type /XObject /Subtype /Image /Width " + imgW
                + " /Height " + imgH
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                + jpeg.length + " >>stream\n");
        out.write(jpeg);
        write(out, "\nendstream\nendobj\n");
        int xref = out.size();
        write(out, "xref\n0 6\n");
        write(out, "0000000000 65535 f \n");
        for (int i = 0; i < offsets.size(); i++) {
            write(out, String.format("%010d 00000 n \n", offsets.get(i)));
        }
        write(out, "trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        Files.write(file, out.toByteArray());
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
