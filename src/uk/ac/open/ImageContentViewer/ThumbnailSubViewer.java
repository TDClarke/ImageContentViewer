package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * Sub-tab that compares an image with its embedded EXIF thumbnail.
 */
@ServiceProvider(service = ImageSubViewer.class, position = 6)
public class ThumbnailSubViewer implements ImageSubViewer {

    private ThumbnailPanel panel;

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
        return "Thumbnail";
    }

    @Override
    public String getToolTip() {
        return "Compare the thumbnail embedded in the EXIF data with the image";
    }

    @Override
    public ImageSubViewer createInstance() {
        return new ThumbnailSubViewer();
    }

    @Override
    public Component getComponent() {
        if (panel == null) {
            panel = new ThumbnailPanel();
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
        // Only JPEG files carry the EXIF thumbnail this viewer reads.
        String mime = file.getMIMEType();
        if (mime != null && mime.toLowerCase().equals("image/jpeg")) {
            return true;
        }
        String ext = file.getNameExtension();
        if (ext == null) {
            return false;
        }
        ext = ext.toLowerCase();
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("jpe") || ext.equals("jfif");
    }

    @Override
    public int isPreferred(Node node) {
        return 1;
    }
}
