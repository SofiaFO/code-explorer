package com.sofia.codeexplorer.analyzer;

import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;
import com.sofia.codeexplorer.model.EdgeType;
import com.sofia.codeexplorer.model.NodeType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class HierarchyJsonBuilderTest {

    private static ClassNode node(String qualifiedName, String simpleName, String packageName, int fanIn) {
        ClassNode n = new ClassNode(qualifiedName, simpleName, packageName, NodeType.CLASS, "");
        n.setFanIn(fanIn);
        return n;
    }

    @Test
    public void nestsClassesByPackageSegmentsWhenSiblingsExist() {
        // "com" e "example" não têm classe própria e só têm 1 filho cada,
        // então colapsam em "com.example" — só a partir daí há ramificação
        // real (model/service), então essa parte continua aninhada.
        ClassNode user = node("com.example.model.User", "User", "com.example.model", 0);
        ClassNode service = node("com.example.service.UserService", "UserService", "com.example.service", 0);
        var result = new ClassRelationExtractor.ExtractionResult(List.of(user, service), List.of());

        String json = HierarchyJsonBuilder.build(result, List.of(), "proj");

        assertTrue(json.contains("\"name\": \"proj\""));
        assertFalse(json.contains("\"name\": \"com\","));
        assertFalse(json.contains("\"name\": \"example\","));
        int comExampleIdx = json.indexOf("\"name\": \"com.example\"");
        int modelIdx = json.indexOf("\"name\": \"model\"");
        int serviceIdx = json.indexOf("\"name\": \"service\"");
        assertTrue(comExampleIdx >= 0 && modelIdx > comExampleIdx);
        // "model" vem antes de "service" (ordem alfabética dos pacotes)
        assertTrue(modelIdx < serviceIdx);
        assertTrue(json.contains("\"qualifiedName\": \"com.example.model.User\""));
        assertTrue(json.contains("\"qualifiedName\": \"com.example.service.UserService\""));
    }

    @Test
    public void collapsesLongSingleChildChainIntoOneNode() {
        ClassNode leaf = node("a.b.c.d.Leaf", "Leaf", "a.b.c.d", 0);
        var result = new ClassRelationExtractor.ExtractionResult(List.of(leaf), List.of());

        String json = HierarchyJsonBuilder.build(result, List.of(), "proj");

        assertTrue(json.contains("\"name\": \"a.b.c.d\""));
        assertFalse(json.contains("\"name\": \"a\","));
        assertFalse(json.contains("\"name\": \"b\","));
        assertFalse(json.contains("\"name\": \"c\","));
        assertFalse(json.contains("\"name\": \"d\","));
        assertTrue(json.contains("\"qualifiedName\": \"a.b.c.d.Leaf\""));
    }

    @Test
    public void doesNotCollapsePackageWithDirectClassAndSubpackage() {
        // "a" tem uma classe direta E um sub-pacote "b" -> não pode colapsar,
        // senão a classe direta de "a" perderia o pacote certo.
        ClassNode direct = node("a.Direct", "Direct", "a", 0);
        ClassNode nested = node("a.b.Nested", "Nested", "a.b", 0);
        var result = new ClassRelationExtractor.ExtractionResult(List.of(direct, nested), List.of());

        String json = HierarchyJsonBuilder.build(result, List.of(), "proj");

        assertTrue(json.contains("\"name\": \"a\","));
        assertTrue(json.contains("\"name\": \"b\""));
        assertTrue(json.contains("\"qualifiedName\": \"a.Direct\""));
        assertTrue(json.contains("\"qualifiedName\": \"a.b.Nested\""));
    }

    @Test
    public void defaultPackageClassGoesDirectlyUnderRoot() {
        ClassNode main = node("Main", "Main", "", 0);
        var result = new ClassRelationExtractor.ExtractionResult(List.of(main), List.of());

        String json = HierarchyJsonBuilder.build(result, List.of(), "proj");

        // Só deve existir o array "children" da raiz — nenhum pacote intermediário criado.
        assertEquals(1, json.split("\"children\":", -1).length - 1);
        assertTrue(json.contains("\"qualifiedName\": \"Main\""));
    }

    @Test
    public void valueIsNeverZeroEvenWithoutFanIn() {
        ClassNode isolated = node("pkg.Isolated", "Isolated", "pkg", 0);
        var result = new ClassRelationExtractor.ExtractionResult(List.of(isolated), List.of());

        String json = HierarchyJsonBuilder.build(result, List.of(), "proj");

        assertTrue(json.contains("\"fanIn\": 0"));
        assertTrue(json.contains("\"value\": 1"));
    }

    @Test
    public void edgesAndCyclesAppearAtRootLevel() {
        ClassNode b = node("a.B", "B", "a", 0);
        ClassNode c = node("a.C", "C", "a", 0);
        List<DependencyEdge> edges = List.of(new DependencyEdge("a.B", "a.C", EdgeType.USES));
        List<List<String>> cycles = List.of(List.of("a.B", "a.C"));
        var result = new ClassRelationExtractor.ExtractionResult(List.of(b, c), edges);

        String json = HierarchyJsonBuilder.build(result, cycles, "proj");

        assertTrue(json.contains("\"source\": \"a.B\""));
        assertTrue(json.contains("\"target\": \"a.C\""));
        assertTrue(json.contains("\"type\": \"USES\""));
        assertTrue(json.contains("[\"a.B\", \"a.C\"]"));
        assertTrue(json.indexOf("\"edges\":") < json.indexOf("\"cycles\":"));
    }
}
