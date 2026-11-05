package uk.ac.open.ImageContentViewer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Clone (copy-move) detection, implemented in plain Java (no OpenCV).
 *
 * Method (block matching):
 *  1. The image is reduced so its longest side is at most "Maximal image size"
 *     and converted to luminance.
 *  2. Every block of (2 x Block size) pixels square is described by four numbers
 *     (mean, horizontal, vertical and diagonal change) computed from integral
 *     images. Blocks whose standard deviation is below "Minimal detail" are flat
 *     and ignored.
 *  3. The descriptors are quantised and sorted so that similar blocks end up
 *     next to each other; neighbours are then compared pixel by pixel. A pair is
 *     a match when its similarity (1 - rms difference / 32) is at least
 *     "Minimal similarity" and the blocks do not overlap.
 *  4. Matches are grouped by their offset (how far the second block is from the
 *     first). A copied region produces many matches with the same offset, so
 *     offsets with fewer than "Minimal cluster size" matching blocks are dropped.
 *  5. Each remaining offset gets a colour; both blocks of every match are tinted
 *     in that colour and some of the matches are joined by lines.
 *
 * "Show quantized image" shows the quantised luminance used for matching
 * instead of the original image under the overlay.
 *
 * Limitations: only copies with the same orientation and scale are found
 * (no rotated or resized clones).
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class CloneDetectionPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(CloneDetectionPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;

    // defaults
    private static final int DEFAULT_SIMILARITY = 90;    // 0.90
    private static final int DEFAULT_DETAIL = 200;       // 2.00
    private static final int DEFAULT_CLUSTER = 8;
    private static final int DEFAULT_BLOCK = 3;
    private static final int DEFAULT_MAX_SIZE = 512;

    /** Maximum number of block pairs kept (memory safety). */
    private static final int MAX_PAIRS = 6_000_000;
    /** Number of following blocks in sort order each block is compared with. */
    private static final int WINDOW = 6;
    /** Number of clone offsets that are drawn. */
    private static final int MAX_OFFSETS_SHOWN = 200;

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
    private final JSlider similaritySlider = new JSlider(1, 100, DEFAULT_SIMILARITY);
    private final JSlider detailSlider = new JSlider(1, 1000, DEFAULT_DETAIL);
    private final JSlider clusterSlider = new JSlider(1, 16, DEFAULT_CLUSTER);
    private final JSlider blockSlider = new JSlider(1, 5, DEFAULT_BLOCK);
    private final JSlider maxSizeSlider = new JSlider(128, 2048, DEFAULT_MAX_SIZE);
    private final JLabel similarityLabel = new JLabel();
    private final JLabel detailLabel = new JLabel();
    private final JLabel clusterLabel = new JLabel();
    private final JLabel blockLabel = new JLabel();
    private final JLabel maxSizeLabel = new JLabel();
    private final JCheckBox quantizedCheck = new JCheckBox("Show quantized image");
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel infoLabel = new JLabel(" ");
    private final JLabel resultLabel = new JLabel(" ");

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile BufferedImage source;   // TYPE_INT_ARGB
    private volatile Prepared prepared;      // cached per maximal image size
    private volatile PairSet pairSet;        // cached per matching parameters
    private boolean resetViewOnNextRender = true;
    private SwingWorker<BufferedImage, Void> loader;
    private SwingWorker<RenderResult, Void> renderer;
    private final Timer renderTimer;

    public CloneDetectionPanel() {
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

        renderTimer = new Timer(300, e -> startRender());
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

        JLabel help = new JLabel("<html>Finds regions that were copied within the image. "
                + "Matching regions get the same colour and are joined by lines. "
                + "Only copies with the same size and orientation are found.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        similaritySlider.setToolTipText("How alike two blocks must be to match (1.0 = identical)");
        detailSlider.setToolTipText("Blocks with less detail (standard deviation) than this are ignored");
        clusterSlider.setToolTipText("Minimum number of matching blocks that share the same offset");
        blockSlider.setToolTipText("Blocks are 2 x this many pixels square");
        maxSizeSlider.setToolTipText("Larger images are reduced to this size before analysis (slower when larger)");
        quantizedCheck.setToolTipText("Show the quantized luminance used for matching instead of the original");

        for (JSlider s : new JSlider[]{similaritySlider, detailSlider, clusterSlider, blockSlider, maxSizeSlider}) {
            s.setAlignmentX(LEFT_ALIGNMENT);
            s.addChangeListener(e -> {
                updateLabels();
                renderTimer.setInitialDelay(300);
                renderTimer.restart();
            });
        }
        for (JLabel l : new JLabel[]{similarityLabel, detailLabel, clusterLabel, blockLabel, maxSizeLabel,
            infoLabel, resultLabel}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
        }

        quantizedCheck.setAlignmentX(LEFT_ALIGNMENT);
        quantizedCheck.addActionListener(e -> {
            renderTimer.setInitialDelay(30);
            renderTimer.restart();
        });

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            similaritySlider.setValue(DEFAULT_SIMILARITY);
            detailSlider.setValue(DEFAULT_DETAIL);
            clusterSlider.setValue(DEFAULT_CLUSTER);
            blockSlider.setValue(DEFAULT_BLOCK);
            maxSizeSlider.setValue(DEFAULT_MAX_SIZE);
            quantizedCheck.setSelected(false);
        });

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(similarityLabel);
        sidebar.add(similaritySlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(detailLabel);
        sidebar.add(detailSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(clusterLabel);
        sidebar.add(clusterSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(blockLabel);
        sidebar.add(blockSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(maxSizeLabel);
        sidebar.add(maxSizeSlider);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(quantizedCheck);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(resetControlsButton);
        sidebar.add(Box.createVerticalStrut(16));
        sidebar.add(infoLabel);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(resultLabel);
        sidebar.add(Box.createVerticalGlue());

        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebarScroll.setPreferredSize(new Dimension(250, 100));
        add(sidebarScroll, BorderLayout.EAST);
    }

    private void updateLabels() {
        similarityLabel.setText(String.format("Minimal similarity: %.2f", similaritySlider.getValue() / 100.0));
        detailLabel.setText(String.format("Minimal detail: %.2f", detailSlider.getValue() / 100.0));
        clusterLabel.setText("Minimal cluster size: " + clusterSlider.getValue());
        blockLabel.setText("Block size: " + blockSlider.getValue());
        maxSizeLabel.setText("Maximal image size: " + maxSizeSlider.getValue());
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
        similaritySlider.setEnabled(enabled);
        detailSlider.setEnabled(enabled);
        clusterSlider.setEnabled(enabled);
        blockSlider.setEnabled(enabled);
        maxSizeSlider.setEnabled(enabled);
        quantizedCheck.setEnabled(enabled);
        resetControlsButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ API

    /** Load the given file's image in the background and analyse it. */
    public void setFile(AbstractFile file) {
        reset();
        infoLabel.setText("Loading...");
        loader = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                java.awt.image.BufferedImage img;
                try (ReadContentInputStream in = new ReadContentInputStream(file)) {
                    img = ImageIO.read(in);
                }
                if (img == null) {
                    return null;
                }
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
                    // show the plain image straight away while the analysis runs
                    canvas.setImage(img, true);
                    resetViewOnNextRender = false;
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
        pairSet = null;
        canvas.setImage(null, true);
        infoLabel.setText(" ");
        resultLabel.setText(" ");
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    private static class RenderResult {

        final BufferedImage image;
        final String info;

        RenderResult(BufferedImage image, String info) {
            this.image = image;
            this.info = info;
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
        final double similarity = similaritySlider.getValue() / 100.0;
        final double detail = detailSlider.getValue() / 100.0;
        final int minCluster = clusterSlider.getValue();
        final int blockSize = blockSlider.getValue();
        final int maxSize = maxSizeSlider.getValue();
        final boolean showQuantized = quantizedCheck.isSelected();

        resultLabel.setText("Analysing...");

        SwingWorker<RenderResult, Void> worker = new SwingWorker<RenderResult, Void>() {
            @Override
            protected RenderResult doInBackground() {
                int w = src.getWidth();
                int h = src.getHeight();
                int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

                Prepared prep = prepared;
                if (prep == null || prep.maxSize != maxSize) {
                    prep = prepare(s, w, h, maxSize, this::isCancelled);
                    if (prep == null) {
                        return null;
                    }
                    if (source == src) {
                        prepared = prep;
                    }
                }

                PairSet ps = pairSet;
                int simKey = similaritySlider.getValue();
                int detailKey = detailSlider.getValue();
                if (ps == null || ps.maxSize != maxSize || ps.blockSize != blockSize
                        || ps.simKey != simKey || ps.detailKey != detailKey) {
                    ps = findPairs(prep, blockSize, similarity, detail, this::isCancelled);
                    if (ps == null) {
                        return null;
                    }
                    ps.maxSize = maxSize;
                    ps.blockSize = blockSize;
                    ps.simKey = simKey;
                    ps.detailKey = detailKey;
                    if (source == src) {
                        pairSet = ps;
                    }
                }
                return compose(src, prep, ps, similarity, minCluster, showQuantized, this::isCancelled);
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
                        resultLabel.setText(r.info);
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Clone detection failed", ex);
                    resultLabel.setText("Clone detection failed");
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    // -------------------------------------------------------- preparation

    /** Luminance at the analysis size plus integral images for fast block statistics. */
    private static class Prepared {

        final int maxSize;
        final int pw;
        final int ph;
        final int[] lum;      // pw * ph, 0..255
        final int[] sum;      // (pw+1) * (ph+1) integral image of lum
        final long[] sq;      // integral image of lum^2

        Prepared(int maxSize, int pw, int ph, int[] lum, int[] sum, long[] sq) {
            this.maxSize = maxSize;
            this.pw = pw;
            this.ph = ph;
            this.lum = lum;
            this.sum = sum;
            this.sq = sq;
        }

        int rectSum(int x, int y, int w, int h) {
            int w1 = pw + 1;
            return sum[(y + h) * w1 + x + w] - sum[y * w1 + x + w] - sum[(y + h) * w1 + x] + sum[y * w1 + x];
        }

        long rectSq(int x, int y, int w, int h) {
            int w1 = pw + 1;
            return sq[(y + h) * w1 + x + w] - sq[y * w1 + x + w] - sq[(y + h) * w1 + x] + sq[y * w1 + x];
        }
    }

    static Prepared prepare(int[] src, int w, int h, int maxSize, BooleanSupplier cancelled) {
        int pw = w;
        int ph = h;
        int[] rgb = src;
        if (Math.max(w, h) > maxSize) {
            double f = (double) Math.max(w, h) / maxSize;
            pw = Math.max(1, (int) Math.round(w / f));
            ph = Math.max(1, (int) Math.round(h / f));
            rgb = boxDownscale(src, w, h, pw, ph);
        }
        int[] lum = new int[pw * ph];
        for (int i = 0; i < lum.length; i++) {
            int p = rgb[i];
            lum[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        }
        int w1 = pw + 1;
        int[] sum = new int[w1 * (ph + 1)];
        long[] sq = new long[w1 * (ph + 1)];
        for (int y = 0; y < ph; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            int rowSum = 0;
            long rowSq = 0;
            for (int x = 0; x < pw; x++) {
                int v = lum[y * pw + x];
                rowSum += v;
                rowSq += (long) v * v;
                sum[(y + 1) * w1 + x + 1] = sum[y * w1 + x + 1] + rowSum;
                sq[(y + 1) * w1 + x + 1] = sq[y * w1 + x + 1] + rowSq;
            }
        }
        return new Prepared(maxSize, pw, ph, lum, sum, sq);
    }

    /** Reduce an image to tw x th by averaging the block of source pixels under each target pixel. */
    static int[] boxDownscale(int[] src, int w, int h, int tw, int th) {
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int y0 = (int) ((long) ty * h / th);
            int y1 = Math.max(y0 + 1, (int) ((long) (ty + 1) * h / th));
            for (int tx = 0; tx < tw; tx++) {
                int x0 = (int) ((long) tx * w / tw);
                int x1 = Math.max(x0 + 1, (int) ((long) (tx + 1) * w / tw));
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int y = y0; y < y1 && y < h; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1 && x < w; x++) {
                        int p = src[row + x];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        count++;
                    }
                }
                if (count == 0) {
                    count = 1;
                }
                out[ty * tw + tx] = 0xFF000000
                        | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- matching

    /** Block size N means blocks of 2N x 2N pixels. */
    static int blockSide(int blockSize) {
        return 2 * blockSize;
    }

    /** Allowed rms difference between matching blocks, in grey levels. */
    static double tolerance(double similarity) {
        return (1.0 - similarity) * 32.0;
    }

    /** Quantisation step used for the sort keys (and for the quantized image). */
    static double quantStep(double similarity) {
        return Math.max(3.0, tolerance(similarity) * 1.5 + 2.0);
    }

    /** Matching block pairs found for one set of parameters. */
    private static class PairSet {

        int maxSize;
        int blockSize;
        int simKey;
        int detailKey;
        int bw;                 // blocks per row
        int side;
        int candidates;         // blocks with enough detail
        boolean truncated;
        long[] pairs = new long[0]; // sorted, unique: (indexA << 22) | indexB with indexA < indexB
    }

    /** Growable list of longs. */
    private static class LongList {

        long[] data = new long[1024];
        int size;

        boolean add(long v) {
            if (size >= MAX_PAIRS) {
                return false;
            }
            if (size == data.length) {
                data = Arrays.copyOf(data, Math.min(MAX_PAIRS, data.length * 2));
            }
            data[size++] = v;
            return true;
        }
    }

    private static final long INDEX_MASK = (1L << 22) - 1;

    /** Descriptor of the block at (x, y): mean, horizontal, vertical and diagonal change. */
    private static void features(Prepared p, int x, int y, int side, double[] f) {
        int half = side / 2;
        double tl = p.rectSum(x, y, half, half);
        double tr = p.rectSum(x + half, y, half, half);
        double bl = p.rectSum(x, y + half, half, half);
        double br = p.rectSum(x + half, y + half, half, half);
        double area = (double) half * half;
        f[0] = (tl + tr + bl + br) / (4 * area);
        f[1] = (tr + br - tl - bl) / (2 * area);
        f[2] = (bl + br - tl - tr) / (2 * area);
        f[3] = (tl + br - tr - bl) / (2 * area);
    }

    /** @return the matches, or null if cancelled. */
    static PairSet findPairs(Prepared p, int blockSize, double similarity, double minDetail,
            BooleanSupplier cancelled) {
        PairSet res = new PairSet();
        final int side = blockSide(blockSize);
        final int bw = p.pw - side + 1;
        final int bh = p.ph - side + 1;
        res.side = side;
        res.bw = Math.max(bw, 0);
        if (bw <= 0 || bh <= 0) {
            return res;
        }
        final int total = bw * bh;

        // 1. blocks with enough detail
        int[] idxs = new int[total];
        int m = 0;
        final double area = (double) side * side;
        for (int y = 0; y < bh; y++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            for (int x = 0; x < bw; x++) {
                double mean = p.rectSum(x, y, side, side) / area;
                double var = p.rectSq(x, y, side, side) / area - mean * mean;
                if (Math.sqrt(Math.max(var, 0)) >= minDetail) {
                    idxs[m++] = y * bw + x;
                }
            }
        }
        res.candidates = m;

        // 2. sort blocks by quantised descriptor (two grids shifted by half a step) and compare neighbours
        final double tol = tolerance(similarity);
        final double limit = tol * tol * area;
        final double q = quantStep(similarity);
        LongList list = new LongList();
        long[] keys = new long[m];
        double[] f = new double[4];

        for (int pass = 0; pass < 2; pass++) {
            double off = pass == 0 ? 0 : q / 2;
            for (int k = 0; k < m; k++) {
                if ((k & 0xFFFF) == 0 && cancelled.getAsBoolean()) {
                    return null;
                }
                int i = idxs[k];
                features(p, i % bw, i / bw, side, f);
                long hash = 1469598103934665603L;
                for (int d = 0; d < 4; d++) {
                    long cell = (long) Math.floor((f[d] + off) / q);
                    hash = (hash ^ cell) * 1099511628211L;
                    hash ^= hash >>> 29;
                }
                keys[k] = ((hash >>> 22) << 22) | i;
            }
            Arrays.parallelSort(keys, 0, m);

            for (int k = 0; k < m; k++) {
                if ((k & 0xFFFF) == 0 && cancelled.getAsBoolean()) {
                    return null;
                }
                long group = keys[k] >>> 22;
                int ia = (int) (keys[k] & INDEX_MASK);
                int xa = ia % bw;
                int ya = ia / bw;
                for (int j = k + 1; j < m && j <= k + WINDOW; j++) {
                    if ((keys[j] >>> 22) != group) {
                        break;
                    }
                    int ib = (int) (keys[j] & INDEX_MASK);
                    int xb = ib % bw;
                    int yb = ib / bw;
                    int dx = xb - xa;
                    int dy = yb - ya;
                    if (Math.max(Math.abs(dx), dy) < side) {
                        continue; // blocks overlap
                    }
                    if (rmsWithin(p.lum, p.pw, xa, ya, xb, yb, side, limit)) {
                        if (!list.add(((long) ia << 22) | ib)) {
                            res.truncated = true;
                        }
                    }
                }
            }
        }

        // 3. remove duplicates (the same pair can be found in both passes)
        long[] all = Arrays.copyOf(list.data, list.size);
        Arrays.sort(all);
        int u = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[u++] = all[i];
            }
        }
        res.pairs = Arrays.copyOf(all, u);
        return res;
    }

    /** True if the sum of squared differences of the two blocks is at most limit. */
    private static boolean rmsWithin(int[] lum, int pw, int xa, int ya, int xb, int yb, int side, double limit) {
        double acc = 0;
        for (int r = 0; r < side; r++) {
            int ra = (ya + r) * pw + xa;
            int rb = (yb + r) * pw + xb;
            for (int c = 0; c < side; c++) {
                int d = lum[ra + c] - lum[rb + c];
                acc += d * d;
            }
            if (acc > limit) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ composing

    /** Group matches by offset, drop small clusters, and draw the result. Returns null if cancelled. */
    static RenderResult compose(BufferedImage src, Prepared p, PairSet ps, double similarity, int minCluster,
            boolean showQuantized, BooleanSupplier cancelled) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        final int pw = p.pw;
        final int ph = p.ph;
        final int side = ps.side;
        final int bw = ps.bw;

        // --- votes per offset (dy >= 0 always, because indexA < indexB)
        final int stride = 2 * pw - 1;
        Map<Integer, Integer> votes = new HashMap<>();
        for (long pair : ps.pairs) {
            int ia = (int) (pair >>> 22);
            int ib = (int) (pair & INDEX_MASK);
            int dx = ib % bw - ia % bw;
            int dy = ib / bw - ia / bw;
            votes.merge(dy * stride + dx + pw - 1, 1, Integer::sum);
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }

        // --- offsets that form big enough clusters, strongest first
        List<Map.Entry<Integer, Integer>> kept = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() >= minCluster) {
                kept.add(e);
            }
        }
        kept.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (kept.size() > MAX_OFFSETS_SHOWN) {
            kept = kept.subList(0, MAX_OFFSETS_SHOWN);
        }
        Map<Integer, Integer> rank = new HashMap<>();
        Map<Integer, Integer> lineStep = new HashMap<>();
        int blocks = 0;
        for (int r = 0; r < kept.size(); r++) {
            rank.put(kept.get(r).getKey(), r);
            lineStep.put(kept.get(r).getKey(), Math.max(1, kept.get(r).getValue() / 40));
            blocks += kept.get(r).getValue();
        }

        // --- colour the matching blocks and collect some connecting lines
        int[] palette = new int[Math.max(1, kept.size())];
        for (int r = 0; r < palette.length; r++) {
            float hue = (float) ((r * 0.61803398875) % 1.0);
            palette[r] = (0x96 << 24) | (Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF);
        }
        int[] overlay = new int[pw * ph];
        List<float[]> lines = new ArrayList<>();
        Map<Integer, Integer> seen = new HashMap<>();
        for (long pair : ps.pairs) {
            int ia = (int) (pair >>> 22);
            int ib = (int) (pair & INDEX_MASK);
            int xa = ia % bw, ya = ia / bw, xb = ib % bw, yb = ib / bw;
            int key = (yb - ya) * stride + (xb - xa) + pw - 1;
            Integer rk = rank.get(key);
            if (rk == null) {
                continue;
            }
            int colour = palette[rk];
            paintBlock(overlay, pw, xa, ya, side, colour);
            paintBlock(overlay, pw, xb, yb, side, colour);
            int count = seen.merge(key, 1, Integer::sum);
            if ((count - 1) % lineStep.get(key) == 0) {
                lines.add(new float[]{xa + side / 2f, ya + side / 2f, xb + side / 2f, yb + side / 2f, rk});
            }
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }

        // --- base image: original, or the quantized luminance
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            if (showQuantized) {
                BufferedImage q = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                int[] qd = ((DataBufferInt) q.getRaster().getDataBuffer()).getData();
                double step = quantStep(similarity);
                for (int i = 0; i < qd.length; i++) {
                    int v = (int) Math.min(255, Math.floor(p.lum[i] / step) * step + step / 2);
                    qd[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(q, 0, 0, w, h, null);
            } else {
                g.drawImage(src, 0, 0, null);
            }

            BufferedImage ov = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
            System.arraycopy(overlay, 0, ((DataBufferInt) ov.getRaster().getDataBuffer()).getData(), 0, overlay.length);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(ov, 0, 0, w, h, null);

            double sx = (double) w / pw;
            double sy = (double) h / ph;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.5f));
            for (float[] l : lines) {
                g.setColor(new Color((palette[(int) l[4]] & 0xFFFFFF) | (0xDC << 24), true));
                g.drawLine((int) Math.round(l[0] * sx), (int) Math.round(l[1] * sy),
                        (int) Math.round(l[2] * sx), (int) Math.round(l[3] * sy));
            }
        } finally {
            g.dispose();
        }

        String info = String.format("<html>Analysed at %d x %d px<br>%d blocks with enough detail<br>"
                + "%d clone offset%s, %d matching block pairs%s</html>",
                pw, ph, ps.candidates, kept.size(), kept.size() == 1 ? "" : "s", blocks,
                ps.truncated ? "<br>(match limit reached - raise the similarity or detail)" : "");
        return new RenderResult(out, info);
    }

    private static void paintBlock(int[] overlay, int pw, int x, int y, int side, int colour) {
        for (int r = 0; r < side; r++) {
            int start = (y + r) * pw + x;
            Arrays.fill(overlay, start, start + side, colour);
        }
    }
}
