package com.sofia.codeexplorer.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

// Instala o pipeline de snapshot visual (headless/ + workflow de CI) no
// projeto aberto, a partir dos recursos embutidos no jar do plugin em
// resources/pipeline/. Nunca sobrescreve um arquivo que já exista no
// projeto — clicar de novo na action é seguro (idempotente).
class PipelineInstaller {

    enum CiType { GITHUB, BITBUCKET }

    private final Project project;
    private final String projectPath;

    private final List<String> created = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    PipelineInstaller(Project project, String projectPath) {
        this.project = project;
        this.projectPath = projectPath;
    }

    void install() throws IOException {
        CiType ciType = detectCiType();

        copyHeadlessFolder();
        copyExtractorSource();
        copyWorkflow(ciType);
        boolean gitignoreUpdated = updateGitignore();

        showSuccess(ciType, gitignoreUpdated);
    }

    // -----------------------------------------------------------------
    // Detecção de CI
    // -----------------------------------------------------------------

    private CiType detectCiType() {
        Path gitConfig = Paths.get(projectPath, ".git", "config");
        if (!Files.exists(gitConfig)) return CiType.GITHUB;

        try {
            String content = Files.readString(gitConfig);
            if (content.contains("bitbucket.org")) return CiType.BITBUCKET;
            if (content.contains("github.com")) return CiType.GITHUB;
        } catch (IOException ignored) {
            // config ilegível — cai no padrão abaixo
        }
        return CiType.GITHUB;
    }

    // -----------------------------------------------------------------
    // Cópia dos arquivos
    // -----------------------------------------------------------------

    private void copyHeadlessFolder() throws IOException {
        String base = "/pipeline/headless/";
        Path target = Paths.get(projectPath, "headless");

        copyResourceIfAbsent(base + "pom.xml", target.resolve("pom.xml"));
        copyResourceIfAbsent(base + "package.json", target.resolve("package.json"));
        copyResourceIfAbsent(base + "screenshot.js", target.resolve("screenshot.js"));
        copyResourceIfAbsent(base + "template.html", target.resolve("template.html"));
        // Recurso salvo como gitignore.txt (alguns empacotadores ignoram
        // arquivos ".gitignore" dentro de resources) — copiado como .gitignore.
        copyResourceIfAbsent(base + "gitignore.txt", target.resolve(".gitignore"));
    }

    private void copyExtractorSource() throws IOException {
        Path target = Paths.get(projectPath, "headless", "src", "main", "java",
            "com", "sofia", "codeexplorer", "headless", "ExtractorCli.java");
        copyResourceIfAbsent("/pipeline/ExtractorCli.java", target);
    }

    private void copyWorkflow(CiType ciType) throws IOException {
        if (ciType == CiType.GITHUB) {
            Path target = Paths.get(projectPath, ".github", "workflows", "codeexplorer.yml");
            copyResourceIfAbsent("/pipeline/workflows/github-actions.yml", target);
        } else {
            Path target = Paths.get(projectPath, "bitbucket-pipelines.yml");
            copyResourceIfAbsent("/pipeline/workflows/bitbucket-pipelines.yml", target);
        }
    }

    private void copyResourceIfAbsent(String resourcePath, Path targetPath) throws IOException {
        String relative = Paths.get(projectPath).relativize(targetPath).toString();

        if (Files.exists(targetPath)) {
            skipped.add(relative);
            return;
        }

        Files.createDirectories(targetPath.getParent());
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Recurso não encontrado no plugin: " + resourcePath);
            }
            Files.write(targetPath, is.readAllBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        created.add(relative);
    }

    // -----------------------------------------------------------------
    // .gitignore
    // -----------------------------------------------------------------

    private boolean updateGitignore() throws IOException {
        Path gitignore = Paths.get(projectPath, ".gitignore");
        String addition = "# CodeExplorer\n" +
            "headless/output/\n" +
            "headless/node_modules/\n" +
            "headless/target/\n";

        if (Files.exists(gitignore)) {
            String content = Files.readString(gitignore, StandardCharsets.UTF_8);
            if (content.contains("headless/output/")) {
                return false;
            }
            String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
            Files.writeString(gitignore, separator + "\n" + addition,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } else {
            Files.writeString(gitignore, addition, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
        return true;
    }

    // -----------------------------------------------------------------
    // Notificação
    // -----------------------------------------------------------------

    private void showSuccess(CiType ciType, boolean gitignoreUpdated) {
        String ciName = ciType == CiType.BITBUCKET ? "Bitbucket Pipelines" : "GitHub Actions";

        StringBuilder message = new StringBuilder();
        message.append("<b>Pipeline configurado!</b><br><br>")
            .append("CI detectado: ").append(ciName).append("<br><br>");

        if (!created.isEmpty()) {
            message.append("Arquivos criados: ").append(created.size()).append("<br>");
        }
        if (!skipped.isEmpty()) {
            message.append("Já existiam e foram mantidos: ").append(skipped.size()).append("<br>");
        }
        if (gitignoreUpdated) {
            message.append(".gitignore atualizado<br>");
        }

        message.append("<br>Próximos passos:<br>")
            .append("1. Compile o extrator: <code>cd headless &amp;&amp; mvn package</code><br>")
            .append("2. Instale o Node: <code>cd headless &amp;&amp; npm install</code><br>")
            .append("3. Faça commit e push dos arquivos adicionados<br>")
            .append("4. Abra um Pull Request — o snapshot será gerado automaticamente");

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeExplorer")
            .createNotification("CodeExplorer", message.toString(), NotificationType.INFORMATION)
            .notify(project);
    }
}
