package com.linux.plugin.running;
import com.rapid.api.project.component.running.TaskRunConfiguration;
import com.rapid.api.project.ProjectVariant;
import com.rapid.api.project.task.ExecutorResult;
import com.rapid.api.project.Project;
import com.rapid.api.Platform;

public class CopySoFileRunc extends TaskRunConfiguration {

	@Override
	public Project getProject() {
		return Platform.getProject();
	}

	@Override
	public String getTaskId() {
		return "copySoFile";
	}

	@Override
	public ProjectVariant getVariant() {
		return ProjectVariant.DEBUG;
	}

	@Override
	public void onResult(ExecutorResult executorResult) {
	}

	@Override
	public String getId() {
		return "build-art";
	}

	@Override
	public String getName() {
		return "构建动态库";
	}
	
    
    
    
}
