package com.linux.plugin;

import com.rapid.api.framework.project.component.ComponentFactory;

public class FirstTimeDialogFactory implements ComponentFactory<FirstTimeDialog> {
    public FirstTimeDialog createComponent() {
        return new FirstTimeDialog();
    }
}
