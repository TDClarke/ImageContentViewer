package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import javax.imageio.ImageIO;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * Sub-tab that shows an image as original / greyscale / R / G / B.
 */
@ServiceProvider(service = ImageSubViewer.class, position = 1)
public class RGBImageSubViewer implements ImageSubViewer {

    private ChannelImagePanel panel;

    @Override
    public void setNode(Node selectedNode) {
        if (selectedNode == null || !isSupported(selectedNode)) {
            resetComponent();
            return;
        }
        AbstractFile file = selectedNode.getLookup().lookup(AbstractFile.class);
        getComponent();
        panel.setFile(file);
    }

    @Override
    public String getTitle() {
        return "RGB";
    }

    @Override
    public String getToolTip() {
        return "View the image as greyscale or as its red, green or blue channel";
    }

    @Override
    public ImageSubViewer createInstance() {
        return new RGBImageSubViewer();
    }

    @Override
    public Component getComponent() {
        if (panel == null) {
            panel = new ChannelImagePanel();
        }
        return panel;
    }

    @Override
    public void resetComponent() {
        getComponent();
        panel.reset();
    }

    @Override
    public boolean isSupported(Node node) {
        if (node == null) {
            return false;
        }
        AbstractFile file = node.getLookup().lookup(AbstractFile.class);
        if (file == null || file.isDir() || file.getSize() <= 0) {
            return false;
        }
        // Prefer the MIME type detected by Autopsy's file type module.
        String mime = file.getMIMEType();
        if (mime != null && mime.toLowerCase().startsWith("image/")) {
            return true;
        }
        // Fall back to the file extension (if the file type module hasn't run).
        String ext = file.getNameExtension();
        if (ext == null || ext.isEmpty()) {
            return false;
        }
        for (String suffix : ImageIO.getReaderFileSuffixes()) {
            if (suffix.equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int isPreferred(Node node) {
        // Stay below Autopsy's built-in media viewer (which uses 7 for images);
        // the user can still click the tab.
        return 1;
    }
}
