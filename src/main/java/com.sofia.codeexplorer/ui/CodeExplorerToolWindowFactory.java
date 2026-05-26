package com.sofia.codeexplorer.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Comparator;
import java.util.List;

public class CodeExplorerToolWindowFactory implements ToolWindowFactory {

    // Referência estática para permitir que a Action dispare a análise
    private static JBTextArea outputArea;
    private static Project currentProject;

    @Override
    public void createToolWindowContent(@NotNull Project project,
                                        @NotNull ToolWindow toolWindow) {
        currentProject = project;

        JPanel panel = new JPanel(new BorderLayout());

        // Botão de análise
        JButton analyzeButton = new JButton("Analisar projeto");
        analyzeButton.addActionListener(e -> runAnalysis(project));

        // Área de texto com resultado
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

    // Chamado tanto pelo botão quanto pela Action do menu
    public static void triggerAnalysis(Project project) {
        currentProject = project;
        runAnalysis(project);
    }

    private static void runAnalysis(Project project) {
        if (outputArea == null) return;
        outputArea.setText("Analisando...\n");

        // Roda em background para não travar a UI
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    ClassRelationExtractor extractor = new ClassRelationExtractor(project);
                    ClassRelationExtractor.ExtractionResult result = extractor.extract();

                    String text = buildOutput(result.nodes, result.edges);

                    // Atualiza a UI na thread correta
                    SwingUtilities.invokeLater(() -> outputArea.setText(text));
                });
    }

    private static String buildOutput(List<ClassNode> nodes,
                                      List<DependencyEdge> edges) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Classes encontradas: ").append(nodes.size()).append(" ===\n\n");

        nodes.stream()
                .sorted(Comparator.comparingInt(ClassNode::getFanIn).reversed())
                .limit(30)
                .forEach(node -> {
                    sb.append(node.getSimpleName())
                            .append("  [").append(node.getType()).append("]")
                            .append("  fan-in: ").append(node.getFanIn())
                            .append("  fan-out: ").append(node.getFanOut())
                            .append("\n");
                });

        sb.append("\n=== Relações: ").append(edges.size()).append(" ===\n\n");

        edges.stream().limit(50).forEach(edge -> {
            String src = simpleName(edge.getSource());
            String tgt = simpleName(edge.getTarget());
            sb.append(src).append(" --[").append(edge.getType()).append("]--> ").append(tgt).append("\n");
        });

        return sb.toString();
    }

    private static String simpleName(String qualifiedName) {
        int i = qualifiedName.lastIndexOf('.');
        return i >= 0 ? qualifiedName.substring(i + 1) : qualifiedName;
    }
}