package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import javax.imageio.ImageIO;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * Sub-tab that detects cloned (copy-moved) regions in an image.
 */
@ServiceProvider(service = ImageSubViewer.class, position = 9)
public class CloneDetectionSubViewer implements ImageSubViewer {

    private CloneDetectionPanel panel;

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
        return "Clone Detection";
    }

    @Override
    public String getToolTip() {
        return "Find regions that were copied within the image";
    }

    @Override
    public ImageSubViewer createInstance() {
        return new CloneDetectionSubViewer();
    }

    @Override
    public Component getComponent() {
        if (panel == null) {
            panel = new CloneDetectionPanel();
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
