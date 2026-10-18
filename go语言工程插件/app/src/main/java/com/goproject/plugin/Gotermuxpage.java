package com.goproject.plugin;
import com.rapid.api.ext.plugin.BasePluginPage;
import android.os.Bundle;
import com.qiplat.open.ui.module.layout.QiLinearLayout;
import com.rapid.android.ui.widget.editor.QiCodeEditor;
import io.github.rosemoe.sora.widget.CodeEditor;
import android.widget.LinearLayout;
import android.widget.Button;
import android.view.Gravity;
import androidx.recyclerview.widget.OrientationHelper;

public class Gotermuxpage extends BasePluginPage {

	@Override
	public void onCreate(Bundle savedInstanceState) {
		QiLinearLayout ql = new QiLinearLayout(getActivity());
		QiLinearLayout.LayoutParams rootparams = new QiLinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT);
		ql.setLayoutParams(rootparams);
		//ql.setOrientation(OrientationHelper.VERTICAL);
		//ql.setGravity(Gravity.CENTER);
		//Button bt1 = new Button(getActivity());
		//ql.addView(bt1);
		QiCodeEditor qc = new QiCodeEditor(getActivity());
		qc.setText("class");
		QiLinearLayout.LayoutParams qcparams = new QiLinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.MATCH_PARENT);
		qc.setLayoutParams(qcparams);
		ql.addView(qc);
		setContentView(ql);
	}
	
}
