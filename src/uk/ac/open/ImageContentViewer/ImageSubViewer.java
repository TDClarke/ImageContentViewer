package uk.ac.open.ImageContentViewer;

import java.awt.Component;
import org.openide.nodes.Node;

/**
 * Service interface for sub-tabs shown inside the ImageContentViewer.
 * Mirrors Autopsy's TextViewer. Implementations register with
 * {@code @ServiceProvider(service = ImageSubViewer.class, position = N)}.
 */
public interface ImageSubViewer {

    void setNode(Node selectedNode);

    String getTitle();

    String getToolTip();

    /** Create a new instance (used for non-main viewers). */
    ImageSubViewer createInstance();

    Component getComponent();

    void resetComponent();

    boolean isSupported(Node node);

    /** 0 = not preferred; higher = more preferred. */
    int isPreferred(Node node);
}
