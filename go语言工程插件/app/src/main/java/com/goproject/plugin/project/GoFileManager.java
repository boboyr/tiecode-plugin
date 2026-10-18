package com.goproject.plugin.project;

import com.rapid.api.component.widget.tree.TreeItemViewHolder;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.file.ProjectFileUiEvent;
import com.rapid.api.project.DefaultProjectFileManager;
import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.TreeNode;
import com.rapid.util.android.ContextUtils;
import com.goproject.plugin.R;

public class GoFileManager extends DefaultProjectFileManager<GoProject>
        implements ProjectFileUiEvent {
    @Override
    public void onBindView(TreeItemViewHolder holder, TreeNode<PSObject> node) {
        String name = node.getSource().getName();
        if (name.endsWith(".go")) {
            holder.loadIcon(
                    ContextUtils.getDrawable(
                            AndroidPlugins.getContext(this),
                            R.drawable.golang
                    )
            );
        }
    }
}
