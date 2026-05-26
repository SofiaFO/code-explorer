package com.sofia.codeexplorer.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.sofia.codeexplorer.ui.CodeExplorerToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public class AnalyzeProjectAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Abre o painel lateral e dispara a análise
        ToolWindow toolWindow = ToolWindowManager
                .getInstance(project)
                .getToolWindow("CodeExplorer");

        if (toolWindow != null) {
            toolWindow.show();
            CodeExplorerToolWindowFactory.triggerAnalysis(project);
        }
    }
}