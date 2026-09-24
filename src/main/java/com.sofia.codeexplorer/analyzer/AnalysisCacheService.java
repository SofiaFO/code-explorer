package com.sofia.codeexplorer.analyzer;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

@Service(Service.Level.PROJECT)
public final class AnalysisCacheService {

    private static final String PROJECT_KEY = "__project__";

    private final Map<String, CacheEntry> entries = new HashMap<>();

    public boolean isCacheValid(Project project, @Nullable Module module) {
        CacheEntry entry = entries.get(key(module));
        if (entry == null) return false;
        return collectTimestamps(project, module).equals(entry.fileTimestamps);
    }

    public ClassRelationExtractor.ExtractionResult getCached(@Nullable Module module) {
        CacheEntry entry = entries.get(key(module));
        return entry != null ? entry.result : null;
    }

    // Momento (epoch millis) em que a análise foi de fato executada — usado
    // pelo rótulo "analisado há X min" da barra superior. Continua o mesmo
    // valor enquanto o resultado vier do cache (a análise em si não rodou de
    // novo), só muda quando store() é chamado com um resultado fresco.
    @Nullable
    public Long getLastAnalyzedAt(@Nullable Module module) {
        CacheEntry entry = entries.get(key(module));
        return entry != null ? entry.analyzedAt : null;
    }

    public void store(Project project, @Nullable Module module, ClassRelationExtractor.ExtractionResult result) {
        entries.put(key(module), new CacheEntry(result, collectTimestamps(project, module), System.currentTimeMillis()));
    }

    private String key(@Nullable Module module) {
        return module != null ? module.getName() : PROJECT_KEY;
    }

    private Map<String, Long> collectTimestamps(Project project, @Nullable Module module) {
        Map<String, Long> map = new HashMap<>();
        VirtualFile[] roots = module != null
                ? ModuleRootManager.getInstance(module).getSourceRoots(false)
                : ProjectRootManager.getInstance(project).getContentSourceRoots();
        for (VirtualFile root : roots) {
            collectRecursive(root, map);
        }
        return map;
    }

    private void collectRecursive(VirtualFile file, Map<String, Long> map) {
        if (file.isDirectory()) {
            for (VirtualFile child : file.getChildren()) {
                collectRecursive(child, map);
            }
        } else {
            String ext = file.getExtension();
            if ("java".equals(ext) || "kt".equals(ext)) {
                map.put(file.getPath(), file.getTimeStamp());
            }
        }
    }

    private static final class CacheEntry {
        final ClassRelationExtractor.ExtractionResult result;
        final Map<String, Long> fileTimestamps;
        final long analyzedAt;

        CacheEntry(ClassRelationExtractor.ExtractionResult result, Map<String, Long> fileTimestamps, long analyzedAt) {
            this.result = result;
            this.fileTimestamps = fileTimestamps;
            this.analyzedAt = analyzedAt;
        }
    }
}
