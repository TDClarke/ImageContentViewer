package uk.ac.open.ImageContentViewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.ReadContentInputStream;

/**
 * Error level analysis (ELA), implemented with the JDK's JPEG codec (no OpenCV).
 *
 * The image is re-saved as a JPEG at the chosen quality (in memory) and compared
 * with the original. Regions that were edited or saved at a different quality
 * tend to show a different error level from the rest of the image.
 *
 *   ela = |original - recompressed| * scale     (per channel, clamped to 255)
 *   ela = 0 where ela < squelch
 *   ela = 255 - ela                              if inverted
 *   out = original * (1 - opacity) + ela * opacity
 *
 * Sidebar controls: scale, quality, squelch, opacity, inverted.
 *
 * Note: in the original OpenCV code "opacity" was the weight of the original image
 * (addWeighted(original, opacity, ela, 1 - opacity)). Here, as in the other tabs,
 * opacity is the opacity of the ELA image over the original (0.0 = original only).
 * The default 0.50 gives the same 50/50 blend either way.
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class ErrorLevelAnalysisPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(ErrorLevelAnalysisPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    // defaults taken from the original ELA code
    private static final int DEFAULT_SCALE = 15;
    private static final int DEFAULT_QUALITY = 75;
    private static final int DEFAULT_SQUELCH = 10;
    private static final int DEFAULT_OPACITY = 500;     // 0.50
    private static final boolean DEFAULT_INVERTED = true;

    // toolbar
    private final JToolBar toolbar = new JToolBar();
    private final JTextField rotationTextField = new JTextField("0°");
    private final JButton rotateLeftButton = new JButton("\u21BA");
    private final JButton rotateRightButton = new JButton("\u21BB");
    private final JTextField zoomTextField = new JTextField("100%");
    private final JButton zoomOutButton = new JButton("\u2212");
    private final JButton zoomInButton = new JButton("+");
    private final JButton zoomResetButton = new JButton("Reset");

    // sidebar
    private final JSlider scaleSlider = new JSlider(1, 100, DEFAULT_SCALE);
    private final JSlider qualitySlider = new JSlider(1, 100, DEFAULT_QUALITY);
    private final JSlider squelchSlider = new JSlider(0, 100, DEFAULT_SQUELCH);
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel scaleLabel = new JLabel();
    private final JLabel qualityLabel = new JLabel();
    private final JLabel squelchLabel = new JLabel();
    private final JLabel opacityLabel = new JLabel();
    private final JCheckBox invertedCheck = new JCheckBox("Inverted", DEFAULT_INVERTED);
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel infoLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile BufferedImage source;          // TYPE_INT_ARGB, opaque
    private volatile Recompressed recompressed;     // single-slot cache keyed by quality
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<BufferedImage, Void> renderer;
    private final Timer renderTimer;

    public ErrorLevelAnalysisPanel() {
        setLayout(new BorderLayout());
        buildToolbar();
        buildSidebar();

        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Color.BLACK);
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                canvas.revalidate();
                updateToolbar();
            }
        });
        add(scrollPane, BorderLayout.CENTER);
        canvas.setOnChange(this::updateToolbar);

        renderTimer = new Timer(30, e -> startRender());
        renderTimer.setRepeats(false);

        updateLabels();
        setControlsEnabled(false);
        updateToolbar();
    }

    // ------------------------------------------------------------------ UI

    private void buildToolbar() {
        toolbar.setFloatable(false);
        toolbar.setRollover(true);

        for (JTextField f : new JTextField[]{rotationTextField, zoomTextField}) {
            f.setEditable(false);
            f.setHorizontalAlignment(SwingConstants.RIGHT);
            f.setPreferredSize(new Dimension(55, 22));
            f.setMinimumSize(new Dimension(55, 22));
            f.setMaximumSize(new Dimension(55, 22));
        }
        rotationTextField.setToolTipText("Current rotation");
        zoomTextField.setToolTipText("Current zoom");

        for (JButton b : new JButton[]{rotateLeftButton, rotateRightButton, zoomOutButton, zoomInButton}) {
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(0, 0, 0, 0));
            b.setPreferredSize(new Dimension(26, 24));
            b.setMinimumSize(new Dimension(26, 24));
            b.setMaximumSize(new Dimension(26, 24));
        }
        zoomResetButton.setFocusable(false);
        rotateLeftButton.setToolTipText("Rotate left");
        rotateRightButton.setToolTipText("Rotate right");
        zoomOutButton.setToolTipText("Zoom out");
        zoomInButton.setToolTipText("Zoom in");
        zoomResetButton.setToolTipText("Fit image to window and reset rotation");

        rotateLeftButton.addActionListener(e -> canvas.rotate(270));
        rotateRightButton.addActionListener(e -> canvas.rotate(90));
        zoomOutButton.addActionListener(e -> canvas.zoomOut());
        zoomInButton.addActionListener(e -> canvas.zoomIn());
        zoomResetButton.addActionListener(e -> canvas.resetView());

        toolbar.add(rotationTextField);
        toolbar.add(rotateLeftButton);
        toolbar.add(rotateRightButton);
        toolbar.addSeparator();
        toolbar.add(zoomTextField);
        toolbar.add(zoomOutButton);
        toolbar.add(zoomInButton);
        toolbar.add(zoomResetButton);
        add(toolbar, BorderLayout.NORTH);
    }

    private void buildSidebar() {
        ChannelImagePanel.SidebarPanel sidebar = new ChannelImagePanel.SidebarPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel help = new JLabel("<html>The image is re-saved as a JPEG and compared with the original. "
                + "Areas edited or saved at a different quality tend to show a different error level.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        scaleSlider.setToolTipText("Gain applied to the difference (1 - 100)");
        qualitySlider.setToolTipText("JPEG quality used for the re-save (1 - 100)");
        squelchSlider.setToolTipText("Differences below this level (after scaling) are set to zero");
        opacitySlider.setToolTipText("Opacity of the ELA image over the original image");

        // Changing the quality means re-compressing the whole image, so wait a little longer.
        qualitySlider.addChangeListener(e -> {
            updateLabels();
            scheduleRender(250);
        });
        for (JSlider s : new JSlider[]{scaleSlider, squelchSlider, opacitySlider}) {
            s.addChangeListener(e -> {
                updateLabels();
                scheduleRender(30);
            });
        }
        for (JSlider s : new JSlider[]{scaleSlider, qualitySlider, squelchSlider, opacitySlider}) {
            s.setAlignmentX(LEFT_ALIGNMENT);
        }
        for (JLabel l : new JLabel[]{scaleLabel, qualityLabel, squelchLabel, opacityLabel, infoLabel}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
        }

        invertedCheck.setAlignmentX(LEFT_ALIGNMENT);
        invertedCheck.setToolTipText("Invert the ELA image (small errors show as light)");
        invertedCheck.addActionListener(e -> scheduleRender(30));

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            scaleSlider.setValue(DEFAULT_SCALE);
            qualitySlider.setValue(DEFAULT_QUALITY);
            squelchSlider.setValue(DEFAULT_SQUELCH);
            opacitySlider.setValue(DEFAULT_OPACITY);
            invertedCheck.setSelected(DEFAULT_INVERTED);
            scheduleRender(30);
        });

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(scaleLabel);
        sidebar.add(scaleSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(qualityLabel);
        sidebar.add(qualitySlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(squelchLabel);
        sidebar.add(squelchSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(opacityLabel);
        sidebar.add(opacitySlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(invertedCheck);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(resetControlsButton);
        sidebar.add(Box.createVerticalStrut(16));
        sidebar.add(infoLabel);
        sidebar.add(Box.createVerticalGlue());

        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebarScroll.setPreferredSize(new Dimension(250, 100));
        add(sidebarScroll, BorderLayout.EAST);
    }

    private void scheduleRender(int delayMs) {
        renderTimer.setInitialDelay(delayMs);
        renderTimer.restart();
    }

    private void updateLabels() {
        scaleLabel.setText("Scale: " + scaleSlider.getValue());
        qualityLabel.setText("Quality: " + qualitySlider.getValue());
        squelchLabel.setText("Squelch: " + squelchSlider.getValue());
        opacityLabel.setText(String.format("Opacity: %.2f", opacitySlider.getValue() / 1000.0));
    }

    private void updateToolbar() {
        boolean has = canvas.hasImage();
        double z = canvas.effectiveZoom();
        rotationTextField.setText(canvas.getRotation() + "°");
        zoomTextField.setText(Math.round(z * 100.0) + "%");
        rotateLeftButton.setEnabled(has);
        rotateRightButton.setEnabled(has);
        zoomResetButton.setEnabled(has);
        zoomOutButton.setEnabled(has && z > MIN_ZOOM_RATIO);
        zoomInButton.setEnabled(has && z < MAX_ZOOM_RATIO);
    }

    private void setControlsEnabled(boolean enabled) {
        scaleSlider.setEnabled(enabled);
        qualitySlider.setEnabled(enabled);
        squelchSlider.setEnabled(enabled);
        opacitySlider.setEnabled(enabled);
        invertedCheck.setEnabled(enabled);
        resetControlsButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ API

    /** Load the given file's image in the background and display it. */
    public void setFile(AbstractFile file) {
        reset();
        infoLabel.setText("Loading...");
        loader = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                BufferedImage img;
                try (ReadContentInputStream in = new ReadContentInputStream(file)) {
                    img = ImageIO.read(in);
                }
                if (img == null) {
                    return null;
                }
                // Flatten onto white so the image is opaque (JPEG has no alpha channel).
                BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argb.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, argb.getWidth(), argb.getHeight());
                    g.drawImage(img, 0, 0, null);
                } finally {
                    g.dispose();
                }
                return argb;
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                BufferedImage img;
                try {
                    img = get();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Unable to read image " + file.getName(), ex);
                    img = null;
                }
                source = img;
                if (img == null) {
                    infoLabel.setText("Image could not be read");
                    canvas.setImage(null, true);
                    setControlsEnabled(false);
                } else {
                    infoLabel.setText("<html>" + img.getWidth() + " x " + img.getHeight() + " px</html>");
                    setControlsEnabled(true);
                    resetViewOnNextRender = true;
                    startRender();
                }
            }
        };
        loader.execute();
    }

    public void reset() {
        renderTimer.stop();
        if (loader != null) {
            loader.cancel(true);
            loader = null;
        }
        if (renderer != null) {
            renderer.cancel(true);
            renderer = null;
        }
        source = null;
        recompressed = null;
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    /** The image re-saved as a JPEG at a given quality, decoded back to RGB. */
    private static class Recompressed {

        final int quality;
        final int[] pixels;

        Recompressed(int quality, int[] pixels) {
            this.quality = quality;
            this.pixels = pixels;
        }
    }

    private void startRender() {
        if (source == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final BufferedImage src = source;
        final int scale = scaleSlider.getValue();
        final int quality = qualitySlider.getValue();
        final int squelch = squelchSlider.getValue();
        final double opacity = opacitySlider.getValue() / 1000.0;
        final boolean inverted = invertedCheck.isSelected();

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                int w = src.getWidth();
                int h = src.getHeight();
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

                Recompressed rc = recompressed;
                if (rc == null || rc.quality != quality) {
                    int[] px = recompress(src, quality);
                    if (isCancelled()) {
                        return null;
                    }
                    rc = new Recompressed(quality, px);
                    if (source == src) { // don't cache for a file that was replaced
                        recompressed = rc;
                    }
                }

                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] d = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
                render(s, rc.pixels, d, w, h, scale, squelch, inverted, opacity, this::isCancelled);
                return out;
            }

            @Override
            protected void done() {
                if (isCancelled() || source != src) {
                    return;
                }
                try {
                    BufferedImage out = get();
                    if (out != null) {
                        canvas.setImage(out, resetViewOnNextRender);
                        resetViewOnNextRender = false;
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Error level analysis failed", ex);
                    infoLabel.setText("Error level analysis failed");
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    /** Save the image as a JPEG at the given quality (1-100) in memory and decode it again. */
    static int[] recompress(BufferedImage argb, int quality) throws IOException {
        int w = argb.getWidth();
        int h = argb.getHeight();

        // The JPEG writer needs an image without an alpha channel.
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(argb, 0, 0, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        if (decoded == null || decoded.getWidth() != w || decoded.getHeight() != h) {
            throw new IOException("JPEG re-compression failed");
        }
        return decoded.getRGB(0, 0, w, h, null, 0, w);
    }

    /**
     * ela = min(255, |original - recompressed| * scale); ela = 0 if ela < squelch;
     * ela = 255 - ela if inverted; out = original * (1 - opacity) + ela * opacity.
     */
    static void render(int[] orig, int[] comp, int[] dst, int w, int h, int scale, int squelch,
            boolean inverted, double opacity, BooleanSupplier cancelled) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;

        // |difference| (0..255) -> ELA value
        int[] lut = new int[256];
        for (int d = 0; d < 256; d++) {
            int v = Math.min(255, d * scale);
            if (v < squelch) {
                v = 0;
            }
            lut[d] = inverted ? 255 - v : v;
        }

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                int p = orig[i];
                int q = comp[i];
                int er = lut[Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))];
                int eg = lut[Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))];
                int eb = lut[Math.abs((p & 0xFF) - (q & 0xFF))];
                int r = (((p >> 16) & 0xFF) * keep + er * opI + 128) >> 8;
                int g = (((p >> 8) & 0xFF) * keep + eg * opI + 128) >> 8;
                int b = ((p & 0xFF) * keep + eb * opI + 128) >> 8;
                dst[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
