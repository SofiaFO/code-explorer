package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CouplingAnalyzer {

    public enum Severity { OK, MODERADO, ALTO, CRITICO }

    public static Severity classify(ClassNode node) {
        int fanOut = node.getFanOut();
        if (fanOut >= 7) return Severity.CRITICO;
        if (fanOut >= 4) return Severity.ALTO;
        if (fanOut >= 2) return Severity.MODERADO;
        return Severity.OK;
    }

    public static List<ClassNode> getByMinSeverity(List<ClassNode> nodes, Severity minSeverity) {
        return nodes.stream()
                .filter(n -> classify(n).ordinal() >= minSeverity.ordinal())
                .sorted(Comparator.comparingInt(ClassNode::getFanOut).reversed())
                .collect(Collectors.toList());
    }
}
