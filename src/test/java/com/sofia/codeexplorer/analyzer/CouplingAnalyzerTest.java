package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.NodeType;
import org.junit.Test;

import java.util.List;

import static com.sofia.codeexplorer.analyzer.CouplingAnalyzer.Severity.*;
import static org.junit.Assert.*;

public class CouplingAnalyzerTest {

    private static ClassNode node(int fanOut) {
        ClassNode n = new ClassNode("pkg.Test", "Test", "pkg", NodeType.CLASS, "");
        n.setFanOut(fanOut);
        return n;
    }

    @Test
    public void okBoundary() {
        assertEquals(OK, CouplingAnalyzer.classify(node(0)));
        assertEquals(OK, CouplingAnalyzer.classify(node(1)));
    }

    @Test
    public void moderadoBoundary() {
        assertEquals(MODERADO, CouplingAnalyzer.classify(node(2)));
        assertEquals(MODERADO, CouplingAnalyzer.classify(node(3)));
    }

    @Test
    public void altoBoundary() {
        assertEquals(ALTO, CouplingAnalyzer.classify(node(4)));
        assertEquals(ALTO, CouplingAnalyzer.classify(node(6)));
    }

    @Test
    public void criticoBoundary() {
        assertEquals(CRITICO, CouplingAnalyzer.classify(node(7)));
        assertEquals(CRITICO, CouplingAnalyzer.classify(node(50)));
    }

    @Test
    public void getByMinSeverityOk() {
        List<ClassNode> nodes = List.of(node(0), node(2), node(4), node(7));
        assertEquals(4, CouplingAnalyzer.getByMinSeverity(nodes, OK).size());
    }

    @Test
    public void getByMinSeverityModerado() {
        List<ClassNode> nodes = List.of(node(0), node(2), node(4), node(7));
        assertEquals(3, CouplingAnalyzer.getByMinSeverity(nodes, MODERADO).size());
    }

    @Test
    public void getByMinSeverityAlto() {
        List<ClassNode> nodes = List.of(node(0), node(2), node(4), node(7));
        assertEquals(2, CouplingAnalyzer.getByMinSeverity(nodes, ALTO).size());
    }

    @Test
    public void getByMinSeverityCritico() {
        List<ClassNode> nodes = List.of(node(0), node(2), node(4), node(7));
        assertEquals(1, CouplingAnalyzer.getByMinSeverity(nodes, CRITICO).size());
    }

    @Test
    public void sortedByFanOutDescending() {
        List<ClassNode> result = CouplingAnalyzer.getByMinSeverity(
            List.of(node(4), node(7), node(5)), ALTO);
        assertEquals(7, result.get(0).getFanOut());
        assertEquals(4, result.get(result.size() - 1).getFanOut());
    }
}
