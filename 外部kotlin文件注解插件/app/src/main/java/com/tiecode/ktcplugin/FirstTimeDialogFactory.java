package com.tiecode.ktcplugin;

import com.rapid.api.framework.project.component.ComponentFactory;

public class FirstTimeDialogFactory implements ComponentFactory<FirstTimeDialog> {
    @Override
    public FirstTimeDialog createComponent() {
        return new FirstTimeDialog();
    }
}
