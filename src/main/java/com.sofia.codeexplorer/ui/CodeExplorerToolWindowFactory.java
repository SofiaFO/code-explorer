package com.sofia.codeexplorer.ui;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sofia.codeexplorer.analyzer.AnalysisCacheService;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
import com.sofia.codeexplorer.analyzer.CycleDetector;
import com.sofia.codeexplorer.analyzer.HierarchyJsonBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class CodeExplorerToolWindowFactory implements ToolWindowFactory {

    // One instance per process is acceptable for a single-project dev tool
    private static GraphHtmlPanel graphPanel;
    private static JLabel statusLabel;
    private static JCheckBox useCacheBox;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        graphPanel = new GraphHtmlPanel();

        statusLabel = new JLabel("Pronto");
        useCacheBox = new JCheckBox("Cache", true);
        useCacheBox.setToolTipText("Reusar resultado se nenhum arquivo mudou");

        JButton analyzeBtn = new JButton("Analisar");
        analyzeBtn.addActionListener(e -> runAnalysis(project, useCacheBox.isSelected()));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(analyzeBtn);
        toolbar.add(useCacheBox);
        toolbar.add(statusLabel);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(toolbar, BorderLayout.NORTH);
        root.add(graphPanel, BorderLayout.CENTER);

        var content = toolWindow.getContentManager()
                .getFactory().createContent(root, "", false);
        content.setDisposer(() -> graphPanel.dispose());
        toolWindow.getContentManager().addContent(content);
    }

    // Called from AnalyzeProjectAction
    public static void triggerAnalysis(Project project) {
        runAnalysis(project, useCacheBox != null && useCacheBox.isSelected());
    }

    private static void runAnalysis(Project project, boolean useCache) {
        if (statusLabel == null) return;
        statusLabel.setText("Analisando...");

        AnalysisCacheService cache = project.getService(AnalysisCacheService.class);

        if (useCache && cache.isCacheValid(project)) {
            ClassRelationExtractor.ExtractionResult result = cache.getCached();
            List<List<String>> cycles = CycleDetector.detect(result.edges);
            graphPanel.updateGraph(HierarchyJsonBuilder.build(result, cycles, project.getName()));
            statusLabel.setText("(cache) " + summary(result, cycles));
            return;
        }

        ReadAction.nonBlocking(() -> new ClassRelationExtractor(project).extract())
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                if (result.nodes.isEmpty()) {
                    statusLabel.setText("0 classes — verifique se o projeto tem fontes Java/Kotlin indexadas");
                    return;
                }
                List<List<String>> cycles = CycleDetector.detect(result.edges);
                cache.store(project, result);
                graphPanel.updateGraph(HierarchyJsonBuilder.build(result, cycles, project.getName()));
                statusLabel.setText(summary(result, cycles));
            })
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError(ex -> SwingUtilities.invokeLater(() ->
                statusLabel.setText("Erro: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()))));
    }

    private static String summary(ClassRelationExtractor.ExtractionResult r, List<List<String>> cycles) {
        return r.nodes.size() + " classes | " + r.edges.size() + " relações | " + cycles.size() + " ciclos";
    }
}
