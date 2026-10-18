package com.scala.android.dependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个 POM 文件解析出来的内容（只保留"解析依赖传递"需要的部分）。
 */
public final class MavenPom {

    /** 一条 &lt;dependency&gt;。 */
    public static final class Dependency {
        public String groupId;
        public String artifactId;
        public String version;
        public String type = "jar";
        public String classifier;
        public String scope = "compile";
        public boolean optional;

        public MavenCoordinate toCoordinate() {
            if (groupId == null || artifactId == null || version == null) {
                return null;
            }
            MavenCoordinate coordinate = new MavenCoordinate(groupId, artifactId, version, classifier);
            coordinate.packaging = type == null || type.isEmpty() ? "jar" : type;
            return coordinate;
        }

        public String key() {
            return groupId + ":" + artifactId
                    + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
        }

        @Override
        public String toString() {
            return key() + ":" + version + " (" + scope + ")"
                    + (optional ? " [optional]" : "");
        }
    }

    /** 本 POM 的坐标。 */
    public MavenCoordinate coordinate;
    public String parentGroupId;
    public String parentArtifactId;
    public String parentVersion;
    public String packaging = "jar";

    /** &lt;properties&gt; 与本 POM 的内建属性。 */
    public final Map<String, String> properties = new LinkedHashMap<>();
    /** &lt;dependencyManagement&gt; 里的版本约束，键是 group:artifact[:classifier]。 */
    public final Map<String, String> managedVersions = new LinkedHashMap<>();
    /** 实际依赖。 */
    public final List<Dependency> dependencies = new ArrayList<>();
    /** POM 里额外声明的仓库。 */
    public final Map<String, String> repositories = new LinkedHashMap<>();

    public boolean isParentOf(String groupId, String artifactId) {
        return parentGroupId != null && parentGroupId.equals(groupId)
                && parentArtifactId != null && parentArtifactId.equals(artifactId);
    }

    /** POM 里可能出现的 unresolved 标记（调试用）。 */
    public final Set<String> unresolvedProperties = new LinkedHashSet<>();
}
