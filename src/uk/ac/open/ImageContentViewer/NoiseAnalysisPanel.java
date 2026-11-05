package uk.ac.open.ImageContentViewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import javax.imageio.ImageIO;
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
 * Noise analysis. The noise of each colour channel is estimated as the difference
 * between the image and a denoised copy of it (3x3 median filter):
 *
 *     noise = pixel - median3x3(pixel)
 *
 * and shown as  128 + amplitude * noise  (mid grey = no noise), so regions with
 * different noise levels - for example pasted, retouched or resized areas -
 * show up with a different "texture" from the rest of the image.
 *
 * Sidebar controls:
 *   Noise amplitude 1-100  gain applied to the noise
 *   Equalise histogram     equalise each channel of the amplified noise image
 *   Opacity 0.0-1.0        opacity of the noise image over the original
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class NoiseAnalysisPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(NoiseAnalysisPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    private static final int DEFAULT_AMPLITUDE = 20;
    private static final int DEFAULT_OPACITY = 1000;

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
    private final JSlider amplitudeSlider = new JSlider(1, 100, DEFAULT_AMPLITUDE);
    private final JLabel amplitudeLabel = new JLabel();
    private final JCheckBox equaliseCheck = new JCheckBox("Equalise histogram");
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel opacityLabel = new JLabel();
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel infoLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile BufferedImage source;   // TYPE_INT_ARGB
    private volatile NoiseData noise;        // cached, independent of the controls
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<BufferedImage, Void> renderer;
    private final Timer renderTimer;

    public NoiseAnalysisPanel() {
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
        MagnifierControls.addTo(toolbar, canvas);
        add(toolbar, BorderLayout.NORTH);
    }

    private void buildSidebar() {
        ChannelImagePanel.SidebarPanel sidebar = new ChannelImagePanel.SidebarPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel help = new JLabel("<html>Shows the noise left after a 3x3 median filter is removed from the image. "
                + "Areas with a different noise texture may have come from another source or been altered.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        amplitudeSlider.setAlignmentX(LEFT_ALIGNMENT);
        amplitudeSlider.setToolTipText("Gain applied to the noise (1 - 100)");
        amplitudeSlider.addChangeListener(e -> {
            updateLabels();
            renderTimer.restart();
        });
        amplitudeLabel.setAlignmentX(LEFT_ALIGNMENT);

        equaliseCheck.setAlignmentX(LEFT_ALIGNMENT);
        equaliseCheck.setToolTipText("Equalise the histogram of each channel of the noise image");
        equaliseCheck.addActionListener(e -> renderTimer.restart());

        opacitySlider.setAlignmentX(LEFT_ALIGNMENT);
        opacitySlider.setToolTipText("Opacity of the noise image over the original image");
        opacitySlider.addChangeListener(e -> {
            updateLabels();
            renderTimer.restart();
        });
        opacityLabel.setAlignmentX(LEFT_ALIGNMENT);

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            amplitudeSlider.setValue(DEFAULT_AMPLITUDE);
            equaliseCheck.setSelected(false);
            opacitySlider.setValue(DEFAULT_OPACITY);
            renderTimer.restart();
        });

        infoLabel.setAlignmentX(LEFT_ALIGNMENT);

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(amplitudeLabel);
        sidebar.add(amplitudeSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(equaliseCheck);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(opacityLabel);
        sidebar.add(opacitySlider);
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

    private void updateLabels() {
        amplitudeLabel.setText("Noise amplitude: " + amplitudeSlider.getValue());
        opacityLabel.setText(String.format("Opacity: %.3f", opacitySlider.getValue() / 1000.0));
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
        amplitudeSlider.setEnabled(enabled);
        equaliseCheck.setEnabled(enabled);
        opacitySlider.setEnabled(enabled);
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
                BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argb.createGraphics();
                try {
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
        noise = null;
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    private void startRender() {
        if (source == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final BufferedImage src = source;
        final int amplitude = amplitudeSlider.getValue();
        final boolean equalise = equaliseCheck.isSelected();
        final double opacity = opacitySlider.getValue() / 1000.0;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                int w = src.getWidth();
                int h = src.getHeight();
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

                NoiseData nd = noise;
                if (nd == null) {
                    nd = computeNoise(s, w, h, this::isCancelled);
                    if (nd == null) {
                        return null; // cancelled
                    }
                    if (source == src) { // don't cache for a file that was replaced
                        noise = nd;
                    }
                }

                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] d = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
                render(s, d, w, h, nd, amplitude, equalise, opacity, this::isCancelled);
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
                    logger.log(Level.WARNING, "Noise analysis render failed", ex);
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    /** Per-channel noise (pixel - 3x3 median, clamped to -128..127) and its histogram. */
    private static class NoiseData {

        final byte[][] noise;   // [0]=R, [1]=G, [2]=B
        final int[][] hist;     // [channel][noise + 128]

        NoiseData(byte[][] noise, int[][] hist) {
            this.noise = noise;
            this.hist = hist;
        }
    }

    /** @return the noise, or null if cancelled. */
    static NoiseData computeNoise(int[] src, int w, int h, BooleanSupplier cancelled) {
        final int n = w * h;
        byte[][] noise = new byte[3][n];
        int[][] hist = new int[3][256];
        int[] wr = new int[9];
        int[] wg = new int[9];
        int[] wb = new int[9];
        int[] rows = new int[3];
        int[] cols = new int[3];

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            int rowM = Math.max(y - 1, 0) * w;
            int row = y * w;
            int rowP = Math.min(y + 1, h - 1) * w;
            rows[0] = rowM;
            rows[1] = row;
            rows[2] = rowP;
            for (int x = 0; x < w; x++) {
                int xm = x > 0 ? x - 1 : 0;
                int xp = x < w - 1 ? x + 1 : w - 1;
                int k = 0;
                cols[0] = xm;
                cols[1] = x;
                cols[2] = xp;
                for (int ry : rows) {
                    for (int cx : cols) {
                        int p = src[ry + cx];
                        wr[k] = (p >> 16) & 0xFF;
                        wg[k] = (p >> 8) & 0xFF;
                        wb[k] = p & 0xFF;
                        k++;
                    }
                }
                int p = src[row + x];
                int i = row + x;
                int nr = clampNoise(((p >> 16) & 0xFF) - median9(wr));
                int ng = clampNoise(((p >> 8) & 0xFF) - median9(wg));
                int nb = clampNoise((p & 0xFF) - median9(wb));
                noise[0][i] = (byte) nr;
                noise[1][i] = (byte) ng;
                noise[2][i] = (byte) nb;
                hist[0][nr + 128]++;
                hist[1][ng + 128]++;
                hist[2][nb + 128]++;
            }
        }
        return new NoiseData(noise, hist);
    }

    private static int clampNoise(int v) {
        return v < -128 ? -128 : (v > 127 ? 127 : v);
    }

    /** Median of nine values (insertion sort; sorts the array in place). */
    private static int median9(int[] a) {
        for (int i = 1; i < 9; i++) {
            int v = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
        return a[4];
    }

    /**
     * Noise image value = clamp(128 + amplitude * noise), optionally equalised per channel,
     * then blended: out = original * (1 - opacity) + noiseImage * opacity.
     */
    static void render(int[] src, int[] dst, int w, int h, NoiseData nd, int amplitude, boolean equalise,
            double opacity, BooleanSupplier cancelled) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;

        // One look-up table per channel: noise (+128) -> final noise-image value.
        int[][] lut = new int[3][256];
        for (int c = 0; c < 3; c++) {
            int[] amp = new int[256];
            for (int idx = 0; idx < 256; idx++) {
                int v = 128 + (idx - 128) * amplitude;
                amp[idx] = v < 0 ? 0 : (v > 255 ? 255 : v);
            }
            if (!equalise) {
                lut[c] = amp;
                continue;
            }
            // histogram of the amplified values, then the equalisation map
            long[] ah = new long[256];
            for (int idx = 0; idx < 256; idx++) {
                ah[amp[idx]] += nd.hist[c][idx];
            }
            long total = (long) w * h;
            long cdf = 0;
            long cdfMin = -1;
            int[] eq = new int[256];
            for (int v = 0; v < 256; v++) {
                cdf += ah[v];
                if (cdfMin < 0 && ah[v] > 0) {
                    cdfMin = cdf;
                }
                long denom = total - cdfMin;
                eq[v] = denom <= 0 ? 128 : (int) Math.round(255.0 * Math.max(0, cdf - cdfMin) / denom);
            }
            for (int idx = 0; idx < 256; idx++) {
                lut[c][idx] = eq[amp[idx]];
            }
        }

        final byte[] nr = nd.noise[0];
        final byte[] ng = nd.noise[1];
        final byte[] nb = nd.noise[2];
        final int[] lr = lut[0];
        final int[] lg = lut[1];
        final int[] lb = lut[2];

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                int p = src[i];
                int vr = lr[nr[i] + 128];
                int vg = lg[ng[i] + 128];
                int vb = lb[nb[i] + 128];
                int r = (((p >> 16) & 0xFF) * keep + vr * opI + 128) >> 8;
                int g = (((p >> 8) & 0xFF) * keep + vg * opI + 128) >> 8;
                int b = ((p & 0xFF) * keep + vb * opI + 128) >> 8;
                dst[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
