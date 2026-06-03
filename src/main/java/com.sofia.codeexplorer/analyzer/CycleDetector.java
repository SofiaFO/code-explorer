package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.DependencyEdge;

import java.util.*;

public class CycleDetector {

    public static List<List<String>> detect(List<DependencyEdge> edges) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (DependencyEdge edge : edges) {
            adj.computeIfAbsent(edge.getSource(), k -> new HashSet<>()).add(edge.getTarget());
        }

        List<List<String>> cycles = new ArrayList<>();
        Set<List<String>> seen   = new HashSet<>();
        Set<String> visited      = new HashSet<>();
        Set<String> inStack      = new HashSet<>();
        List<String> path        = new ArrayList<>();

        for (String node : adj.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, adj, visited, inStack, path, cycles, seen);
            }
        }

        return cycles;
    }

    private static void dfs(String node,
                             Map<String, Set<String>> adj,
                             Set<String> visited,
                             Set<String> inStack,
                             List<String> path,
                             List<List<String>> cycles,
                             Set<List<String>> seen) {
        visited.add(node);
        inStack.add(node);
        path.add(node);

        for (String neighbor : adj.getOrDefault(node, Collections.emptySet())) {
            if (!visited.contains(neighbor)) {
                dfs(neighbor, adj, visited, inStack, path, cycles, seen);
            } else if (inStack.contains(neighbor)) {
                int startIdx = path.indexOf(neighbor);
                List<String> cycle = new ArrayList<>(path.subList(startIdx, path.size()));
                List<String> canonical = canonical(cycle);
                if (seen.add(canonical)) {
                    cycles.add(cycle);
                }
            }
        }

        path.remove(path.size() - 1);
        inStack.remove(node);
    }

    // Rotaciona o ciclo para começar sempre pelo nó lexicograficamente menor,
    // garantindo que o mesmo ciclo detectado por caminhos diferentes seja deduplicado.
    private static List<String> canonical(List<String> cycle) {
        int minIdx = 0;
        for (int i = 1; i < cycle.size(); i++) {
            if (cycle.get(i).compareTo(cycle.get(minIdx)) < 0) minIdx = i;
        }
        List<String> result = new ArrayList<>(cycle.size());
        for (int i = 0; i < cycle.size(); i++) {
            result.add(cycle.get((minIdx + i) % cycle.size()));
        }
        return result;
    }
}
