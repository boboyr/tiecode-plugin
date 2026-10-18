package com.goproject.plugin.project;
import com.goproject.plugin.project.running.GoStartRunConfig;

public final class b implements Runnable {
    public final GoStartRunConfig b;

    public b(GoStartRunConfig cVar) {
        this.b = cVar;
    }

    @Override
    public final void run() {
        new GoStartRunConfig().i();
    }
}

