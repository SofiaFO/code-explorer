package com.sofia.codeexplorer.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

// Copia o pipeline headless (extrator + screenshot + workflow de CI) para o
// projeto aberto, embutido como resource do plugin — ver PipelineInstaller.
public class SetupPipelineAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        String projectPath = project.getBasePath();
        if (projectPath == null) return;

        try {
            new PipelineInstaller(project, projectPath).install();
        } catch (Exception ex) {
            showError(project, ex.getMessage());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    private void showError(Project project, String message) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeExplorer")
            .createNotification(
                "CodeExplorer",
                "Erro ao configurar pipeline: " + message,
                NotificationType.ERROR)
            .notify(project);
    }
}
