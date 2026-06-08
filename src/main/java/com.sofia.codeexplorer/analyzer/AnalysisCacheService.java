package com.sofia.codeexplorer.analyzer;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.HashMap;
import java.util.Map;

@Service(Service.Level.PROJECT)
public final class AnalysisCacheService {

    private ClassRelationExtractor.ExtractionResult cachedResult;
    private Map<String, Long> fileTimestamps = new HashMap<>();

    public boolean isCacheValid(Project project) {
        if (cachedResult == null) return false;
        return collectTimestamps(project).equals(fileTimestamps);
    }

    public ClassRelationExtractor.ExtractionResult getCached() {
        return cachedResult;
    }

    public void store(Project project, ClassRelationExtractor.ExtractionResult result) {
        this.cachedResult = result;
        this.fileTimestamps = collectTimestamps(project);
    }

    private Map<String, Long> collectTimestamps(Project project) {
        Map<String, Long> map = new HashMap<>();
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
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
}
