package com.scala.android.dependency;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * POM 解析器：把 {@code pom.xml} 读成 {@link MavenPom}。
 *
 * <p>只解析"解析依赖传递"用得上的节点：坐标、parent、properties、
 * dependencyManagement、dependencies、repositories。其它节点（build、profiles…）忽略。
 */
public final class MavenPomParser {

    private MavenPomParser() {
    }

    public static MavenPom parse(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            // 防 XXE：POM 是外部下载来的数据。
            trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new ByteArrayInputStream(content)));
            Element root = document.getDocumentElement();
            if (root == null || !"project".equals(localName(root))) {
                return null;
            }
            MavenPom pom = new MavenPom();
            pom.parentGroupId = text(child(root, "parent"), "groupId");
            pom.parentArtifactId = text(child(root, "parent"), "artifactId");
            pom.parentVersion = text(child(root, "parent"), "version");

            String groupId = text(root, "groupId");
            String artifactId = text(root, "artifactId");
            String version = text(root, "version");
            String packaging = text(root, "packaging");
            pom.coordinate = new MavenCoordinate(groupId, artifactId, version);
            pom.packaging = packaging == null || packaging.isEmpty() ? "jar" : packaging;

            Element properties = child(root, "properties");
            if (properties != null) {
                NodeList children = properties.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        String name = localName((Element) node);
                        String value = node.getTextContent();
                        if (name != null && value != null) {
                            pom.properties.put(name.trim(), value.trim());
                        }
                    }
                }
            }

            Element dependencyManagement = child(root, "dependencyManagement");
            if (dependencyManagement != null) {
                Element dependencies = child(dependencyManagement, "dependencies");
                for (Element element : children(dependencies, "dependency")) {
                    String key = key(text(element, "groupId"), text(element, "artifactId"),
                            text(element, "classifier"));
                    String managedVersion = text(element, "version");
                    if (key != null && managedVersion != null) {
                        pom.managedVersions.put(key, managedVersion);
                    }
                }
            }

            for (Element element : children(child(root, "dependencies"), "dependency")) {
                MavenPom.Dependency dependency = new MavenPom.Dependency();
                dependency.groupId = text(element, "groupId");
                dependency.artifactId = text(element, "artifactId");
                dependency.version = text(element, "version");
                dependency.type = valueOrDefault(text(element, "type"), "jar");
                dependency.classifier = text(element, "classifier");
                dependency.scope = valueOrDefault(text(element, "scope"), "compile");
                dependency.optional = "true".equalsIgnoreCase(text(element, "optional"));
                pom.dependencies.add(dependency);
            }

            for (Element element : children(child(root, "repositories"), "repository")) {
                String id = text(element, "id");
                String url = text(element, "url");
                if (id != null && url != null) {
                    pom.repositories.put(id, url);
                }
            }
            return pom;
        } catch (Throwable error) {
            return null;
        }
    }

    // ------------------------------------------------------------ DOM 小工具

    private static void trySetFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Throwable ignored) {
            // 有些实现不支持该 feature，忽略即可。
        }
    }

    private static String localName(Element element) {
        String name = element.getTagName();
        int index = name.indexOf(':');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    private static Element child(Element parent, String name) {
        if (parent == null || name == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName((Element) node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null || name == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName((Element) node))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null ? null : value.trim();
    }

    private static String key(String groupId, String artifactId, String classifier) {
        if (groupId == null || artifactId == null) {
            return null;
        }
        return groupId + ":" + artifactId
                + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
