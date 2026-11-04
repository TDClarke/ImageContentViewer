package uk.ac.open.ImageContentViewer;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.openide.nodes.Node;
import org.openide.util.Lookup;
import org.sleuthkit.autopsy.core.UserPreferences;

/**
 * Tabbed panel that displays all registered ImageSubViewers.
 */
public class ImageContentViewerPanel extends JPanel implements ChangeListener {

    private static final long serialVersionUID = 1L;

    private final List<UpdateWrapper> viewers = new ArrayList<>();
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private Node currentNode;
    private boolean listening = false;

    public ImageContentViewerPanel(boolean isMain) {
        setLayout(new BorderLayout());
        add(tabbedPane, BorderLayout.CENTER);

        Collection<? extends ImageSubViewer> found = Lookup.getDefault().lookupAll(ImageSubViewer.class);
        for (ImageSubViewer factory : found) {
            ImageSubViewer v = isMain ? factory : factory.createInstance();
            viewers.add(new UpdateWrapper(v));
            // The sub viewer manages its own scrolling/fit, so no JScrollPane here.
            tabbedPane.addTab(v.getTitle(), null, v.getComponent(), v.getToolTip());
        }
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            tabbedPane.setEnabledAt(i, false);
        }
    }

    boolean isSupported(Node node) {
        for (UpdateWrapper v : viewers) {
            if (v.isSupported(node)) {
                return true;
            }
        }
        return false;
    }

    int isPreferred(Node node) {
        int max = 0;
        for (UpdateWrapper v : viewers) {
            if (v.isSupported(node)) {
                max = Math.max(max, v.isPreferred(node));
            }
        }
        return max;
    }

    public void setNode(Node selectedNode) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            currentNode = selectedNode;
            setupTabs(selectedNode);
        } finally {
            setCursor(null);
        }
    }

    public void setupTabs(Node selectedNode) {
        if (viewers.isEmpty()) {
            return;
        }
        if (!listening) {
            tabbedPane.addChangeListener(this);
            listening = true;
        }

        int currTab = Math.max(0, tabbedPane.getSelectedIndex());
        int maxPreferred = 0;
        int preferredIndex = 0;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            UpdateWrapper v = viewers.get(i);
            v.resetComponent();
            if (selectedNode == null || !v.isSupported(selectedNode)) {
                tabbedPane.setEnabledAt(i, false);
            } else {
                tabbedPane.setEnabledAt(i, true);
                int p = v.isPreferred(selectedNode);
                if (p > maxPreferred) {
                    preferredIndex = i;
                    maxPreferred = p;
                }
            }
        }

        int tabIndex = UserPreferences.keepPreferredContentViewer() ? currTab : preferredIndex;
        UpdateWrapper v = viewers.get(tabIndex);
        if (!tabbedPane.isEnabledAt(tabIndex)) {
            v.resetComponent();
        } else {
            v.setNode(selectedNode);
        }
        tabbedPane.setSelectedIndex(tabIndex);
        tabbedPane.getSelectedComponent().repaint();
    }

    @Override
    public void stateChanged(ChangeEvent evt) {
        JTabbedPane pane = (JTabbedPane) evt.getSource();
        int tab = pane.getSelectedIndex();
        if (tab != -1 && pane.isEnabledAt(tab)) {
            UpdateWrapper v = viewers.get(tab);
            if (v.isOutdated()) {
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                try {
                    v.setNode(currentNode);
                } finally {
                    setCursor(null);
                }
            }
        }
    }

    /** Tracks which sub viewers need refreshing (lazy loading per tab). */
    private static class UpdateWrapper {

        private final ImageSubViewer wrapped;
        private boolean outdated = true;

        UpdateWrapper(ImageSubViewer wrapped) {
            this.wrapped = wrapped;
        }

        void setNode(Node n) {
            wrapped.setNode(n);
            outdated = false;
        }

        void resetComponent() {
            wrapped.resetComponent();
            outdated = true;
        }

        boolean isOutdated() {
            return outdated;
        }

        boolean isSupported(Node n) {
            return wrapped.isSupported(n);
        }

        int isPreferred(Node n) {
            return wrapped.isPreferred(n);
        }
    }
}
