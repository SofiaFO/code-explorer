package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.NodeType;
import org.junit.Test;

import java.util.List;

import static com.sofia.codeexplorer.analyzer.CouplingAnalyzer.CouplingLevel.*;
import static org.junit.Assert.*;

public class CouplingAnalyzerTest {

    private static ClassNode node(int cbo) {
        ClassNode n = new ClassNode("pkg.Test", "Test", "pkg", NodeType.CLASS, "");
        n.setCbo(cbo);
        return n;
    }

    @Test
    public void baixoBoundary() {
        assertEquals(BAIXO, CouplingAnalyzer.classify(node(0)));
        assertEquals(BAIXO, CouplingAnalyzer.classify(node(2)));
    }

    @Test
    public void moderadoBoundary() {
        assertEquals(MODERADO, CouplingAnalyzer.classify(node(3)));
        assertEquals(MODERADO, CouplingAnalyzer.classify(node(5)));
    }

    @Test
    public void altoBoundary() {
        assertEquals(ALTO, CouplingAnalyzer.classify(node(6)));
        assertEquals(ALTO, CouplingAnalyzer.classify(node(9)));
    }

    @Test
    public void severoBoundary() {
        assertEquals(SEVERO, CouplingAnalyzer.classify(node(10)));
        assertEquals(SEVERO, CouplingAnalyzer.classify(node(50)));
    }

    @Test
    public void getByMinLevelBaixo() {
        List<ClassNode> nodes = List.of(node(0), node(3), node(6), node(10));
        assertEquals(4, CouplingAnalyzer.getByMinLevel(nodes, BAIXO).size());
    }

    @Test
    public void getByMinLevelModerado() {
        List<ClassNode> nodes = List.of(node(0), node(3), node(6), node(10));
        assertEquals(3, CouplingAnalyzer.getByMinLevel(nodes, MODERADO).size());
    }

    @Test
    public void getByMinLevelAlto() {
        List<ClassNode> nodes = List.of(node(0), node(3), node(6), node(10));
        assertEquals(2, CouplingAnalyzer.getByMinLevel(nodes, ALTO).size());
    }

    @Test
    public void getByMinLevelSevero() {
        List<ClassNode> nodes = List.of(node(0), node(3), node(6), node(10));
        assertEquals(1, CouplingAnalyzer.getByMinLevel(nodes, SEVERO).size());
    }

    @Test
    public void sortedByCboDescending() {
        List<ClassNode> result = CouplingAnalyzer.getByMinLevel(
            List.of(node(6), node(10), node(7)), ALTO);
        assertEquals(10, result.get(0).getCbo());
        assertEquals(6, result.get(result.size() - 1).getCbo());
    }
}
