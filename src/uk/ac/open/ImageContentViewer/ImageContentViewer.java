package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataContentViewer;
import org.sleuthkit.datamodel.AbstractFile;

/**
 * A DataContentViewer that hosts ImageSubViewer sub-tabs (same pattern as
 * Autopsy's TextContentViewer). Position 2 as requested; note that Autopsy's
 * built-in Text viewer also uses position 2, so relative order between the two
 * is not guaranteed. Use 3 if you want it deterministically after Text.
 */
@ServiceProvider(service = DataContentViewer.class, position = 2)
public class ImageContentViewer implements DataContentViewer {

    private final ImageContentViewerPanel panel;
    private volatile Node currentNode = null;

    /** No-arg constructor for the main (Lookup) instance. */
    public ImageContentViewer() {
        this(true);
    }

    private ImageContentViewer(boolean isMain) {
        panel = new ImageContentViewerPanel(isMain);
    }

    @Override
    public void setNode(Node selectedNode) {
        currentNode = selectedNode;
        panel.setNode(selectedNode);
    }

    @Override
    public String getTitle() {
        return "Image Analysis";
    }

    @Override
    public String getToolTip() {
        return "Displays the selected image as greyscale or as individual colour channels";
    }

    @Override
    public DataContentViewer createInstance() {
        return new ImageContentViewer(false);
    }

    @Override
    public Component getComponent() {
        return panel;
    }

    @Override
    public void resetComponent() {
        currentNode = null;
        panel.setupTabs(null);
    }

    @Override
    public boolean isSupported(Node node) {
        if (node == null) {
            return false;
        }
        AbstractFile file = node.getLookup().lookup(AbstractFile.class);
        if (file != null && (file.isDir() || file.getSize() == 0)) {
            return false;
        }
        return panel.isSupported(node);
    }

    @Override
    public int isPreferred(Node node) {
        return panel.isPreferred(node);
    }
}
