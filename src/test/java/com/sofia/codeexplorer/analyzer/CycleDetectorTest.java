package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.DependencyEdge;
import com.sofia.codeexplorer.model.EdgeType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CycleDetectorTest {

    private static DependencyEdge e(String from, String to) {
        return new DependencyEdge(from, to, EdgeType.USES);
    }

    @Test
    public void emptyGraph() {
        assertTrue(CycleDetector.detect(List.of()).isEmpty());
    }

    @Test
    public void linearChainNoCycle() {
        assertTrue(CycleDetector.detect(List.of(e("A", "B"), e("B", "C"))).isEmpty());
    }

    @Test
    public void simpleTwoNodeCycle() {
        List<List<String>> cycles = CycleDetector.detect(List.of(e("A", "B"), e("B", "A")));
        assertEquals(1, cycles.size());
        List<String> c = cycles.get(0);
        assertTrue(c.contains("A") && c.contains("B"));
    }

    @Test
    public void selfLoop() {
        assertEquals(1, CycleDetector.detect(List.of(e("A", "A"))).size());
    }

    @Test
    public void threeCycle() {
        List<List<String>> cycles = CycleDetector.detect(
            List.of(e("A", "B"), e("B", "C"), e("C", "A")));
        assertEquals(1, cycles.size());
        assertEquals(3, cycles.get(0).size());
    }

    @Test
    public void noDuplicatesForSameCycle() {
        // A->B->A is one cycle regardless of which node triggers the DFS
        List<List<String>> cycles = CycleDetector.detect(List.of(e("A", "B"), e("B", "A")));
        assertEquals(1, cycles.size());
    }

    @Test
    public void twoCyclesIndependent() {
        List<List<String>> cycles = CycleDetector.detect(List.of(
            e("A", "B"), e("B", "A"),
            e("C", "D"), e("D", "C")));
        assertEquals(2, cycles.size());
    }

    @Test
    public void dagPlusOneCycle() {
        // A->B->C (no cycle), plus D->E->D (cycle)
        List<List<String>> cycles = CycleDetector.detect(List.of(
            e("A", "B"), e("B", "C"),
            e("D", "E"), e("E", "D")));
        assertEquals(1, cycles.size());
    }
}
