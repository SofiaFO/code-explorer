package com.sofia.codeexplorer.ui;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sofia.codeexplorer.analyzer.AnalysisCacheService;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
import com.sofia.codeexplorer.analyzer.CycleDetector;
import com.sofia.codeexplorer.analyzer.HierarchyJsonBuilder;
import com.sofia.codeexplorer.model.ClassNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CodeExplorerToolWindowFactory implements ToolWindowFactory {

    // One instance per process is acceptable for a single-project dev tool.
    // A barra superior (Analisar / módulo / cache / timestamp) agora vive
    // dentro do HTML do GraphHtmlPanel, ligada aqui via bridge JCEF
    // (GraphHtmlPanel.AnalyzeListener) em vez de componentes Swing.
    private static GraphHtmlPanel graphPanel;
    private static List<Module> availableModules = List.of();
    private static String lastSelectedModuleName;
    private static boolean lastUseCache = true;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        graphPanel = new GraphHtmlPanel();
        graphPanel.setAnalyzeListener((moduleName, useCache) ->
            runAnalysis(project, resolveModule(moduleName), useCache));
        graphPanel.setOpenFileListener(qualifiedName -> openInEditor(project, qualifiedName));

        availableModules = sortedModulesIfMultiple(project);
        lastSelectedModuleName = availableModules.isEmpty() ? null : availableModules.get(0).getName();
        lastUseCache = true;

        graphPanel.render(new GraphHtmlPanel.RenderState(
            project.getName(),
            moduleNames(availableModules),
            lastSelectedModuleName,
            lastUseCache,
            null,
            null,
            null
        ));

        JPanel root = new JPanel(new BorderLayout());
        root.add(graphPanel, BorderLayout.CENTER);

        var content = toolWindow.getContentManager()
                .getFactory().createContent(root, "", false);
        content.setDisposer(() -> graphPanel.dispose());
        toolWindow.getContentManager().addContent(content);
    }

    // Called from AnalyzeProjectAction
    public static void triggerAnalysis(Project project) {
        if (graphPanel == null) return;
        runAnalysis(project, resolveModule(lastSelectedModuleName), lastUseCache);
    }

    // Só monta a lista (e o dropdown, do lado do HTML) quando há mais de um
    // módulo — com um só (ou nenhum), a análise é sempre do projeto inteiro.
    private static List<Module> sortedModulesIfMultiple(Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length <= 1) return List.of();

        Arrays.sort(modules, (a, b) -> {
            boolean aHasSrc = hasMainJava(a);
            boolean bHasSrc = hasMainJava(b);
            if (aHasSrc && !bHasSrc) return -1;
            if (!aHasSrc && bHasSrc) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return new ArrayList<>(Arrays.asList(modules));
    }

    private static boolean hasMainJava(Module module) {
        return Arrays.stream(ModuleRootManager.getInstance(module).getSourceRoots(false))
                .anyMatch(r -> r.getPath().contains("src/main/java"));
    }

    private static List<String> moduleNames(List<Module> modules) {
        return modules.stream().map(Module::getName).toList();
    }

    @Nullable
    private static Module resolveModule(@Nullable String name) {
        if (name == null) return null;
        return availableModules.stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static void runAnalysis(Project project, @Nullable Module module, boolean useCache) {
        if (graphPanel == null) return;
        lastSelectedModuleName = module != null ? module.getName() : null;
        lastUseCache = useCache;

        AnalysisCacheService cache = project.getService(AnalysisCacheService.class);

        if (useCache && cache.isCacheValid(project, module)) {
            ClassRelationExtractor.ExtractionResult result = cache.getCached(module);
            List<List<String>> cycles = CycleDetector.detect(result.edges);
            String rootName = module != null ? module.getName() : project.getName();
            renderState(project, module, useCache, HierarchyJsonBuilder.build(result, cycles, rootName),
                cache.getLastAnalyzedAt(module), null);
            return;
        }

        ReadAction.nonBlocking(() -> new ClassRelationExtractor(project, module).extract())
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                if (result.nodes.isEmpty()) {
                    renderState(project, module, useCache, null, null,
                        "0 classes — verifique se o projeto tem fontes Java/Kotlin indexadas");
                    return;
                }
                List<List<String>> cycles = CycleDetector.detect(result.edges);
                cache.store(project, module, result);
                String rootName = module != null ? module.getName() : project.getName();
                renderState(project, module, useCache, HierarchyJsonBuilder.build(result, cycles, rootName),
                    cache.getLastAnalyzedAt(module), null);
            })
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError(ex -> SwingUtilities.invokeLater(() ->
                renderState(project, module, useCache, null, null,
                    "Erro: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()))));
    }

    // Acionado pelo botão "Abrir no editor" do inspetor lateral (bridge JCEF).
    // Resolve o caminho do arquivo a partir do resultado em cache da última
    // análise do módulo atualmente selecionado — evita ter que serializar
    // filePath no JSON só para isso.
    private static void openInEditor(Project project, String qualifiedName) {
        Module module = resolveModule(lastSelectedModuleName);
        AnalysisCacheService cache = project.getService(AnalysisCacheService.class);
        ClassRelationExtractor.ExtractionResult result = cache.getCached(module);
        if (result == null) return;

        ClassNode node = result.nodes.stream()
                .filter(n -> n.getQualifiedName().equals(qualifiedName))
                .findFirst()
                .orElse(null);
        if (node == null || node.getFilePath().isEmpty()) return;

        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(node.getFilePath());
        if (file == null) return;

        new OpenFileDescriptor(project, file).navigate(true);
    }

    private static void renderState(Project project, @Nullable Module module, boolean useCache,
                                     @Nullable String graphJson, @Nullable Long lastAnalyzedAt,
                                     @Nullable String statusMessage) {
        graphPanel.render(new GraphHtmlPanel.RenderState(
            project.getName(),
            moduleNames(availableModules),
            module != null ? module.getName() : null,
            useCache,
            lastAnalyzedAt,
            statusMessage,
            graphJson
        ));
    }
}
