package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;

import java.util.List;

public class ExportService {

    public static String toCsv(List<ClassNode> nodes, List<DependencyEdge> edges) {
        StringBuilder sb = new StringBuilder();
        sb.append("qualifiedName,simpleName,package,type,fanIn,fanOut,instability,DIT,NOC,implementations,severity\n");
        for (ClassNode n : nodes) {
            sb.append(csv(n.getQualifiedName())).append(',')
              .append(csv(n.getSimpleName())).append(',')
              .append(csv(n.getPackageName())).append(',')
              .append(n.getType()).append(',')
              .append(n.getFanIn()).append(',')
              .append(n.getFanOut()).append(',')
              .append(String.format("%.2f", n.getInstability())).append(',')
              .append(n.getDit()).append(',')
              .append(n.getNoc()).append(',')
              .append(n.getImplementationsCount()).append(',')
              .append(CouplingAnalyzer.classify(n)).append('\n');
        }
        sb.append("\nsource,target,type\n");
        for (DependencyEdge e : edges) {
            sb.append(csv(e.getSource())).append(',')
              .append(csv(e.getTarget())).append(',')
              .append(e.getType()).append('\n');
        }
        return sb.toString();
    }

    public static String toJson(List<ClassNode> nodes, List<DependencyEdge> edges) {
        StringBuilder sb = new StringBuilder("{\n  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            ClassNode n = nodes.get(i);
            sb.append(String.format(
                "    {\"name\":\"%s\",\"package\":\"%s\",\"type\":\"%s\"," +
                "\"fanIn\":%d,\"fanOut\":%d,\"instability\":%.2f," +
                "\"DIT\":%d,\"NOC\":%d,\"impl\":%d,\"severity\":\"%s\"}%s\n",
                esc(n.getQualifiedName()), esc(n.getPackageName()), n.getType(),
                n.getFanIn(), n.getFanOut(), n.getInstability(),
                n.getDit(), n.getNoc(), n.getImplementationsCount(),
                CouplingAnalyzer.classify(n),
                i < nodes.size() - 1 ? "," : ""));
        }
        sb.append("  ],\n  \"edges\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            DependencyEdge e = edges.get(i);
            sb.append(String.format("    {\"source\":\"%s\",\"target\":\"%s\",\"type\":\"%s\"}%s\n",
                esc(e.getSource()), esc(e.getTarget()), e.getType(),
                i < edges.size() - 1 ? "," : ""));
        }
        sb.append("  ]\n}");
        return sb.toString();
    }

    public static String toDot(List<ClassNode> nodes, List<DependencyEdge> edges) {
        StringBuilder sb = new StringBuilder("digraph dependencies {\n  node [shape=box];\n");
        for (ClassNode n : nodes) {
            sb.append(String.format("  \"%s\";\n", esc(n.getQualifiedName())));
        }
        for (DependencyEdge e : edges) {
            String style = switch (e.getType()) {
                case EXTENDS     -> "style=bold";
                case IMPLEMENTS  -> "style=dashed";
                case INSTANTIATES -> "style=dotted";
                default          -> "";
            };
            sb.append(String.format("  \"%s\" -> \"%s\" [%s label=\"%s\"];\n",
                esc(e.getSource()), esc(e.getTarget()), style, e.getType()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
