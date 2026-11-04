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
import javax.swing.JComboBox;
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
 * Principal Component Analysis viewer.
 *
 * Every pixel is turned into a 3-D feature vector f:
 *   Colour             f = (R, G, B)
 *   Luminance gradient f = (dL/dx, dL/dy, L), each standardised
 * PCA finds the mean m and the three principal directions e1, e2, e3 (largest
 * variance first). For the chosen component k, with c = (f - m) . e_k,
 * p = m + c * e_k (the pixel projected onto that component's axis) and d = f - p:
 *   Projection  p        (colour image)
 *   Difference  d        (colour image, grey = 0)
 *   Distance    |d|      (grey image)
 *   Component   c        (grey image, mid grey = 0)
 *
 * Linearize converts sRGB to linear light before the analysis. Invert flips the
 * result. Enhancement is applied to the result (per channel) before it is
 * blended with the original at the chosen opacity.
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class PcaPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(PcaPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;
    private static final int DEFAULT_OPACITY = 1000;

    // combo indices
    private static final int INPUT_COLOUR = 0;
    private static final int INPUT_GRADIENT = 1;
    private static final int MODE_PROJECTION = 0;
    private static final int MODE_DIFFERENCE = 1;
    private static final int MODE_DISTANCE = 2;
    private static final int MODE_COMPONENT = 3;
    private static final int ENH_NONE = 0;
    private static final int ENH_STRETCH = 1;
    private static final int ENH_EQUALISE = 2;

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
    private final JComboBox<String> inputCombo = new JComboBox<>(new String[]{"Colour", "Luminance gradient"});
    private final JComboBox<String> modeCombo
            = new JComboBox<>(new String[]{"Projection", "Difference", "Distance", "Component"});
    private final JComboBox<String> componentCombo = new JComboBox<>(new String[]{"1", "2", "3"});
    private final JCheckBox linearizeCheck = new JCheckBox("Linearize");
    private final JCheckBox invertCheck = new JCheckBox("Invert");
    private final JComboBox<String> enhancementCombo
            = new JComboBox<>(new String[]{"None", "Stretch contrast", "Equalise histogram"});
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel opacityLabel = new JLabel();
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel infoLabel = new JLabel(" ");
    private final JLabel varianceLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile BufferedImage source;   // TYPE_INT_ARGB
    private volatile Prepared prepared;      // single-slot cache of the PCA basis
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<RenderResult, Void> renderer;
    private final Timer renderTimer;

    public PcaPanel() {
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

        applyDefaults();
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

    private JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private void prepCombo(JComboBox<String> c, String tooltip) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        c.setToolTipText(tooltip);
        c.addActionListener(e -> renderTimer.restart());
    }

    private void buildSidebar() {
        ChannelImagePanel.SidebarPanel sidebar = new ChannelImagePanel.SidebarPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel help = new JLabel("<html>Principal component analysis of the pixel values. "
                + "Component 1 holds the most variance, component 3 the least.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        prepCombo(inputCombo, "Colour: analyse (R, G, B). Luminance gradient: analyse (dL/dx, dL/dy, L).");
        prepCombo(modeCombo, "<html>Projection: pixel projected onto the component<br>"
                + "Difference: pixel minus its projection<br>"
                + "Distance: length of the difference<br>"
                + "Component: pixel's value along the component</html>");
        prepCombo(componentCombo, "Principal component to use (1 = most variance)");
        prepCombo(enhancementCombo, "Enhancement applied to the result before blending");

        linearizeCheck.setAlignmentX(LEFT_ALIGNMENT);
        linearizeCheck.setToolTipText("Convert sRGB to linear light before the analysis");
        linearizeCheck.addActionListener(e -> renderTimer.restart());
        invertCheck.setAlignmentX(LEFT_ALIGNMENT);
        invertCheck.setToolTipText("Invert the result");
        invertCheck.addActionListener(e -> renderTimer.restart());

        opacitySlider.setAlignmentX(LEFT_ALIGNMENT);
        opacitySlider.setToolTipText("Opacity of the result over the original image");
        opacitySlider.addChangeListener(e -> {
            updateLabels();
            renderTimer.restart();
        });
        opacityLabel.setAlignmentX(LEFT_ALIGNMENT);
        infoLabel.setAlignmentX(LEFT_ALIGNMENT);
        varianceLabel.setAlignmentX(LEFT_ALIGNMENT);

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            applyDefaults();
            renderTimer.restart();
        });

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(title("Input:"));
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(inputCombo);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(title("Mode:"));
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(modeCombo);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(title("Component:"));
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(componentCombo);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(linearizeCheck);
        sidebar.add(invertCheck);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(title("Enhancement:"));
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(enhancementCombo);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(opacityLabel);
        sidebar.add(opacitySlider);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(resetControlsButton);
        sidebar.add(Box.createVerticalStrut(16));
        sidebar.add(infoLabel);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(varianceLabel);
        sidebar.add(Box.createVerticalGlue());

        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebarScroll.setPreferredSize(new Dimension(250, 100));
        add(sidebarScroll, BorderLayout.EAST);
    }

    /** Defaults: Colour, Component 3, stretched - the weakest component often shows subtle edits. */
    private void applyDefaults() {
        inputCombo.setSelectedIndex(INPUT_COLOUR);
        modeCombo.setSelectedIndex(MODE_COMPONENT);
        componentCombo.setSelectedIndex(2);
        linearizeCheck.setSelected(false);
        invertCheck.setSelected(false);
        enhancementCombo.setSelectedIndex(ENH_STRETCH);
        opacitySlider.setValue(DEFAULT_OPACITY);
    }

    private void updateLabels() {
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
        inputCombo.setEnabled(enabled);
        modeCombo.setEnabled(enabled);
        componentCombo.setEnabled(enabled);
        linearizeCheck.setEnabled(enabled);
        invertCheck.setEnabled(enabled);
        enhancementCombo.setEnabled(enabled);
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
        prepared = null;
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        varianceLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    private static class RenderResult {

        final BufferedImage image;
        final String variance;

        RenderResult(BufferedImage image, String variance) {
            this.image = image;
            this.variance = variance;
        }
    }

    /** Render with the current controls on a background thread (latest request wins). */
    private void startRender() {
        if (source == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final BufferedImage src = source;
        final boolean gradient = inputCombo.getSelectedIndex() == INPUT_GRADIENT;
        final boolean linearize = linearizeCheck.isSelected();
        final int mode = modeCombo.getSelectedIndex();
        final int component = componentCombo.getSelectedIndex();
        final boolean invert = invertCheck.isSelected();
        final int enhancement = enhancementCombo.getSelectedIndex();
        final double opacity = opacitySlider.getValue() / 1000.0;

        SwingWorker<RenderResult, Void> worker = new SwingWorker<RenderResult, Void>() {
            @Override
            protected RenderResult doInBackground() {
                int w = src.getWidth();
                int h = src.getHeight();
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

                Prepared prep = prepared;
                if (prep == null || prep.gradient != gradient || prep.linearize != linearize) {
                    prep = prepare(s, w, h, gradient, linearize, this::isCancelled);
                    if (prep == null) {
                        return null; // cancelled
                    }
                    if (source == src) {
                        prepared = prep;
                    }
                }

                byte[][] result = computeResult(s, w, h, prep, mode, component, enhancement, this::isCancelled);
                if (result == null) {
                    return null;
                }

                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] d = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
                composite(s, d, w, h, result, invert, opacity, this::isCancelled);
                return new RenderResult(out, prep.varianceText());
            }

            @Override
            protected void done() {
                if (isCancelled() || source != src) {
                    return;
                }
                try {
                    RenderResult r = get();
                    if (r != null) {
                        canvas.setImage(r.image, resetViewOnNextRender);
                        resetViewOnNextRender = false;
                        varianceLabel.setText(r.variance);
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "PCA render failed", ex);
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    // ----------------------------------------------------------- PCA maths

    private static double[] srgbToLinearLut(boolean linearize) {
        double[] lut = new double[256];
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            lut[i] = linearize ? (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)) : c;
        }
        return lut;
    }

    /** Analysis state for one (input, linearize) combination: feature source plus the PCA basis. */
    private static class Prepared {

        final boolean gradient;
        final boolean linearize;
        final double[] lut;

        // gradient input only: standardised features f = a * value + b
        float[] gx;
        float[] gy;
        short[] lum16;
        double a0, b0, a1, b1, a2, b2;

        final double[] mean = new double[3];
        final double[][] vecs = new double[3][3];   // vecs[k] = unit eigenvector of component k
        final double[] eig = new double[3];

        Prepared(boolean gradient, boolean linearize) {
            this.gradient = gradient;
            this.linearize = linearize;
            this.lut = srgbToLinearLut(linearize);
        }

        /** Feature vector of pixel i. */
        void features(int[] src, int i, double[] f) {
            if (gradient) {
                f[0] = a0 * gx[i] + b0;
                f[1] = a1 * gy[i] + b1;
                f[2] = a2 * (lum16[i] & 0xFFFF) + b2;
            } else {
                int p = src[i];
                f[0] = lut[(p >> 16) & 0xFF];
                f[1] = lut[(p >> 8) & 0xFF];
                f[2] = lut[p & 0xFF];
            }
        }

        String varianceText() {
            double total = eig[0] + eig[1] + eig[2];
            if (total <= 0) {
                return " ";
            }
            return String.format("<html>Variance explained:<br>C1 %.1f%%<br>C2 %.1f%%<br>C3 %.2f%%</html>",
                    100 * eig[0] / total, 100 * eig[1] / total, 100 * eig[2] / total);
        }
    }

    /** Build the feature source and PCA basis. Returns null if cancelled. */
    static Prepared prepare(int[] src, int w, int h, boolean gradient, boolean linearize, BooleanSupplier cancelled) {
        final int n = w * h;
        Prepared pr = new Prepared(gradient, linearize);

        if (gradient) {
            short[] lum16 = new short[n];
            for (int i = 0; i < n; i++) {
                int p = src[i];
                double l = 0.299 * pr.lut[(p >> 16) & 0xFF] + 0.587 * pr.lut[(p >> 8) & 0xFF]
                        + 0.114 * pr.lut[p & 0xFF];
                lum16[i] = (short) Math.round(l * 65535.0);
            }
            float[] gx = new float[n];
            float[] gy = new float[n];
            double sx = 0, sxx = 0, sy = 0, syy = 0, sl = 0, sll = 0;
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
                    int tl = lum16[rowM + xm] & 0xFFFF, tc = lum16[rowM + x] & 0xFFFF, tr = lum16[rowM + xp] & 0xFFFF;
                    int ml = lum16[row + xm] & 0xFFFF, mc = lum16[row + x] & 0xFFFF, mr = lum16[row + xp] & 0xFFFF;
                    int bl = lum16[rowP + xm] & 0xFFFF, bc = lum16[rowP + x] & 0xFFFF, br = lum16[rowP + xp] & 0xFFFF;
                    // Sobel / 4, on luminance in [0,1]
                    double dx = ((tr + 2 * mr + br) - (tl + 2 * ml + bl)) / (4.0 * 65535.0);
                    double dy = ((bl + 2 * bc + br) - (tl + 2 * tc + tr)) / (4.0 * 65535.0);
                    double l = mc / 65535.0;
                    gx[row + x] = (float) dx;
                    gy[row + x] = (float) dy;
                    sx += dx;
                    sxx += dx * dx;
                    sy += dy;
                    syy += dy * dy;
                    sl += l;
                    sll += l * l;
                }
            }
            double mx = sx / n, my = sy / n, ml = sl / n;
            double stdx = Math.sqrt(Math.max(sxx / n - mx * mx, 0));
            double stdy = Math.sqrt(Math.max(syy / n - my * my, 0));
            double stdl = Math.sqrt(Math.max(sll / n - ml * ml, 0));
            stdx = stdx < 1e-9 ? 1 : stdx;
            stdy = stdy < 1e-9 ? 1 : stdy;
            stdl = stdl < 1e-9 ? 1 : stdl;
            // f = 0.5 + (value - mean) / (8 * std): +-4 sigma spans 0..1
            pr.gx = gx;
            pr.gy = gy;
            pr.lum16 = lum16;
            pr.a0 = 1.0 / (8 * stdx);
            pr.b0 = 0.5 - mx * pr.a0;
            pr.a1 = 1.0 / (8 * stdy);
            pr.b1 = 0.5 - my * pr.a1;
            pr.a2 = 1.0 / (65535.0 * 8 * stdl);
            pr.b2 = 0.5 - ml / (8 * stdl);
        }

        // mean and covariance of the features
        double[] sum = new double[3];
        double[][] sq = new double[3][3];
        double[] f = new double[3];
        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                pr.features(src, i, f);
                sum[0] += f[0];
                sum[1] += f[1];
                sum[2] += f[2];
                sq[0][0] += f[0] * f[0];
                sq[0][1] += f[0] * f[1];
                sq[0][2] += f[0] * f[2];
                sq[1][1] += f[1] * f[1];
                sq[1][2] += f[1] * f[2];
                sq[2][2] += f[2] * f[2];
            }
        }
        double[][] cov = new double[3][3];
        for (int a = 0; a < 3; a++) {
            pr.mean[a] = sum[a] / n;
        }
        for (int a = 0; a < 3; a++) {
            for (int b = a; b < 3; b++) {
                double c = sq[a][b] / n - pr.mean[a] * pr.mean[b];
                cov[a][b] = c;
                cov[b][a] = c;
            }
        }
        eigen(cov, pr.eig, pr.vecs);
        return pr;
    }

    /** Eigen-decomposition of a symmetric 3x3 matrix (Jacobi). Results sorted by decreasing eigenvalue. */
    static void eigen(double[][] a, double[] values, double[][] vectors) {
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * apq);
                    double t = theta == 0 ? 1 : Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        Integer[] order = {0, 1, 2};
        final double[] diag = {a[0][0], a[1][1], a[2][2]};
        java.util.Arrays.sort(order, (x, y) -> Double.compare(diag[y], diag[x]));
        for (int k = 0; k < 3; k++) {
            int col = order[k];
            values[k] = Math.max(diag[col], 0);
            double[] e = {v[0][col], v[1][col], v[2][col]};
            // deterministic sign: the largest-magnitude element is positive
            int big = 0;
            for (int j = 1; j < 3; j++) {
                if (Math.abs(e[j]) > Math.abs(e[big])) {
                    big = j;
                }
            }
            double sign = e[big] < 0 ? -1 : 1;
            for (int j = 0; j < 3; j++) {
                vectors[k][j] = e[j] * sign;
            }
        }
    }

    // ------------------------------------------------------- result image

    private static final double[] ENCODE_LUT = new double[4096];

    static {
        for (int i = 0; i < ENCODE_LUT.length; i++) {
            double v = i / 4095.0;
            ENCODE_LUT[i] = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        }
    }

    private static double encode(double v) {
        int i = (int) Math.round(v * 4095.0);
        return ENCODE_LUT[i < 0 ? 0 : (i > 4095 ? 4095 : i)];
    }

    /**
     * Compute the result channels (1 for Distance / Component, 3 for Projection / Difference),
     * enhanced and quantised to 0..255. Returns null if cancelled.
     */
    static byte[][] computeResult(int[] src, int w, int h, Prepared pr, int mode, int component,
            int enhancement, BooleanSupplier cancelled) {
        final int n = w * h;
        final int nch = (mode == MODE_PROJECTION || mode == MODE_DIFFERENCE) ? 3 : 1;
        float[][] vals = new float[nch][n];
        final double[] e = pr.vecs[component];
        final double[] m = pr.mean;
        final boolean encodeProjection = mode == MODE_PROJECTION && pr.linearize && !pr.gradient;
        double[] f = new double[3];

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                pr.features(src, i, f);
                double d0 = f[0] - m[0], d1 = f[1] - m[1], d2 = f[2] - m[2];
                double c = d0 * e[0] + d1 * e[1] + d2 * e[2];
                switch (mode) {
                    case MODE_PROJECTION: {
                        double p0 = m[0] + c * e[0], p1 = m[1] + c * e[1], p2 = m[2] + c * e[2];
                        if (encodeProjection) {
                            p0 = encode(p0);
                            p1 = encode(p1);
                            p2 = encode(p2);
                        }
                        vals[0][i] = (float) (255 * p0);
                        vals[1][i] = (float) (255 * p1);
                        vals[2][i] = (float) (255 * p2);
                        break;
                    }
                    case MODE_DIFFERENCE:
                        vals[0][i] = (float) (128 + 255 * (d0 - c * e[0]));
                        vals[1][i] = (float) (128 + 255 * (d1 - c * e[1]));
                        vals[2][i] = (float) (128 + 255 * (d2 - c * e[2]));
                        break;
                    case MODE_DISTANCE: {
                        double r0 = d0 - c * e[0], r1 = d1 - c * e[1], r2 = d2 - c * e[2];
                        vals[0][i] = (float) (255 * Math.sqrt(r0 * r0 + r1 * r1 + r2 * r2));
                        break;
                    }
                    default: // MODE_COMPONENT
                        vals[0][i] = (float) (128 + 255 * c);
                }
            }
        }

        byte[][] out = new byte[nch][];
        for (int c = 0; c < nch; c++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            out[c] = enhance(vals[c], enhancement);
            vals[c] = null; // release memory early
        }
        return out;
    }

    private static final int BINS = 4096;

    /** Quantise to 0..255 with the chosen enhancement (None / Stretch contrast / Equalise histogram). */
    static byte[] enhance(float[] v, int enhancement) {
        final int n = v.length;
        byte[] out = new byte[n];
        if (enhancement == ENH_NONE) {
            for (int i = 0; i < n; i++) {
                int q = Math.round(v[i]);
                out[i] = (byte) (q < 0 ? 0 : (q > 255 ? 255 : q));
            }
            return out;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float x = v[i];
            if (x < min) {
                min = x;
            }
            if (x > max) {
                max = x;
            }
        }
        if (!(max > min)) {
            java.util.Arrays.fill(out, (byte) 128);
            return out;
        }
        final double scale = (BINS - 1e-6) / (max - min);
        int[] hist = new int[BINS];
        for (int i = 0; i < n; i++) {
            hist[(int) ((v[i] - min) * scale)]++;
        }

        int[] map = new int[BINS];
        if (enhancement == ENH_STRETCH) {
            // linear stretch between the 0.5% and 99.5% percentiles
            long lowTarget = (long) (n * 0.005);
            long highTarget = (long) (n * 0.995);
            long acc = 0;
            int lo = 0, hi = BINS - 1;
            boolean haveLo = false;
            for (int b = 0; b < BINS; b++) {
                acc += hist[b];
                if (!haveLo && acc > lowTarget) {
                    lo = b;
                    haveLo = true;
                }
                if (acc >= highTarget) {
                    hi = b;
                    break;
                }
            }
            if (hi <= lo) {
                lo = 0;
                hi = BINS - 1;
            }
            for (int b = 0; b < BINS; b++) {
                double t = (b - lo) / (double) (hi - lo);
                map[b] = (int) Math.round(255 * (t < 0 ? 0 : (t > 1 ? 1 : t)));
            }
        } else {
            long cdf = 0;
            long cdfMin = -1;
            for (int b = 0; b < BINS; b++) {
                cdf += hist[b];
                if (cdfMin < 0 && hist[b] > 0) {
                    cdfMin = cdf;
                }
                long denom = n - cdfMin;
                map[b] = denom <= 0 ? 128 : (int) Math.round(255.0 * Math.max(0, cdf - cdfMin) / denom);
            }
        }
        for (int i = 0; i < n; i++) {
            out[i] = (byte) map[(int) ((v[i] - min) * scale)];
        }
        return out;
    }

    /** out = original * (1 - opacity) + result * opacity (optionally inverting the result first). */
    static void composite(int[] src, int[] dst, int w, int h, byte[][] res, boolean invert,
            double opacity, BooleanSupplier cancelled) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;
        final boolean grey = res.length == 1;
        final byte[] c0 = res[0];
        final byte[] c1 = grey ? c0 : res[1];
        final byte[] c2 = grey ? c0 : res[2];
        final int flip = invert ? 255 : 0;

        for (int y = 0; y < h; y++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                int p = src[i];
                int rr = (c0[i] & 0xFF) ^ flip;
                int rg = (c1[i] & 0xFF) ^ flip;
                int rb = (c2[i] & 0xFF) ^ flip;
                int r = (((p >> 16) & 0xFF) * keep + rr * opI + 128) >> 8;
                int g = (((p >> 8) & 0xFF) * keep + rg * opI + 128) >> 8;
                int b = ((p & 0xFF) * keep + rb * opI + 128) >> 8;
                dst[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
