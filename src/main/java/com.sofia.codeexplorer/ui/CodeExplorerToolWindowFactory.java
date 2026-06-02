package com.sofia.codeexplorer.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
import com.sofia.codeexplorer.analyzer.CouplingAnalyzer;
import com.sofia.codeexplorer.analyzer.CycleDetector;
import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Comparator;
import java.util.List;

public class CodeExplorerToolWindowFactory implements ToolWindowFactory {

    private static JBTextArea outputArea;
    private static Project currentProject;

    @Override
    public void createToolWindowContent(@NotNull Project project,
                                        @NotNull ToolWindow toolWindow) {
        currentProject = project;

        JPanel panel = new JPanel(new BorderLayout());

        JButton analyzeButton = new JButton("Analisar projeto");
        analyzeButton.addActionListener(e -> runAnalysis(project));

        outputArea = new JBTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputArea.setText("Clique em 'Analisar projeto' para começar.");

        panel.add(analyzeButton, BorderLayout.NORTH);
        panel.add(new JBScrollPane(outputArea), BorderLayout.CENTER);

        var content = toolWindow.getContentManager()
                .getFactory()
                .createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    public static void triggerAnalysis(Project project) {
        currentProject = project;
        runAnalysis(project);
    }

    private static void runAnalysis(Project project) {
        if (outputArea == null) return;
        outputArea.setText("Analisando...\n");

        com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    ClassRelationExtractor extractor = new ClassRelationExtractor(project);
                    ClassRelationExtractor.ExtractionResult result = extractor.extract();
                    String text = buildOutput(result.nodes, result.edges);
                    SwingUtilities.invokeLater(() -> outputArea.setText(text));
                });
    }

    private static String buildOutput(List<ClassNode> nodes, List<DependencyEdge> edges) {
        List<List<String>> cycles = CycleDetector.detect(edges);
        StringBuilder sb = new StringBuilder();

        // Resumo
        sb.append("=== RESUMO ===\n");
        sb.append(String.format("Classes: %d  |  Relações: %d  |  Ciclos: %d%n%n",
                nodes.size(), edges.size(), cycles.size()));

        // Ciclos de dependência
        if (!cycles.isEmpty()) {
            sb.append("=== CICLOS DE DEPENDÊNCIA ===\n");
            for (int i = 0; i < cycles.size(); i++) {
                List<String> cycle = cycles.get(i);
                sb.append(String.format("  [%d] ", i + 1));
                for (int j = 0; j < cycle.size(); j++) {
                    sb.append(simpleName(cycle.get(j)));
                    if (j < cycle.size() - 1) sb.append(" → ");
                }
                sb.append(" → ").append(simpleName(cycle.get(0))).append("\n");
            }
            sb.append("\n");
        }

        // Classes com métricas
        sb.append("=== CLASSES — top 30 por fan-in ===\n\n");
        sb.append(String.format("%-30s %-16s %6s %7s %5s %4s %4s %5s  SEVERIDADE%n",
                "Classe", "Tipo", "fan-in", "fan-out", "I", "DIT", "NOC", "impl"));
        sb.append("-".repeat(90)).append("\n");

        nodes.stream()
                .sorted(Comparator.comparingInt(ClassNode::getFanIn).reversed())
                .limit(30)
                .forEach(node -> {
                    CouplingAnalyzer.Severity severity = CouplingAnalyzer.classify(node);
                    sb.append(String.format("%-30s %-16s %6d %7d %5.2f %4d %4d %5d  %s%n",
                            node.getSimpleName(),
                            node.getType(),
                            node.getFanIn(),
                            node.getFanOut(),
                            node.getInstability(),
                            node.getDit(),
                            node.getNoc(),
                            node.getImplementationsCount(),
                            severity));
                });

        // Relações
        sb.append("\n=== RELAÇÕES — primeiras 50 ===\n\n");
        edges.stream().limit(50).forEach(edge ->
                sb.append(String.format("%-30s --[%-12s]--> %s%n",
                        simpleName(edge.getSource()),
                        edge.getType(),
                        simpleName(edge.getTarget())))
        );

        return sb.toString();
    }

    private static String simpleName(String qualifiedName) {
        int i = qualifiedName.lastIndexOf('.');
        return i >= 0 ? qualifiedName.substring(i + 1) : qualifiedName;
    }
}
