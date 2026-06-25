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

    public enum CouplingLevel { BAIXO, MODERADO, ALTO, SEVERO }

    public static CouplingLevel classify(ClassNode node) {
        int cbo = node.getCbo();
        if (cbo >= 10) return CouplingLevel.SEVERO;
        if (cbo >= 6)  return CouplingLevel.ALTO;
        if (cbo >= 3)  return CouplingLevel.MODERADO;
        return CouplingLevel.BAIXO;
    }

    public static List<ClassNode> getByMinLevel(List<ClassNode> nodes, CouplingLevel minLevel) {
        return nodes.stream()
                .filter(n -> classify(n).ordinal() >= minLevel.ordinal())
                .sorted(Comparator.comparingInt(ClassNode::getCbo).reversed())
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
