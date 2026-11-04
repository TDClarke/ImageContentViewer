package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import javax.imageio.ImageIO;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * Sub-tab that shows the luminance gradient of an image.
 */
@ServiceProvider(service = ImageSubViewer.class, position = 4)
public class LuminanceGradientSubViewer implements ImageSubViewer {

    private LuminanceGradientPanel panel;

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
        return "Luminance Gradient";
    }

    @Override
    public String getToolTip() {
        return "Show how brightness changes across the image to reveal edges and manipulation";
    }

    @Override
    public ImageSubViewer createInstance() {
        return new LuminanceGradientSubViewer();
    }

    @Override
    public Component getComponent() {
        if (panel == null) {
            panel = new LuminanceGradientPanel();
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
        String mime = file.getMIMEType();
        if (mime != null && mime.toLowerCase().startsWith("image/")) {
            return true;
        }
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
        return 1;
    }
}
