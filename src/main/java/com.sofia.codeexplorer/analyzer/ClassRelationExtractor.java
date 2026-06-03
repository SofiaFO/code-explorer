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

        String packageName = "";
        if (psiClass.getContainingFile() instanceof PsiJavaFile) {
            packageName = ((PsiJavaFile) psiClass.getContainingFile()).getPackageName();
        }

        nodes.put(qualifiedName, new ClassNode(
                qualifiedName,
                psiClass.getName() != null ? psiClass.getName() : qualifiedName,
                packageName,
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

        // 4. Métodos: parâmetros, retorno, instanciações e chamadas
        for (PsiMethod method : psiClass.getMethods()) {

            // 4a. Parâmetros
            for (PsiParameter param : method.getParameterList().getParameters()) {
                if (param.getType() instanceof PsiClassType) {
                    PsiClass paramClass = ((PsiClassType) param.getType()).resolve();
                    if (paramClass != null && paramClass.getQualifiedName() != null) {
                        edges.add(new DependencyEdge(qualifiedName, paramClass.getQualifiedName(), EdgeType.USES));
                    }
                }
            }

            // 4b. Tipo de retorno
            PsiType returnType = method.getReturnType();
            if (returnType instanceof PsiClassType) {
                PsiClass returnClass = ((PsiClassType) returnType).resolve();
                if (returnClass != null && returnClass.getQualifiedName() != null) {
                    edges.add(new DependencyEdge(qualifiedName, returnClass.getQualifiedName(), EdgeType.USES));
                }
            }

            // 4c. Corpo: instanciações (new Foo()) e chamadas (obj.metodo())
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

                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                    if (qualifier == null) return;
                    PsiType type = qualifier.getType();
                    if (type instanceof PsiClassType) {
                        PsiClass calledClass = ((PsiClassType) type).resolve();
                        if (calledClass != null && calledClass.getQualifiedName() != null) {
                            edges.add(new DependencyEdge(qualifiedName, calledClass.getQualifiedName(), EdgeType.CALLS));
                        }
                    }
                }
            });
        }
    }

    private void calculateMetrics(Map<String, ClassNode> nodes, List<DependencyEdge> edges) {
        Map<String, Long> fanOutMap = edges.stream()
                .collect(Collectors.groupingBy(
                        DependencyEdge::getSource,
                        Collectors.collectingAndThen(
                                Collectors.mapping(DependencyEdge::getTarget, Collectors.toSet()),
                                set -> (long) set.size()
                        )
                ));

        Map<String, Long> fanInMap = edges.stream()
                .collect(Collectors.groupingBy(
                        DependencyEdge::getTarget,
                        Collectors.collectingAndThen(
                                Collectors.mapping(DependencyEdge::getSource, Collectors.toSet()),
                                set -> (long) set.size()
                        )
                ));

        // NOC: filhos diretos por herança
        Map<String, Long> nocMap = edges.stream()
                .filter(e -> e.getType() == EdgeType.EXTENDS)
                .collect(Collectors.groupingBy(DependencyEdge::getTarget, Collectors.counting()));

        // Implementações de interface
        Map<String, Long> implMap = edges.stream()
                .filter(e -> e.getType() == EdgeType.IMPLEMENTS)
                .collect(Collectors.groupingBy(DependencyEdge::getTarget, Collectors.counting()));

        // Mapa pai→filho para calcular DIT
        Map<String, String> parentMap = new HashMap<>();
        for (DependencyEdge edge : edges) {
            if (edge.getType() == EdgeType.EXTENDS) {
                parentMap.put(edge.getSource(), edge.getTarget());
            }
        }

        nodes.forEach((name, node) -> {
            int fanOut = fanOutMap.getOrDefault(name, 0L).intValue();
            int fanIn  = fanInMap.getOrDefault(name, 0L).intValue();
            node.setFanOut(fanOut);
            node.setFanIn(fanIn);
            node.setNoc(nocMap.getOrDefault(name, 0L).intValue());
            node.setImplementationsCount(implMap.getOrDefault(name, 0L).intValue());

            double instability = (fanIn + fanOut) == 0 ? 0.0 : (double) fanOut / (fanIn + fanOut);
            node.setInstability(instability);

            // DIT: conta saltos até a raiz da hierarquia de herança
            int dit = 0;
            String current = name;
            Set<String> seen = new HashSet<>();
            while (parentMap.containsKey(current) && seen.add(current)) {
                current = parentMap.get(current);
                dit++;
            }
            node.setDit(dit);
        });
    }

    public static class ExtractionResult {
        public final List<ClassNode>      nodes;
        public final List<DependencyEdge> edges;

        public ExtractionResult(List<ClassNode> nodes, List<DependencyEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }
}
