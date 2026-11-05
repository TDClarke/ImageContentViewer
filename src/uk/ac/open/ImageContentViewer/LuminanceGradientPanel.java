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
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.ReadContentInputStream;

/**
 * "Luminance gradient" viewer. The luminance of the image is differentiated
 * (Sobel operator) to show how brightness changes across the image, which
 * reveals edges, splices and smoothed or cloned regions.
 *
 * Output colours (flat areas are mid grey):
 *   Red   = horizontal change (x gradient)
 *   Green = vertical change (y gradient)
 *
 * Sidebar controls:
 *   Equalise histogram - equalise the luminance before taking the gradient
 *   Normalise          - scale so the strongest edges use the full range
 *   Intensity 1.0-16.0 - extra gain applied to the gradient
 *   Opacity 0.0-1.0    - opacity of the gradient over the original image
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class LuminanceGradientPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(LuminanceGradientPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    private static final int DEFAULT_INTENSITY = 10;   // 1.0
    private static final int DEFAULT_OPACITY = 1000;   // 1.0

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
    private final JCheckBox equaliseCheck = new JCheckBox("Equalise histogram");
    private final JCheckBox normaliseCheck = new JCheckBox("Normalise", true);
    private final JSlider intensitySlider = new JSlider(10, 160, DEFAULT_INTENSITY);
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel intensityLabel = new JLabel();
    private final JLabel opacityLabel = new JLabel();
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel infoLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile BufferedImage source;      // TYPE_INT_ARGB
    private volatile GradientData gradientPlain; // cached gradients, per equalise setting
    private volatile GradientData gradientEqualised;
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<BufferedImage, Void> renderer;
    private final Timer renderTimer;

    public LuminanceGradientPanel() {
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

        JLabel help = new JLabel("<html>Shows how luminance changes across the image. "
                + "Flat areas are grey; red = horizontal change, green = vertical change.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        equaliseCheck.setAlignmentX(LEFT_ALIGNMENT);
        equaliseCheck.setToolTipText("Equalise the luminance histogram before taking the gradient");
        equaliseCheck.addActionListener(e -> renderTimer.restart());

        normaliseCheck.setAlignmentX(LEFT_ALIGNMENT);
        normaliseCheck.setToolTipText("Scale so the strongest edges fill the full range (intensity 1.0)");
        normaliseCheck.addActionListener(e -> renderTimer.restart());

        intensitySlider.setToolTipText("Gain applied to the gradient (1.0 - 16.0)");
        opacitySlider.setToolTipText("Opacity of the gradient over the original image");
        for (JSlider s : new JSlider[]{intensitySlider, opacitySlider}) {
            s.setAlignmentX(LEFT_ALIGNMENT);
            s.addChangeListener(e -> {
                updateLabels();
                renderTimer.restart();
            });
        }
        intensityLabel.setAlignmentX(LEFT_ALIGNMENT);
        opacityLabel.setAlignmentX(LEFT_ALIGNMENT);
        infoLabel.setAlignmentX(LEFT_ALIGNMENT);

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            equaliseCheck.setSelected(false);
            normaliseCheck.setSelected(true);
            intensitySlider.setValue(DEFAULT_INTENSITY);
            opacitySlider.setValue(DEFAULT_OPACITY);
            renderTimer.restart();
        });

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(equaliseCheck);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(normaliseCheck);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(intensityLabel);
        sidebar.add(intensitySlider);
        sidebar.add(Box.createVerticalStrut(8));
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

    private double intensity() {
        return intensitySlider.getValue() / 10.0;
    }

    private void updateLabels() {
        intensityLabel.setText(String.format("Intensity: %.1f", intensity()));
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
        equaliseCheck.setEnabled(enabled);
        normaliseCheck.setEnabled(enabled);
        intensitySlider.setEnabled(enabled);
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
                // Normalise to TYPE_INT_ARGB so we can work on the raw int[] directly.
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
        gradientPlain = null;
        gradientEqualised = null;
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    /** Render with the current controls on a background thread (latest request wins). */
    private void startRender() {
        if (source == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final BufferedImage src = source;
        final boolean equalise = equaliseCheck.isSelected();
        final boolean normalise = normaliseCheck.isSelected();
        final double intensity = intensity();
        final double opacity = opacitySlider.getValue() / 1000.0;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                int w = src.getWidth();
                int h = src.getHeight();
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

                GradientData gd = equalise ? gradientEqualised : gradientPlain;
                if (gd == null) {
                    gd = computeGradient(s, w, h, equalise, this::isCancelled);
                    if (gd == null) {
                        return null; // cancelled
                    }
                    if (source == src) { // don't cache results for a file that was replaced
                        if (equalise) {
                            gradientEqualised = gd;
                        } else {
                            gradientPlain = gd;
                        }
                    }
                }

                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] d = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
                render(s, d, w, h, gd, normalise, intensity, opacity, this::isCancelled);
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
                    logger.log(Level.WARNING, "Luminance gradient render failed", ex);
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    /** Sobel gradients of the luminance plus a robust "strong edge" level for normalising. */
    private static class GradientData {

        final short[] gx;
        final short[] gy;
        /** 99.5th percentile of max(|gx|,|gy|), in gradient units (raw Sobel / 4), at least 1. */
        final double strongEdge;

        GradientData(short[] gx, short[] gy, double strongEdge) {
            this.gx = gx;
            this.gy = gy;
            this.strongEdge = strongEdge;
        }
    }

    /** @return the gradients, or null if cancelled. */
    static GradientData computeGradient(int[] src, int w, int h, boolean equalise, BooleanSupplier cancelled) {
        final int n = w * h;
        int[] lum = new int[n];
        for (int i = 0; i < n; i++) {
            int p = src[i];
            lum[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        }

        if (equalise) {
            int[] hist = new int[256];
            for (int i = 0; i < n; i++) {
                hist[lum[i]]++;
            }
            int[] map = new int[256];
            int cdf = 0;
            int cdfMin = 0;
            for (int v = 0; v < 256; v++) {
                if (hist[v] > 0) {
                    cdfMin = hist[v];
                    break;
                }
            }
            int denom = n - cdfMin;
            for (int v = 0; v < 256; v++) {
                cdf += hist[v];
                map[v] = denom <= 0 ? v : (int) Math.round((double) Math.max(0, cdf - cdfMin) / denom * 255.0);
            }
            for (int i = 0; i < n; i++) {
                lum[i] = map[lum[i]];
            }
        }

        short[] gx = new short[n];
        short[] gy = new short[n];
        int[] bins = new int[256];

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            int rowM = Math.max(y - 1, 0) * w;
            int row = y * w;
            int rowP = Math.min(y + 1, h - 1) * w;
            for (int x = 0; x < w; x++) {
                int xm = x > 0 ? x - 1 : 0;
                int xp = x < w - 1 ? x + 1 : w - 1;

                int tl = lum[rowM + xm], tc = lum[rowM + x], tr = lum[rowM + xp];
                int ml = lum[row + xm], mr = lum[row + xp];
                int bl = lum[rowP + xm], bc = lum[rowP + x], br = lum[rowP + xp];

                int sx = (tr + 2 * mr + br) - (tl + 2 * ml + bl);
                int sy = (bl + 2 * bc + br) - (tl + 2 * tc + tr);
                gx[row + x] = (short) sx;
                gy[row + x] = (short) sy;

                int a = Math.max(Math.abs(sx), Math.abs(sy)) >> 2;
                bins[a > 255 ? 255 : a]++;
            }
        }

        long target = (long) Math.ceil(n * 0.995);
        long acc = 0;
        int p = 255;
        for (int v = 0; v < 256; v++) {
            acc += bins[v];
            if (acc >= target) {
                p = v;
                break;
            }
        }
        return new GradientData(gx, gy, Math.max(1, p));
    }

    /**
     * out = original * (1 - opacity) + gradientImage * opacity, where
     * gradientImage = (128 + k*gx, 128 + k*gy, 128) and G = Sobel / 4.
     * k = intensity, times 127 / strongEdge when normalising.
     */
    static void render(int[] src, int[] dst, int w, int h, GradientData gd, boolean normalise,
            double intensity, double opacity, BooleanSupplier cancelled) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;
        // k applies to raw Sobel values, hence the extra / 4; kept as 10-bit fixed point.
        double k = intensity * (normalise ? 127.0 / gd.strongEdge : 1.0) / 4.0;
        final int kFixed = (int) Math.round(k * 1024.0);
        final short[] gx = gd.gx;
        final short[] gy = gd.gy;
        final int blueBlend = (128 * opI);

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            int i = y * w;
            int end = i + w;
            for (; i < end; i++) {
                int p = src[i];
                int gr = 128 + ((gx[i] * kFixed + 512) >> 10);
                int gg = 128 + ((gy[i] * kFixed + 512) >> 10);
                gr = gr < 0 ? 0 : (gr > 255 ? 255 : gr);
                gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);

                int r = (((p >> 16) & 0xFF) * keep + gr * opI + 128) >> 8;
                int g = (((p >> 8) & 0xFF) * keep + gg * opI + 128) >> 8;
                int b = ((p & 0xFF) * keep + blueBlend + 128) >> 8;
                dst[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
