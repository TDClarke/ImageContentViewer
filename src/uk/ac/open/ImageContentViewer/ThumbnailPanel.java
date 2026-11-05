package uk.ac.open.ImageContentViewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.logging.Level;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
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
 * Thumbnail analysis. Many cameras store a small JPEG thumbnail in the EXIF
 * data. Editing software often leaves it untouched, so differences between the
 * thumbnail and the image can reveal that the image was changed.
 *
 * The embedded thumbnail is extracted from the file's EXIF data, scaled up to
 * the size of the image and blended with the original image at the chosen
 * opacity. "Show differences" replaces the thumbnail with the difference between
 * the thumbnail and the image (the image is first reduced to the thumbnail's
 * size so that fine detail does not show up as difference), auto-amplified.
 *
 * Reuses ChannelImagePanel.ImageCanvas (zoom / rotate) and SidebarPanel.
 */
public class ThumbnailPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(ThumbnailPanel.class.getName());

    private static final double MIN_ZOOM_RATIO = 0.0625;
    private static final double MAX_ZOOM_RATIO = 10.0;
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
    private final JCheckBox differencesCheck = new JCheckBox("Show differences");
    private final JSlider opacitySlider = new JSlider(0, 1000, DEFAULT_OPACITY);
    private final JLabel opacityLabel = new JLabel();
    private final JButton resetControlsButton = new JButton("Reset controls");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");
    private final JLabel thumbTitle = new JLabel("Embedded thumbnail:");
    private final JLabel thumbPreview = new JLabel();

    private final ChannelImagePanel.ImageCanvas canvas = new ChannelImagePanel.ImageCanvas();
    private final JScrollPane scrollPane = new JScrollPane(canvas);

    private volatile LoadResult data;
    private boolean resetViewOnNextRender = true;
    private SwingWorker<LoadResult, Void> loader;
    private SwingWorker<BufferedImage, Void> renderer;
    private final Timer renderTimer;

    public ThumbnailPanel() {
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

        JLabel help = new JLabel("<html>Compares the thumbnail embedded in the file's EXIF data with the image. "
                + "A thumbnail that does not match the image can indicate the image was edited.</html>");
        help.setAlignmentX(LEFT_ALIGNMENT);

        differencesCheck.setAlignmentX(LEFT_ALIGNMENT);
        differencesCheck.setToolTipText("Show the (amplified) difference between the thumbnail and the image");
        differencesCheck.addActionListener(e -> renderTimer.restart());

        opacitySlider.setAlignmentX(LEFT_ALIGNMENT);
        opacitySlider.setToolTipText("Opacity of the thumbnail / differences over the original image");
        opacitySlider.addChangeListener(e -> {
            updateLabels();
            renderTimer.restart();
        });
        opacityLabel.setAlignmentX(LEFT_ALIGNMENT);

        resetControlsButton.setAlignmentX(LEFT_ALIGNMENT);
        resetControlsButton.setFocusable(false);
        resetControlsButton.addActionListener(e -> {
            differencesCheck.setSelected(false);
            opacitySlider.setValue(DEFAULT_OPACITY);
            renderTimer.restart();
        });

        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        noteLabel.setAlignmentX(LEFT_ALIGNMENT);
        thumbTitle.setAlignmentX(LEFT_ALIGNMENT);
        thumbTitle.setVisible(false);
        thumbPreview.setAlignmentX(LEFT_ALIGNMENT);
        thumbPreview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        thumbPreview.setVisible(false);

        sidebar.add(help);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(differencesCheck);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(opacityLabel);
        sidebar.add(opacitySlider);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(resetControlsButton);
        sidebar.add(Box.createVerticalStrut(16));
        sidebar.add(statusLabel);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(noteLabel);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(thumbTitle);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(thumbPreview);
        sidebar.add(Box.createVerticalGlue());

        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebarScroll.setPreferredSize(new Dimension(250, 100));
        add(sidebarScroll, BorderLayout.EAST);
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
        differencesCheck.setEnabled(enabled);
        opacitySlider.setEnabled(enabled);
        resetControlsButton.setEnabled(enabled);
    }

    // ------------------------------------------------------------------ API

    /** Load the file in the background, extract the EXIF thumbnail and display the result. */
    public void setFile(AbstractFile file) {
        reset();
        statusLabel.setText("Loading...");
        loader = new SwingWorker<LoadResult, Void>() {
            @Override
            protected LoadResult doInBackground() throws Exception {
                byte[] bytes;
                try (ReadContentInputStream in = new ReadContentInputStream(file)) {
                    bytes = in.readAllBytes();
                }
                return analyse(bytes);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                LoadResult r;
                try {
                    r = get();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Unable to analyse thumbnail of " + file.getName(), ex);
                    r = null;
                }
                data = r;
                showLoaded(r);
            }
        };
        loader.execute();
    }

    private void showLoaded(LoadResult r) {
        if (r == null || r.image == null) {
            statusLabel.setText("Image could not be read");
            noteLabel.setText(" ");
            canvas.setImage(null, true);
            setControlsEnabled(false);
            return;
        }
        int w = r.image.getWidth();
        int h = r.image.getHeight();
        if (r.thumbnail == null) {
            statusLabel.setText("<html>Image: " + w + " x " + h + " px<br>No embedded EXIF thumbnail found.</html>");
            noteLabel.setText(" ");
            canvas.setImage(r.image, true); // just show the image
            setControlsEnabled(false);
            return;
        }

        statusLabel.setText(String.format("<html>Image: %d x %d px<br>Thumbnail: %d x %d px<br>Difference gain: x%.1f</html>",
                w, h, r.thumbnail.getWidth(), r.thumbnail.getHeight(), r.gain));
        noteLabel.setText(r.note == null ? " " : "<html><font color='#e08000'>" + r.note + "</font></html>");

        int pw = 200;
        Image preview = r.thumbnail.getWidth() > pw
                ? r.thumbnail.getScaledInstance(pw, -1, Image.SCALE_SMOOTH) : r.thumbnail;
        thumbPreview.setIcon(new ImageIcon(preview));
        thumbTitle.setVisible(true);
        thumbPreview.setVisible(true);

        setControlsEnabled(true);
        resetViewOnNextRender = true;
        startRender();
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
        data = null;
        canvas.setImage(null, true);
        statusLabel.setText(" ");
        noteLabel.setText(" ");
        thumbPreview.setIcon(null);
        thumbTitle.setVisible(false);
        thumbPreview.setVisible(false);
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------ rendering

    private void startRender() {
        final LoadResult r = data;
        if (r == null || r.image == null || r.thumbnailUp == null) {
            return;
        }
        if (renderer != null) {
            renderer.cancel(true);
        }
        final boolean showDiff = differencesCheck.isSelected();
        final double opacity = opacitySlider.getValue() / 1000.0;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage overlay = showDiff ? r.differenceUp : r.thumbnailUp;
                int w = r.image.getWidth();
                int h = r.image.getHeight();
                BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                blend(((DataBufferInt) r.image.getRaster().getDataBuffer()).getData(),
                        ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData(),
                        ((DataBufferInt) out.getRaster().getDataBuffer()).getData(),
                        w, h, opacity, this);
                return out;
            }

            @Override
            protected void done() {
                if (isCancelled() || data != r) {
                    return;
                }
                try {
                    canvas.setImage(get(), resetViewOnNextRender);
                    resetViewOnNextRender = false;
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Thumbnail render failed", ex);
                }
            }
        };
        renderer = worker;
        worker.execute();
    }

    /** out = original * (1 - opacity) + overlay * opacity. */
    static void blend(int[] orig, int[] overlay, int[] dst, int w, int h, double opacity, SwingWorker<?, ?> worker) {
        final int opI = (int) Math.round(opacity * 256.0);
        final int keep = 256 - opI;
        for (int y = 0; y < h; y++) {
            if (worker.isCancelled()) {
                return;
            }
            for (int i = y * w, end = i + w; i < end; i++) {
                int p = orig[i];
                int q = overlay[i];
                int r = (((p >> 16) & 0xFF) * keep + ((q >> 16) & 0xFF) * opI + 128) >> 8;
                int g = (((p >> 8) & 0xFF) * keep + ((q >> 8) & 0xFF) * opI + 128) >> 8;
                int b = ((p & 0xFF) * keep + (q & 0xFF) * opI + 128) >> 8;
                dst[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }

    // --------------------------------------------------------------- analysis

    private static class LoadResult {

        BufferedImage image;        // TYPE_INT_ARGB, full size
        BufferedImage thumbnail;    // as embedded, TYPE_INT_ARGB (null if none)
        BufferedImage thumbnailUp;  // thumbnail scaled up to the image size
        BufferedImage differenceUp; // amplified |thumbnail - reduced image|, scaled up to the image size
        double gain = 1;
        String note;
    }

    /** Decode the image, extract the EXIF thumbnail and prepare the overlays. */
    static LoadResult analyse(byte[] bytes) throws Exception {
        LoadResult r = new LoadResult();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            return r;
        }
        r.image = toArgb(img);
        final int w = r.image.getWidth();
        final int h = r.image.getHeight();

        byte[] thumbBytes = extractExifThumbnail(bytes);
        if (thumbBytes == null) {
            return r;
        }
        BufferedImage thumb = null;
        try {
            thumb = ImageIO.read(new ByteArrayInputStream(thumbBytes));
        } catch (Exception ex) {
            logger.log(Level.FINE, "Embedded thumbnail could not be decoded", ex);
        }
        if (thumb == null) {
            return r;
        }
        r.thumbnail = toArgb(thumb);
        final int tw = r.thumbnail.getWidth();
        final int th = r.thumbnail.getHeight();

        double imgAspect = (double) w / h;
        double thumbAspect = (double) tw / th;
        if (Math.abs(imgAspect / thumbAspect - 1.0) > 0.03) {
            r.note = String.format("The thumbnail's aspect ratio (%.2f) differs from the image's (%.2f). "
                    + "The image may have been cropped, resized or rotated; differences are not reliable.",
                    thumbAspect, imgAspect);
        }

        // Thumbnail scaled up to the image size.
        r.thumbnailUp = scaleTo(r.thumbnail, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // Difference at thumbnail resolution: |thumbnail - image reduced to the thumbnail's size|.
        int[] small = boxDownscale(((DataBufferInt) r.image.getRaster().getDataBuffer()).getData(), w, h, tw, th);
        int[] t = ((DataBufferInt) r.thumbnail.getRaster().getDataBuffer()).getData();
        int[] diff = new int[tw * th];
        int[] hist = new int[256];
        for (int i = 0; i < diff.length; i++) {
            int a = t[i];
            int b = small[i];
            int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
            int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
            int db = Math.abs((a & 0xFF) - (b & 0xFF));
            diff[i] = (dr << 16) | (dg << 8) | db;
            hist[Math.max(dr, Math.max(dg, db))]++;
        }
        // Auto gain: bring the 99.5th percentile difference up to full scale (max x32).
        long target = (long) Math.ceil(diff.length * 0.995);
        long acc = 0;
        int p = 255;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= target) {
                p = v;
                break;
            }
        }
        r.gain = Math.min(32.0, 255.0 / Math.max(p, 1));
        BufferedImage diffSmall = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        int[] ds = ((DataBufferInt) diffSmall.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < ds.length; i++) {
            int d = diff[i];
            int rr = Math.min(255, (int) Math.round(((d >> 16) & 0xFF) * r.gain));
            int gg = Math.min(255, (int) Math.round(((d >> 8) & 0xFF) * r.gain));
            int bb = Math.min(255, (int) Math.round((d & 0xFF) * r.gain));
            ds[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
        r.differenceUp = scaleTo(diffSmall, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        return r;
    }

    private static BufferedImage toArgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage scaleTo(BufferedImage small, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.drawImage(small, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
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

    // ---------------------------------------------------------- EXIF parsing

    /**
     * Find the JPEG thumbnail stored in the EXIF (APP1) segment's second IFD.
     * Returns the thumbnail's JPEG bytes, or null if there is none.
     */
    static byte[] extractExifThumbnail(byte[] d) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
            return null; // not a JPEG
        }
        int pos = 2;
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            int marker = d[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // markers without a length
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                break; // start of scan / end of image: no more headers
            }
            int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (len < 2) {
                break;
            }
            int dataStart = pos + 4;
            int segEnd = pos + 2 + len;
            if (segEnd > d.length) {
                break;
            }
            if (marker == 0xE1 && segEnd - dataStart >= 14
                    && d[dataStart] == 'E' && d[dataStart + 1] == 'x' && d[dataStart + 2] == 'i'
                    && d[dataStart + 3] == 'f' && d[dataStart + 4] == 0 && d[dataStart + 5] == 0) {
                byte[] t = thumbnailFromTiff(d, dataStart + 6, segEnd);
                if (t != null) {
                    return t;
                }
            }
            pos = segEnd;
        }
        return null;
    }

    private static int u16(byte[] d, int p, boolean le) {
        int a = d[p] & 0xFF;
        int b = d[p + 1] & 0xFF;
        return le ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int p, boolean le) {
        long a = u16(d, p, le);
        long b = u16(d, p + 2, le);
        return le ? (b << 16) | a : (a << 16) | b;
    }

    /** @param tiff offset of the TIFF header; @param limit end of the APP1 segment */
    private static byte[] thumbnailFromTiff(byte[] d, int tiff, int limit) {
        if (tiff + 8 > limit) {
            return null;
        }
        boolean le;
        if (d[tiff] == 'I' && d[tiff + 1] == 'I') {
            le = true;
        } else if (d[tiff] == 'M' && d[tiff + 1] == 'M') {
            le = false;
        } else {
            return null;
        }
        if (u16(d, tiff + 2, le) != 42) {
            return null;
        }
        long ifd0 = u32(d, tiff + 4, le);
        long p0 = tiff + ifd0;
        if (ifd0 < 8 || p0 + 2 > limit) {
            return null;
        }
        int count0 = u16(d, (int) p0, le);
        long nextPtr = p0 + 2 + (long) count0 * 12;
        if (nextPtr + 4 > limit) {
            return null;
        }
        long ifd1 = u32(d, (int) nextPtr, le);
        if (ifd1 == 0) {
            return null; // no IFD1, so no thumbnail
        }
        long p1 = tiff + ifd1;
        if (p1 + 2 > limit) {
            return null;
        }
        int count1 = u16(d, (int) p1, le);
        long jpegOffset = -1;
        long jpegLength = -1;
        for (int i = 0; i < count1; i++) {
            long e = p1 + 2 + (long) i * 12;
            if (e + 12 > limit) {
                break;
            }
            int tag = u16(d, (int) e, le);
            int type = u16(d, (int) e + 2, le);
            long value = type == 3 ? u16(d, (int) e + 8, le) : u32(d, (int) e + 8, le);
            if (tag == 0x0201) {
                jpegOffset = value;
            } else if (tag == 0x0202) {
                jpegLength = value;
            }
        }
        if (jpegOffset < 0 || jpegLength <= 4) {
            return null;
        }
        long start = tiff + jpegOffset;
        long end = start + jpegLength;
        if (start < 0 || end > d.length) {
            return null;
        }
        if ((d[(int) start] & 0xFF) != 0xFF || (d[(int) start + 1] & 0xFF) != 0xD8) {
            return null; // not a JPEG thumbnail
        }
        return Arrays.copyOfRange(d, (int) start, (int) end);
    }
}
