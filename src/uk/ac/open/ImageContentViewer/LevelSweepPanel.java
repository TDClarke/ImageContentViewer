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
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.ReadContentInputStream;

/**
 * "Level sweep" viewer. A window of intensity levels is swept from dark to
 * light; levels inside the window are stretched to full contrast and levels
 * outside clip to black / white. The result is blended with the original image.
 *
 * Sidebar controls:
 *   Sweep   0.0 - 1.0  position of the window (0 = darkest levels, 1 = lightest)
 *   Width   8 - 128    size of the window in levels (out of 255)
 *   Opacity 0.0 - 1.0  opacity of the sweep result over the original image
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class LevelSweepPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(LevelSweepPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    private static final int DEFAULT_SWEEP = 500;   // 0.500
    private static final int DEFAULT_WIDTH = 32;
    private static final int DEFAULT_OPACITY = 1000; // 1.000

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
    private final JSlider sweepSlider = new JSlider(0, 1000, DEFAULT_SWEEP);
    private final JSlider widthSlider = new JSlider(8, 128, DEFAULT_WIDTH);
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel sweepLabel = new JLabel();
    private final JLabel widthLabel = new JLabel();
    private final JLabel opacityLabel = new JLabel();
    private final JComboBox<String> applyCombo
            = new JComboBox<>(new String[]{"Colour channels", "Luminance only"});
    private final JToggleButton animateButton = new JToggleButton("\u25B6 Animate sweep");
    private final JButton resetSlidersButton = new JButton("Reset sliders");
    private final JLabel infoLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private BufferedImage source;          // TYPE_INT_ARGB
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<BufferedImage, Void> renderer;
    private final Timer renderTimer;       // coalesces rapid slider changes
    private final Timer animTimer;

    public LevelSweepPanel() {
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

        animTimer = new Timer(50, e -> {
            int v = sweepSlider.getValue() + 5;
            sweepSlider.setValue(v > sweepSlider.getMaximum() ? 0 : v);
        });

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

        JLabel help = new JLabel("<html>Levels inside the sweep window are stretched to full contrast; "
                + "levels outside clip to black or white.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        sweepSlider.setToolTipText("Position of the window: 0.0 = darkest levels, 1.0 = lightest");
        widthSlider.setToolTipText("Width of the window in levels (out of 255)");
        opacitySlider.setToolTipText("Opacity of the sweep result over the original image");
        for (JSlider s : new JSlider[]{sweepSlider, widthSlider, opacitySlider}) {
            s.setAlignmentX(LEFT_ALIGNMENT);
            s.addChangeListener(e -> {
                updateLabels();
                renderTimer.restart();
            });
        }
        for (JLabel l : new JLabel[]{sweepLabel, widthLabel, opacityLabel, infoLabel}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
        }

        JLabel applyTitle = new JLabel("Apply to:");
        applyTitle.setAlignmentX(LEFT_ALIGNMENT);
        applyCombo.setAlignmentX(LEFT_ALIGNMENT);
        applyCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, applyCombo.getPreferredSize().height));
        applyCombo.addActionListener(e -> renderTimer.restart());

        animateButton.setAlignmentX(LEFT_ALIGNMENT);
        animateButton.setFocusable(false);
        animateButton.setToolTipText("Automatically sweep the window from dark to light, repeatedly");
        animateButton.addActionListener(e -> {
            if (animateButton.isSelected()) {
                animateButton.setText("\u23F8 Stop animation");
                animTimer.start();
            } else {
                stopAnimation();
            }
        });

        resetSlidersButton.setAlignmentX(LEFT_ALIGNMENT);
        resetSlidersButton.setFocusable(false);
        resetSlidersButton.addActionListener(e -> {
            sweepSlider.setValue(DEFAULT_SWEEP);
            widthSlider.setValue(DEFAULT_WIDTH);
            opacitySlider.setValue(DEFAULT_OPACITY);
        });

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(sweepLabel);
        sidebar.add(sweepSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(widthLabel);
        sidebar.add(widthSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(opacityLabel);
        sidebar.add(opacitySlider);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(applyTitle);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(applyCombo);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(animateButton);
        sidebar.add(Box.createVerticalStrut(6));
        sidebar.add(resetSlidersButton);
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

    private void stopAnimation() {
        animTimer.stop();
        animateButton.setSelected(false);
        animateButton.setText("\u25B6 Animate sweep");
    }

    private void updateLabels() {
        sweepLabel.setText(String.format("Sweep: %.3f", sweepSlider.getValue() / 1000.0));
        widthLabel.setText("Width: " + widthSlider.getValue());
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
        sweepSlider.setEnabled(enabled);
        widthSlider.setEnabled(enabled);
        opacitySlider.setEnabled(enabled);
        applyCombo.setEnabled(enabled);
        animateButton.setEnabled(enabled);
        resetSlidersButton.setEnabled(enabled);
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
                try {
                    source = get();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Unable to read image " + file.getName(), ex);
                    source = null;
                }
                if (source == null) {
                    infoLabel.setText("Image could not be read");
                    canvas.setImage(null, true);
                    setControlsEnabled(false);
                } else {
                    infoLabel.setText("<html>" + source.getWidth() + " x " + source.getHeight() + " px</html>");
                    setControlsEnabled(true);
                    resetViewOnNextRender = true;
                    startRender();
                }
            }
        };
        loader.execute();
    }

    public void reset() {
        stopAnimation();
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
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    /** Render with the current slider values on a background thread (latest request wins). */
    private void startRender() {
        if (source == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final BufferedImage src = source;
        final double sweep = sweepSlider.getValue() / 1000.0;
        final int width = widthSlider.getValue();
        final double opacity = opacitySlider.getValue() / 1000.0;
        final boolean lumOnly = applyCombo.getSelectedIndex() == 1;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
                int[] d = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
                apply(s, d, src.getWidth(), src.getHeight(), sweep, width, opacity, lumOnly, this::isCancelled);
                return out;
            }

            @Override
            protected void done() {
                if (isCancelled() || source != src) {
                    return;
                }
                try {
                    canvas.setImage(get(), resetViewOnNextRender);
                    resetViewOnNextRender = false;
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Level sweep render failed", ex);
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    /**
     * Level sweep. The window is [lo, lo + width] where lo = sweep * (255 - width),
     * so sweep 0.0 covers levels 0..width and sweep 1.0 covers 255-width..255.
     * Window levels are stretched to 0..255, everything else clips. The result is
     * blended with the original: out = original * (1 - opacity) + sweep * opacity.
     */
    static void apply(int[] src, int[] dst, int w, int h, double sweep, int width,
            double opacity, boolean lumOnly, BooleanSupplier cancelled) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;
        final double lo = sweep * (255 - width);

        int[] win = new int[256];
        for (int v = 0; v < 256; v++) {
            double t = (v - lo) / width;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            win[v] = (int) Math.round(t * 255.0);
        }

        if (!lumOnly) {
            // Per channel: one blended look-up table does everything.
            int[] lut = new int[256];
            for (int v = 0; v < 256; v++) {
                lut[v] = (v * keep + win[v] * opI + 128) >> 8;
            }
            for (int y = 0; y < h; y++) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                int i = y * w;
                int end = i + w;
                for (; i < end; i++) {
                    int p = src[i];
                    dst[i] = (p & 0xFF000000)
                            | (lut[(p >> 16) & 0xFF] << 16)
                            | (lut[(p >> 8) & 0xFF] << 8)
                            | lut[p & 0xFF];
                }
            }
        } else {
            // Luminance: sweep the grey level, blend the grey result with each channel.
            for (int y = 0; y < h; y++) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                int i = y * w;
                int end = i + w;
                for (; i < end; i++) {
                    int p = src[i];
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int l = (r * 77 + g * 150 + b * 29) >> 8;
                    int sOp = win[l] * opI;
                    dst[i] = (p & 0xFF000000)
                            | (((r * keep + sOp + 128) >> 8) << 16)
                            | (((g * keep + sOp + 128) >> 8) << 8)
                            | ((b * keep + sOp + 128) >> 8);
                }
            }
        }
    }
}
