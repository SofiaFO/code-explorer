package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Monta o JSON de árvore de pacotes (para d3.hierarchy/circle packing) +
// edges/cycles no nível raiz, sem nenhuma lib de JSON externa.
public class HierarchyJsonBuilder {

    private HierarchyJsonBuilder() {}

    public static String build(ClassRelationExtractor.ExtractionResult result,
                                List<List<String>> cycles,
                                String rootName) {
        PackageNode root = new PackageNode(rootName);
        for (ClassNode node : result.nodes) {
            PackageNode parent = root;
            if (!node.getPackageName().isEmpty()) {
                for (String segment : node.getPackageName().split("\\.")) {
                    parent = parent.children.computeIfAbsent(segment, PackageNode::new);
                }
            }
            parent.classes.add(node);
        }
        collapseChainsInPlace(root);

        int maxFanIn  = result.nodes.stream().mapToInt(ClassNode::getFanIn).max().orElse(1);
        int maxFanOut = result.nodes.stream().mapToInt(ClassNode::getFanOut).max().orElse(1);
        int maxLoc    = result.nodes.stream().mapToInt(ClassNode::getLoc).max().orElse(1);

        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"name\": \"").append(esc(rootName)).append("\",\n");
        sb.append("  \"maxFanIn\": ").append(maxFanIn).append(",\n");
        sb.append("  \"maxFanOut\": ").append(maxFanOut).append(",\n");
        sb.append("  \"maxLoc\": ").append(maxLoc).append(",\n");
        sb.append("  \"children\": [\n");
        writeChildren(sb, root, 2);
        sb.append("  ],\n");

        sb.append("  \"edges\": [\n");
        for (int i = 0; i < result.edges.size(); i++) {
            DependencyEdge e = result.edges.get(i);
            sb.append("    {\"source\": \"").append(esc(e.getSource()))
              .append("\", \"target\": \"").append(esc(e.getTarget()))
              .append("\", \"type\": \"").append(e.getType()).append("\"}")
              .append(i < result.edges.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ],\n");

        sb.append("  \"cycles\": [\n");
        for (int i = 0; i < cycles.size(); i++) {
            List<String> cycle = cycles.get(i);
            sb.append("    [");
            for (int j = 0; j < cycle.size(); j++) {
                sb.append('"').append(esc(cycle.get(j))).append('"');
                if (j < cycle.size() - 1) sb.append(", ");
            }
            sb.append(']').append(i < cycles.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n");
        sb.append('}');
        return sb.toString();
    }

    private static void collapseChainsInPlace(PackageNode node) {
        Map<String, PackageNode> collapsedChildren = new LinkedHashMap<>();
        for (PackageNode child : node.children.values()) {
            PackageNode collapsed = collapseChain(child);
            collapsedChildren.put(collapsed.name, collapsed);
        }
        node.children.clear();
        node.children.putAll(collapsedChildren);
    }

    // Funde cadeias de pacote com um único filho e nenhuma classe direta
    // (ex.: "com" -> "example" -> "model" vira só "com.example.model"). Sem
    // isso, esses níveis quase não têm área própria no circle packing — o
    // filho único ocupa praticamente todo o espaço do pai, deixando uma
    // borda clicável de poucos pixels pra dar zoom no pai.
    private static PackageNode collapseChain(PackageNode node) {
        collapseChainsInPlace(node);
        while (node.classes.isEmpty() && node.children.size() == 1) {
            PackageNode onlyChild = node.children.values().iterator().next();
            PackageNode merged = new PackageNode(node.name + "." + onlyChild.name);
            merged.children.putAll(onlyChild.children);
            merged.classes.addAll(onlyChild.classes);
            node = merged;
        }
        return node;
    }

    private static void writeChildren(StringBuilder sb, PackageNode node, int indent) {
        List<PackageNode> pkgChildren = new ArrayList<>(node.children.values());
        pkgChildren.sort(Comparator.comparing(p -> p.name));
        List<ClassNode> classChildren = new ArrayList<>(node.classes);
        classChildren.sort(Comparator.comparing(ClassNode::getSimpleName));

        int total = pkgChildren.size() + classChildren.size();
        int written = 0;
        String pad = "  ".repeat(indent);

        for (PackageNode child : pkgChildren) {
            sb.append(pad).append("{\n");
            sb.append(pad).append("  \"name\": \"").append(esc(child.name)).append("\",\n");
            sb.append(pad).append("  \"children\": [\n");
            writeChildren(sb, child, indent + 2);
            sb.append(pad).append("  ]\n");
            sb.append(pad).append('}');
            written++;
            sb.append(written < total ? "," : "").append('\n');
        }

        for (ClassNode n : classChildren) {
            // d3.pack() colapsa círculos de value 0; toda classe tem ao menos 1 linha.
            int value = Math.max(n.getLoc(), 1);

            sb.append(pad).append("{\n");
            sb.append(pad).append("  \"name\": \"").append(esc(n.getSimpleName())).append("\",\n");
            sb.append(pad).append("  \"qualifiedName\": \"").append(esc(n.getQualifiedName())).append("\",\n");
            sb.append(pad).append("  \"type\": \"").append(n.getType()).append("\",\n");
            sb.append(pad).append("  \"value\": ").append(value).append(",\n");
            sb.append(pad).append("  \"loc\": ").append(n.getLoc()).append(",\n");
            sb.append(pad).append("  \"fanIn\": ").append(n.getFanIn()).append(",\n");
            sb.append(pad).append("  \"fanOut\": ").append(n.getFanOut()).append(",\n");
            sb.append(pad).append("  \"cbo\": ").append(n.getCbo()).append("\n");
            sb.append(pad).append('}');
            written++;
            sb.append(written < total ? "," : "").append('\n');
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class PackageNode {
        final String name;
        final Map<String, PackageNode> children = new LinkedHashMap<>();
        final List<ClassNode> classes = new ArrayList<>();

        PackageNode(String name) { this.name = name; }
    }
}
