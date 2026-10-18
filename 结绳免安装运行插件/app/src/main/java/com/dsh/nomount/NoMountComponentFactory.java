package com.dsh.nomount;

import com.rapid.api.framework.project.component.ComponentFactory;

/** 把 {@link NoMountProjectComponent} 注册进每一个打开的工程。 */
public class NoMountComponentFactory implements ComponentFactory<NoMountProjectComponent> {

    @Override
    public NoMountProjectComponent createComponent() {
        return new NoMountProjectComponent();
    }
}
