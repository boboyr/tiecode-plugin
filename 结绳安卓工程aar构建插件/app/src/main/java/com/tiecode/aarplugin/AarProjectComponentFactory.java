package com.tiecode.aarplugin;

import com.rapid.api.framework.project.component.ComponentFactory;

/**
 * 把 {@link AarProjectComponent} 注册进每一个打开的工程。
 */
public class AarProjectComponentFactory implements ComponentFactory<AarProjectComponent> {

    @Override
    public AarProjectComponent createComponent() {
        return new AarProjectComponent();
    }
}
