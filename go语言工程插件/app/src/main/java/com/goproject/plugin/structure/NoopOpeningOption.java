package com.goproject.plugin.structure;

import com.rapid.api.project.structure.PSObject;
import com.rapid.api.project.structure.extension.PSObjectOpeningOption;

/**
 * 「External Libraries」标记文件的打开方式：单击不做任何事。
 *
 * <p>标记文件本身是空的，单击弹编辑器只会看到一个空文件；
 * 注册成"无动作"后，单击不会打开，展开（点状态图标）才进入外部库。
 */
public class NoopOpeningOption implements PSObjectOpeningOption {

    @Override
    public String getId() {
        return "go-noop-open";
    }

    @Override
    public String getName() {
        return "无动作";
    }

    @Override
    public void open(PSObject object) {
        // 故意留空：标记文件不需要打开动作。
    }
}
