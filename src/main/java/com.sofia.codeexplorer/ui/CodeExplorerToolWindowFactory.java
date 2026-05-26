package com.sofia.codeexplorer.ui;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
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

        outputArea.setText(DumbService.isDumb(project)
                ? "Aguardando indexação do projeto...\n"
                : "Analisando...\n");

        ReadAction.nonBlocking(() -> {
                    ClassRelationExtractor extractor = new ClassRelationExtractor(project);
                    return extractor.extract();
                })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(),
                        result -> outputArea.setText(buildOutput(result.nodes, result.edges)))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static String buildOutput(List<ClassNode> nodes, List<DependencyEdge> edges) {
        if (nodes.isEmpty()) {
            return "Nenhuma classe encontrada.\n\n" +
                   "Verifique se:\n" +
                   "  - O projeto aberto possui arquivos .java\n" +
                   "  - O SDK Java está configurado (File → Project Structure → SDK)\n" +
                   "  - A indexação terminou antes de clicar em Analisar";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Classes encontradas: ").append(nodes.size()).append(" ===\n\n");

        nodes.stream()
                .sorted(Comparator.comparingInt(ClassNode::getFanIn).reversed())
                .limit(30)
                .forEach(node -> sb.append(node.getSimpleName())
                        .append("  [").append(node.getType()).append("]")
                        .append("  fan-in: ").append(node.getFanIn())
                        .append("  fan-out: ").append(node.getFanOut())
                        .append("\n"));

        sb.append("\n=== Relações: ").append(edges.size()).append(" ===\n\n");

        edges.stream().limit(50).forEach(edge -> sb
                .append(simpleName(edge.getSource()))
                .append(" --[").append(edge.getType()).append("]--> ")
                .append(simpleName(edge.getTarget()))
                .append("\n"));

        return sb.toString();
    }

    private static String simpleName(String qualifiedName) {
        int i = qualifiedName.lastIndexOf('.');
        return i >= 0 ? qualifiedName.substring(i + 1) : qualifiedName;
    }
}
