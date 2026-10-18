package studio.basic.lang.csml.decision;

import android.util.Pair;
import android.util.SparseIntArray;

import com.rapid.framework.data.document.Position;

import java.util.ArrayList;
import java.util.List;

public class CsmlEnv {
    public boolean marked;
    public SparseIntArray extraLineColumns = new SparseIntArray();
    public String key;
    public List<Pair<String, Position>> highlights = new ArrayList<>();
}
