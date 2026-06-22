package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // CBO: nº de classes distintas acopladas a cada nó, em qualquer direção.
    // Difere de fanIn+fanOut, que pode contar a mesma classe duas vezes se
    // o acoplamento existir nos dois sentidos.
    public static Map<String, Integer> computeCbo(List<DependencyEdge> edges) {
        Map<String, Set<String>> coupled = new HashMap<>();
        for (DependencyEdge e : edges) {
            coupled.computeIfAbsent(e.getSource(), k -> new HashSet<>()).add(e.getTarget());
            coupled.computeIfAbsent(e.getTarget(), k -> new HashSet<>()).add(e.getSource());
        }
        Map<String, Integer> result = new HashMap<>();
        coupled.forEach((k, v) -> result.put(k, v.size()));
        return result;
    }
}
