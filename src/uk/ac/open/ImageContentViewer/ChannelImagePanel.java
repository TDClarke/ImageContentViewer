package uk.ac.open.ImageContentViewer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
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
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.ReadContentInputStream;

/**
 * Image panel with a rotate/zoom toolbar, a scrollable right-hand sidebar and a
 * histogram. A drop-down in the sidebar selects how the image is shown. The
 * available views depend on the {@link ColourSpace} (RGB or YCbCr).
 */
public class ChannelImagePanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(ChannelImagePanel.class.getName());

    /** Same zoom steps as Autopsy's MediaViewImagePanel. */
    private static final double[] ZOOM_STEPS = {
        0.0625, 0.125, 0.25, 0.375, 0.5, 0.75,
        1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};
    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    // ------------------------------------------------------------------
    // Colour spaces
    // ------------------------------------------------------------------

    private static int clamp(double v) {
        long r = Math.round(v);
        return r < 0 ? 0 : (r > 255 ? 255 : (int) r);
    }

    private static int grey(int l) {
        return (l << 16) | (l << 8) | l;
    }

    private static int rgb(double r, double g, double b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /**
     * A colour space the panel can display. Mode 0 is always "Original". For
     * the other modes {@link #render} returns the RGB (no alpha) to display.
     */
    enum ColourSpace {

        RGB("Colour histogram (R, G, B):",
                new String[]{"Original", "Greyscale", "Red channel", "Green channel", "Blue channel"},
                new Color[]{new Color(220, 40, 40), new Color(40, 180, 40), new Color(50, 90, 235)},
                false) {
            @Override
            int[] channels(int argb) {
                return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
            }

            @Override
            boolean hasTintOption(int mode) {
                return mode >= 2;
            }

            @Override
            int render(int argb, int mode, boolean asGrey) {
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                switch (mode) {
                    case 1:
                        return grey(clamp(0.299 * r + 0.587 * g + 0.114 * b));
                    case 2:
                        return asGrey ? grey(r) : (r << 16);
                    case 3:
                        return asGrey ? grey(g) : (g << 8);
                    case 4:
                        return asGrey ? grey(b) : b;
                    default:
                        return argb & 0xFFFFFF;
                }
            }
        },

        /** Full-range YCbCr (ITU-R BT.601 / JPEG). */
        YCBCR("YCbCr histogram (Y, Cb, Cr):",
                new String[]{"Original", "Y (luma)", "Cb (blue difference)", "Cr (red difference)"},
                new Color[]{new Color(210, 210, 210), new Color(60, 120, 255), new Color(235, 60, 60)},
                true) {
            @Override
            int[] channels(int argb) {
                double r = (argb >> 16) & 0xFF;
                double g = (argb >> 8) & 0xFF;
                double b = argb & 0xFF;
                return new int[]{
                    clamp(0.299 * r + 0.587 * g + 0.114 * b),
                    clamp(128 - 0.168736 * r - 0.331264 * g + 0.5 * b),
                    clamp(128 + 0.5 * r - 0.418688 * g - 0.081312 * b)};
            }

            @Override
            boolean hasTintOption(int mode) {
                return mode >= 2; // Y is always grey
            }

            @Override
            int render(int argb, int mode, boolean asGrey) {
                if (mode < 1 || mode > 3) {
                    return argb & 0xFFFFFF;
                }
                int v = channels(argb)[mode - 1];
                if (mode == 1 || asGrey) {
                    return grey(v);
                }
                // Tinted: show Cb or Cr alone with the other components neutral (128).
                double d = v - 128;
                if (mode == 2) {
                    return rgb(128, 128 - 0.344136 * d, 128 + 1.772 * d);
                }
                return rgb(128 + 1.402 * d, 128 - 0.714136 * d, 128);
            }
        };

        final String histogramTitle;
        final String[] modeLabels;
        final Color[] histogramColours;
        final boolean defaultGrey;

        ColourSpace(String histogramTitle, String[] modeLabels, Color[] histogramColours, boolean defaultGrey) {
            this.histogramTitle = histogramTitle;
            this.modeLabels = modeLabels;
            this.histogramColours = histogramColours;
            this.defaultGrey = defaultGrey;
        }

        /** The three channel values (0-255) of a pixel, used for the histogram. */
        abstract int[] channels(int argb);

        /** True if the mode can be shown either tinted or as grey levels. */
        abstract boolean hasTintOption(int mode);

        /** RGB value (no alpha) to display for the given mode. */
        abstract int render(int argb, int mode, boolean asGrey);
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final ColourSpace colourSpace;

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
    private final JComboBox<String> modeCombo;
    private final JCheckBox intensityCheck = new JCheckBox("Show channel as grey intensity");
    private final JLabel infoLabel = new JLabel(" ");
    private final HistogramPanel histogramPanel;
    private final JCheckBox logCheck = new JCheckBox("Log scale");

    private final ImageCanvas canvas = new ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private BufferedImage source;
    private final Map<String, BufferedImage> cache = new HashMap<>();
    private SwingWorker<Loaded, Void> loader;

    /** RGB panel (default). */
    public ChannelImagePanel() {
        this(ColourSpace.RGB);
    }

    public ChannelImagePanel(ColourSpace colourSpace) {
        this.colourSpace = colourSpace;
        this.modeCombo = new JComboBox<>(colourSpace.modeLabels);
        this.histogramPanel = new HistogramPanel(colourSpace.histogramColours);
        this.intensityCheck.setSelected(colourSpace.defaultGrey);

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
        SidebarPanel sidebar = new SidebarPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("View as:");
        title.setAlignmentX(LEFT_ALIGNMENT);
        modeCombo.setAlignmentX(LEFT_ALIGNMENT);
        modeCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, modeCombo.getPreferredSize().height));
        intensityCheck.setAlignmentX(LEFT_ALIGNMENT);
        intensityCheck.setToolTipText("Off: show the channel tinted. On: render its values as grey levels.");
        infoLabel.setAlignmentX(LEFT_ALIGNMENT);

        sidebar.add(title);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(modeCombo);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(intensityCheck);
        sidebar.add(Box.createVerticalStrut(16));
        sidebar.add(infoLabel);
        sidebar.add(Box.createVerticalStrut(16));

        JLabel histTitle = new JLabel(colourSpace.histogramTitle);
        histTitle.setAlignmentX(LEFT_ALIGNMENT);
        histogramPanel.setAlignmentX(LEFT_ALIGNMENT);
        histogramPanel.setPreferredSize(new Dimension(200, 140));
        histogramPanel.setMinimumSize(new Dimension(100, 140));
        histogramPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        logCheck.setAlignmentX(LEFT_ALIGNMENT);
        logCheck.setToolTipText("Log scale makes small values visible when a few levels dominate.");
        logCheck.addActionListener(e -> histogramPanel.setLogScale(logCheck.isSelected()));

        sidebar.add(histTitle);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(histogramPanel);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(logCheck);
        sidebar.add(Box.createVerticalGlue());

        // Scrollable side panel: vertical scrollbar appears when the window is too short.
        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebarScroll.setPreferredSize(new Dimension(250, 100));
        add(sidebarScroll, BorderLayout.EAST);

        modeCombo.addActionListener(e -> refresh());
        intensityCheck.addActionListener(e -> refresh());
    }

    /** Sync toolbar fields / button states with the canvas. */
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

    // ------------------------------------------------------------------ API

    /** Load the given file's image in the background and display it. */
    public void setFile(AbstractFile file) {
        reset();
        infoLabel.setText("Loading...");
        final ColourSpace cs = colourSpace;
        loader = new SwingWorker<Loaded, Void>() {
            @Override
            protected Loaded doInBackground() throws Exception {
                BufferedImage img;
                try (ReadContentInputStream in = new ReadContentInputStream(file)) {
                    img = ImageIO.read(in);
                }
                return new Loaded(img, img == null ? null : computeHistogram(img, cs));
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    Loaded loaded = get();
                    source = loaded.image;
                    histogramPanel.setHistogram(loaded.histogram);
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Unable to read image " + file.getName(), ex);
                    source = null;
                    histogramPanel.setHistogram(null);
                }
                if (source == null) {
                    infoLabel.setText("Image could not be read");
                    canvas.setImage(null, true);
                    setControlsEnabled(false);
                } else {
                    infoLabel.setText("<html>" + source.getWidth() + " x " + source.getHeight() + " px</html>");
                    setControlsEnabled(true);
                    refresh(true);
                }
            }
        };
        loader.execute();
    }

    public void reset() {
        if (loader != null) {
            loader.cancel(true);
            loader = null;
        }
        source = null;
        cache.clear();
        canvas.setImage(null, true);
        histogramPanel.setHistogram(null);
        infoLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------- internals

    private void setControlsEnabled(boolean enabled) {
        modeCombo.setEnabled(enabled);
        intensityCheck.setEnabled(enabled && colourSpace.hasTintOption(modeCombo.getSelectedIndex()));
    }

    private void refresh() {
        refresh(false);
    }

    /** @param resetView true to reset zoom/rotation (new file), false keeps them. */
    private void refresh(boolean resetView) {
        if (source == null) {
            return;
        }
        int mode = modeCombo.getSelectedIndex();
        boolean tintOption = colourSpace.hasTintOption(mode);
        boolean asGrey = intensityCheck.isSelected();
        intensityCheck.setEnabled(tintOption);

        String key = mode + (tintOption && asGrey ? ":G" : "");
        BufferedImage img = cache.get(key);
        if (img == null) {
            img = (mode == 0) ? source : convert(source, colourSpace, mode, asGrey);
            cache.put(key, img);
        }
        canvas.setImage(img, resetView);
    }

    /** Produce the selected view of the image (alpha preserved). */
    static BufferedImage convert(BufferedImage src, ColourSpace cs, int mode, boolean asGrey) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                row[x] = (argb & 0xFF000000) | (cs.render(argb, mode, asGrey) & 0xFFFFFF);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** Returns int[3][256]: counts of the three channels (transparent pixels skipped). */
    static int[][] computeHistogram(BufferedImage img, ColourSpace cs) {
        int[][] hist = new int[3][256];
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                if ((argb >>> 24) == 0) {
                    continue;
                }
                int[] c = cs.channels(argb);
                hist[0][c[0]]++;
                hist[1][c[1]]++;
                hist[2][c[2]]++;
            }
        }
        return hist;
    }

    /** Result of the background load: the image plus its histogram. */
    private static class Loaded {

        final BufferedImage image;
        final int[][] histogram;

        Loaded(BufferedImage image, int[][] histogram) {
            this.image = image;
            this.histogram = histogram;
        }
    }

    /** Sidebar content that follows the scroll pane's width but scrolls vertically. */
    static class SidebarPanel extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            java.awt.Container parent = getParent();
            return parent instanceof JViewport && parent.getHeight() > getPreferredSize().height;
        }
    }

    /** Overlaid three-channel histogram of the original image. */
    private static class HistogramPanel extends JPanel {

        private static final long serialVersionUID = 1L;
        private final Color[] colours;
        private int[][] histogram;
        private boolean logScale = false;

        HistogramPanel(Color[] colours) {
            this.colours = colours;
            setBackground(Color.BLACK);
            setBorder(BorderFactory.createLineBorder(Color.GRAY));
        }

        void setHistogram(int[][] h) {
            this.histogram = h;
            repaint();
        }

        void setLogScale(boolean log) {
            this.logScale = log;
            repaint();
        }

        private double scaled(int v) {
            return logScale ? Math.log1p(v) : v;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int left = 4;
                int right = 4;
                int top = 6;
                int bottom = 16;
                int pw = w - left - right;
                int ph = h - top - bottom;
                if (pw <= 1 || ph <= 1) {
                    return;
                }

                g2.setColor(Color.GRAY);
                g2.drawLine(left, top + ph, left + pw, top + ph);
                g2.drawString("0", left, h - 3);
                String end = "255";
                g2.drawString(end, left + pw - g2.getFontMetrics().stringWidth(end), h - 3);

                if (histogram == null) {
                    return;
                }
                double max = 0;
                for (int[] ch : histogram) {
                    for (int v : ch) {
                        max = Math.max(max, scaled(v));
                    }
                }
                if (max <= 0) {
                    return;
                }

                g2.setStroke(new BasicStroke(1.2f));
                for (int c = 0; c < 3; c++) {
                    Path2D.Double line = new Path2D.Double();
                    Path2D.Double area = new Path2D.Double();
                    area.moveTo(left, top + ph);
                    for (int i = 0; i < 256; i++) {
                        double x = left + i * (pw - 1) / 255.0;
                        double y = top + ph - scaled(histogram[c][i]) / max * ph;
                        if (i == 0) {
                            line.moveTo(x, y);
                        } else {
                            line.lineTo(x, y);
                        }
                        area.lineTo(x, y);
                    }
                    area.lineTo(left + pw - 1, top + ph);
                    area.closePath();
                    Color col = colours[c];
                    g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 70));
                    g2.fill(area);
                    g2.setColor(col);
                    g2.draw(line);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Scrollable canvas that draws the image with zoom and rotation. In
     * "auto fit" mode the image is scaled to fill the viewport; after a manual
     * zoom it becomes scrollable.
     */
    static class ImageCanvas extends JPanel implements Scrollable {

        private static final long serialVersionUID = 1L;

        private BufferedImage image;
        private double zoom = 1.0;
        private int rotation = 0;      // degrees clockwise: 0, 90, 180, 270
        private boolean autoFit = true;
        private Runnable onChange = () -> { };

        ImageCanvas() {
            setBackground(Color.BLACK);
        }

        void setOnChange(Runnable r) {
            this.onChange = r;
        }

        boolean hasImage() {
            return image != null;
        }

        int getRotation() {
            return rotation;
        }

        void setImage(BufferedImage img, boolean resetView) {
            this.image = img;
            if (resetView || img == null) {
                rotation = 0;
                zoom = 1.0;
                autoFit = true;
            }
            changed();
        }

        void rotate(int angle) {
            rotation = (rotation + angle) % 360;
            changed();
        }

        void resetView() {
            rotation = 0;
            zoom = 1.0;
            autoFit = true;
            changed();
        }

        void zoomIn() {
            double z = effectiveZoom();
            for (double step : ZOOM_STEPS) {
                if (z < step - 1e-9) {
                    z = step;
                    break;
                }
            }
            setManualZoom(z);
        }

        void zoomOut() {
            double z = effectiveZoom();
            for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
                if (z > ZOOM_STEPS[i] + 1e-9) {
                    z = ZOOM_STEPS[i];
                    break;
                }
            }
            setManualZoom(z);
        }

        private void setManualZoom(double z) {
            zoom = Math.max(MIN_ZOOM_RATIO, Math.min(MAX_ZOOM_RATIO, z));
            autoFit = false;
            changed();
        }

        private void changed() {
            revalidate();
            repaint();
            onChange.run();
        }

        private int rotatedWidth() {
            return (rotation % 180 == 0) ? image.getWidth() : image.getHeight();
        }

        private int rotatedHeight() {
            return (rotation % 180 == 0) ? image.getHeight() : image.getWidth();
        }

        private Dimension viewportSize() {
            JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            return vp != null ? vp.getExtentSize() : getSize();
        }

        /** The zoom ratio currently in effect (computed when in auto-fit mode). */
        double effectiveZoom() {
            if (image == null) {
                return 1.0;
            }
            if (!autoFit) {
                return zoom;
            }
            Dimension vp = viewportSize();
            if (vp.width <= 0 || vp.height <= 0) {
                return 1.0;
            }
            return Math.min((double) vp.width / rotatedWidth(), (double) vp.height / rotatedHeight());
        }

        @Override
        public Dimension getPreferredSize() {
            if (image == null || autoFit) {
                return new Dimension(1, 1);
            }
            return new Dimension(
                    Math.max(1, (int) Math.round(rotatedWidth() * zoom)),
                    Math.max(1, (int) Math.round(rotatedHeight() * zoom)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            double z = effectiveZoom();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, z >= 2.0
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.translate(getWidth() / 2.0, getHeight() / 2.0);
                g2.rotate(Math.toRadians(rotation));
                g2.scale(z, z);
                g2.translate(-image.getWidth() / 2.0, -image.getHeight() / 2.0);
                g2.drawImage(image, 0, 0, null);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return autoFit || image == null || getPreferredSize().width <= viewportSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return autoFit || image == null || getPreferredSize().height <= viewportSize().height;
        }
    }
}
