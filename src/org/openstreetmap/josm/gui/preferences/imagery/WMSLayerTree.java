// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.preferences.imagery;

import java.awt.Component;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JTree;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreePath;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.LayerDetails;
import org.openstreetmap.josm.io.imagery.WMSImagery;

/**
 * The layer tree of a WMS server.
 */
public class WMSLayerTree {
    private final MutableTreeNode treeRootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeData = new DefaultTreeModel(treeRootNode);
    private final JTree layerTree = new JTree(treeData);
    private final List<LayerDetails> selectedLayers = new LinkedList<>();
    private LatLon checkBounds;

    /**
     * Returns the root node.
     * @return The root node
     */
    public MutableTreeNode getTreeRootNode() {
        return treeRootNode;
    }

    /**
     * Returns the {@code JTree}.
     * @return The {@code JTree}
     */
    public JTree getLayerTree() {
        return layerTree;
    }

    /**
     * Returns the list of selected layers.
     * @return the list of selected layers
     */
    public List<LayerDetails> getSelectedLayers() {
        return selectedLayers;
    }

    /**
     * Constructs a new {@code WMSLayerTree}.
     */
    public WMSLayerTree() {
        layerTree.setCellRenderer(new LayerTreeCellRenderer());
        layerTree.addTreeSelectionListener(new WMSTreeSelectionListener());
    }

    /**
     * Set coordinate to check {@linkplain LayerDetails#getBounds() layer bounds}
     * when {@linkplain #updateTree updating the tree}.
     * @param checkBounds the coordinate
     */
    public void setCheckBounds(LatLon checkBounds) {
        this.checkBounds = checkBounds;
    }

    void addLayersToTreeData(MutableTreeNode parent, Collection<LayerDetails> layers) {
        for (LayerDetails layerDetails : layers.stream()
                .filter(l -> checkBounds == null || l.getBounds() == null || l.getBounds().contains(checkBounds))
                .sorted(Comparator.comparing(LayerDetails::toString).reversed())
                .toArray(LayerDetails[]::new)
                ) {
            DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(layerDetails);
            addLayersToTreeData(treeNode, layerDetails.getChildren());
            treeData.insertNodeInto(treeNode, parent, 0);
        }
    }

    /**
     * Updates the whole tree with the given WMS imagery info. All previous content is removed
     * @param wms The imagery info for a given WMS server
     */
    public void updateTree(WMSImagery wms) {
        while (treeRootNode.getChildCount() > 0) {
            treeRootNode.remove(0);
        }
        treeRootNode.setUserObject(wms.buildRootUrl());
        updateTreeList(wms.getLayers());
    }

    /**
     * Updates the list of WMS layers.
     * @param layers The list of layers to add to the root node
     */
    public void updateTreeList(Collection<LayerDetails> layers) {
        addLayersToTreeData(getTreeRootNode(), layers);
        treeData.nodeStructureChanged(getTreeRootNode());
        getLayerTree().expandRow(0);
        getLayerTree().expandRow(1);
    }

    private static final class LayerTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                      boolean sel, boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf,
                    row, hasFocus);
            DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) value;
            Object userObject = treeNode.getUserObject();
            if (userObject instanceof LayerDetails) {
                LayerDetails ld = (LayerDetails) userObject;
                setEnabled(ld.isSelectable());
            }
            return this;
        }
    }

    private final class WMSTreeSelectionListener implements TreeSelectionListener {

        @Override
        public void valueChanged(TreeSelectionEvent e) {
            TreePath[] selectionRows = layerTree.getSelectionPaths();
            if (selectionRows == null) {
                return;
            }

            selectedLayers.clear();
            for (TreePath i : selectionRows) {
                Object userObject = ((DefaultMutableTreeNode) i.getLastPathComponent()).getUserObject();
                if (userObject instanceof LayerDetails) {
                    LayerDetails detail = (LayerDetails) userObject;
                    if (detail.isSelectable()) {
                        selectedLayers.add(detail);
                    }
                }
            }
            layerTree.firePropertyChange("selectedLayers", /*dummy values*/ false, true);
        }
    }
}
