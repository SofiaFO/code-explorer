package com.sofia.codeexplorer.analyzer;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;
import com.sofia.codeexplorer.model.EdgeType;
import com.sofia.codeexplorer.model.NodeType;

import java.util.*;
import java.util.stream.Collectors;

public class ClassRelationExtractor {

    private final Project project;

    public ClassRelationExtractor(Project project) {
        this.project = project;
    }

    public ExtractionResult extract() {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        List<DependencyEdge>   edges = new ArrayList<>();

        GlobalSearchScope scope  = GlobalSearchScope.projectScope(project);
        JavaPsiFacade     facade = JavaPsiFacade.getInstance(project);
        String[]          names  = PsiShortNamesCache.getInstance(project).getAllClassNames();

        for (String name : names) {
            PsiClass[] classes = facade.findClasses(name, scope);
            for (PsiClass psiClass : classes) {
                processClass(psiClass, nodes, edges);
            }
        }

        calculateMetrics(nodes, edges);
        return new ExtractionResult(new ArrayList<>(nodes.values()), edges);
    }

    private void processClass(PsiClass psiClass,
                              Map<String, ClassNode> nodes,
                              List<DependencyEdge> edges) {

        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return;

        // Determina o tipo do nó
        NodeType nodeType;
        if (psiClass.isInterface()) {
            nodeType = NodeType.INTERFACE;
        } else if (psiClass.isEnum()) {
            nodeType = NodeType.ENUM;
        } else if (psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
            nodeType = NodeType.ABSTRACT_CLASS;
        } else {
            nodeType = NodeType.CLASS;
        }

        String filePath = Optional.ofNullable(psiClass.getContainingFile())
                .map(f -> f.getVirtualFile())
                .map(v -> v.getPath())
                .orElse("");

        nodes.put(qualifiedName, new ClassNode(
                qualifiedName,
                psiClass.getName() != null ? psiClass.getName() : qualifiedName,
                nodeType,
                filePath
        ));

        // 1. Herança
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && superClass.getQualifiedName() != null
                && !superClass.getQualifiedName().equals("java.lang.Object")) {
            edges.add(new DependencyEdge(qualifiedName, superClass.getQualifiedName(), EdgeType.EXTENDS));
        }

        // 2. Interfaces implementadas
        for (PsiClass iface : psiClass.getInterfaces()) {
            if (iface.getQualifiedName() != null) {
                edges.add(new DependencyEdge(qualifiedName, iface.getQualifiedName(), EdgeType.IMPLEMENTS));
            }
        }

        // 3. Campos (dependência de uso)
        for (PsiField field : psiClass.getFields()) {
            if (field.getType() instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) field.getType()).resolve();
                if (fieldClass != null && fieldClass.getQualifiedName() != null) {
                    edges.add(new DependencyEdge(qualifiedName, fieldClass.getQualifiedName(), EdgeType.USES));
                }
            }
        }

        // 4. Instanciações dentro de métodos (new Foo())
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getBody() == null) continue;
            method.getBody().accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitNewExpression(PsiNewExpression expression) {
                    super.visitNewExpression(expression);
                    if (expression.getClassOrAnonymousClassReference() == null) return;
                    PsiElement resolved = expression.getClassOrAnonymousClassReference().resolve();
                    if (resolved instanceof PsiClass) {
                        String targetName = ((PsiClass) resolved).getQualifiedName();
                        if (targetName != null) {
                            edges.add(new DependencyEdge(qualifiedName, targetName, EdgeType.INSTANTIATES));
                        }
                    }
                }
            });
        }
    }

    private void calculateMetrics(Map<String, ClassNode> nodes, List<DependencyEdge> edges) {
        // fan-out: dependências que SAEM desta classe
        Map<String, Long> fanOutMap = edges.stream()
                .collect(Collectors.groupingBy(
                        DependencyEdge::getSource,
                        Collectors.collectingAndThen(
                                Collectors.mapping(DependencyEdge::getTarget, Collectors.toSet()),
                                Set::size
                        )
                ));

        // fan-in: classes que APONTAM para esta
        Map<String, Long> fanInMap = edges.stream()
                .collect(Collectors.groupingBy(
                        DependencyEdge::getTarget,
                        Collectors.collectingAndThen(
                                Collectors.mapping(DependencyEdge::getSource, Collectors.toSet()),
                                Set::size
                        )
                ));

        nodes.forEach((name, node) -> {
            node.setFanOut(fanOutMap.getOrDefault(name, 0L).intValue());
            node.setFanIn(fanInMap.getOrDefault(name, 0L).intValue());
        });
    }

    // Classe simples para retornar os dois resultados juntos
    public static class ExtractionResult {
        public final List<ClassNode>      nodes;
        public final List<DependencyEdge> edges;

        public ExtractionResult(List<ClassNode> nodes, List<DependencyEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }
}