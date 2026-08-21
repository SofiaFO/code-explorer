package com.sofia.codeexplorer.headless;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Extrator standalone (sem IntelliJ/PSI) que reproduz o mesmo JSON de
// hierarquia + edges + cycles que o plugin gera via HierarchyJsonBuilder,
// usado pelo pipeline headless (Bitbucket) pra renderizar o circle packing
// fora do IDE. Roda com JavaParser + symbol solver sobre um diretório de
// fontes Java.
public class ExtractorCli {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: java -jar extractor-cli.jar <caminho/src/main/java> <output/graph.json>");
            System.exit(1);
        }

        Path srcDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outputPath = Paths.get(args[1]).toAbsolutePath().normalize();

        if (!Files.isDirectory(srcDir)) {
            System.err.println("Diretório de fontes não encontrado: " + srcDir);
            System.exit(1);
        }

        try {
            Files.createDirectories(outputPath.getParent());

            ClassRelationExtractor.ExtractionResult result = new ClassRelationExtractor(srcDir).extract();
            List<List<String>> cycles = CycleDetector.detect(result.edges);
            String rootName = deriveProjectName(srcDir);

            GraphRoot graphRoot = HierarchyJsonBuilder.build(result, cycles, rootName);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                gson.toJson(graphRoot, writer);
            }

            System.out.println("OK: " + result.nodes.size() + " classes, " + result.edges.size()
                + " relações, " + cycles.size() + " ciclos -> " + outputPath);
        } catch (IOException e) {
            System.err.println("Falha ao extrair/serializar: " + e.getMessage());
            System.exit(1);
        }
    }

    // Se o diretório for .../<projeto>/src/main/java, usa <projeto> como nome
    // da raiz do grafo; caso contrário cai pro nome do próprio diretório.
    private static String deriveProjectName(Path srcDir) {
        Path javaDir = srcDir;
        if (javaDir.getFileName() != null && javaDir.getFileName().toString().equals("java")
                && javaDir.getParent() != null && javaDir.getParent().getFileName() != null
                && javaDir.getParent().getFileName().toString().equals("main")
                && javaDir.getParent().getParent() != null && javaDir.getParent().getParent().getFileName() != null
                && javaDir.getParent().getParent().getFileName().toString().equals("src")
                && javaDir.getParent().getParent().getParent() != null) {
            return javaDir.getParent().getParent().getParent().getFileName().toString();
        }
        return srcDir.getFileName() != null ? srcDir.getFileName().toString() : "projeto";
    }

    // ------------------------------------------------------------------
    // Modelo
    // ------------------------------------------------------------------

    public enum NodeType { CLASS, INTERFACE, ABSTRACT_CLASS, ENUM }

    public enum EdgeType { EXTENDS, IMPLEMENTS, USES, INSTANTIATES, CALLS }

    public static class ClassNode {
        final String qualifiedName;
        final String simpleName;
        final String packageName;
        final NodeType type;
        int loc = 1;
        int fanIn = 0;
        int fanOut = 0;
        int cbo = 0;

        ClassNode(String qualifiedName, String simpleName, String packageName, NodeType type) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.type = type;
        }
    }

    public static class DependencyEdge {
        final String source;
        final String target;
        final EdgeType type;

        DependencyEdge(String source, String target, EdgeType type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }
    }

    // ------------------------------------------------------------------
    // Extração (JavaParser + symbol solver)
    // ------------------------------------------------------------------

    static class ClassRelationExtractor {

        private final Path srcDir;

        ClassRelationExtractor(Path srcDir) {
            this.srcDir = srcDir;
        }

        ExtractionResult extract() throws IOException {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver();
            typeSolver.add(new ReflectionTypeSolver());
            typeSolver.add(new JavaParserTypeSolver(srcDir));
            StaticJavaParser.getConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

            Map<String, ClassNode> nodes = new LinkedHashMap<>();
            List<DependencyEdge> edges = new ArrayList<>();

            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(srcDir)) {
                javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
            }

            for (Path file : javaFiles) {
                CompilationUnit cu;
                try {
                    cu = StaticJavaParser.parse(file);
                } catch (Exception e) {
                    System.err.println("Aviso: falha ao parsear " + file + " (" + e.getMessage() + ")");
                    continue;
                }
                String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");

                for (TypeDeclaration<?> type : cu.getTypes()) {
                    processType(type, packageName, nodes, edges);
                }
            }

            calculateMetrics(nodes, edges);
            return new ExtractionResult(new ArrayList<>(nodes.values()), edges);
        }

        private void processType(TypeDeclaration<?> type, String packageName,
                                  Map<String, ClassNode> nodes, List<DependencyEdge> edges) {
            String simpleName = type.getNameAsString();
            String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

            NodeType nodeType;
            if (type instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) type;
                if (decl.isInterface()) {
                    nodeType = NodeType.INTERFACE;
                } else if (decl.hasModifier(Modifier.Keyword.ABSTRACT)) {
                    nodeType = NodeType.ABSTRACT_CLASS;
                } else {
                    nodeType = NodeType.CLASS;
                }
            } else if (type instanceof EnumDeclaration) {
                nodeType = NodeType.ENUM;
            } else {
                // records, anotações etc. — fora do escopo desta extração best-effort
                return;
            }

            ClassNode node = new ClassNode(qualifiedName, simpleName, packageName, nodeType);
            node.loc = type.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(1);
            nodes.put(qualifiedName, node);

            if (type instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) type;

                for (ClassOrInterfaceType extended : decl.getExtendedTypes()) {
                    resolveQualifiedName(extended)
                        .filter(qn -> !qn.equals("java.lang.Object"))
                        .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.EXTENDS)));
                }
                for (ClassOrInterfaceType implemented : decl.getImplementedTypes()) {
                    resolveQualifiedName(implemented)
                        .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.IMPLEMENTS)));
                }
            }

            // Campos
            for (FieldDeclaration field : type.getFields()) {
                resolveQualifiedName(field.getElementType())
                    .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.USES)));
            }

            // Métodos: parâmetros, retorno, instanciações e chamadas
            for (MethodDeclaration method : type.getMethods()) {
                for (Parameter param : method.getParameters()) {
                    resolveQualifiedName(param.getType())
                        .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.USES)));
                }
                if (!method.getType().isVoidType()) {
                    resolveQualifiedName(method.getType())
                        .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.USES)));
                }

                method.getBody().ifPresent(body -> {
                    for (ObjectCreationExpr expr : body.findAll(ObjectCreationExpr.class)) {
                        resolveQualifiedName(expr.getType())
                            .ifPresent(qn -> edges.add(new DependencyEdge(qualifiedName, qn, EdgeType.INSTANTIATES)));
                    }
                    // CALLS: melhor esforço — muitas chamadas não resolvem sem o
                    // classpath completo do projeto alvo (deps externas), então
                    // falhas de resolução são simplesmente ignoradas.
                    for (MethodCallExpr expr : body.findAll(MethodCallExpr.class)) {
                        try {
                            ResolvedMethodDeclaration resolved = expr.resolve();
                            String targetQn = resolved.declaringType().getQualifiedName();
                            if (!targetQn.equals(qualifiedName)) {
                                edges.add(new DependencyEdge(qualifiedName, targetQn, EdgeType.CALLS));
                            }
                        } catch (Exception ignored) {
                            // símbolo não resolvido — ignora, é best-effort
                        }
                    }
                });
            }
        }

        private java.util.Optional<String> resolveQualifiedName(Type type) {
            try {
                var resolved = type.resolve();
                if (resolved.isReferenceType()) {
                    return java.util.Optional.of(resolved.asReferenceType().getQualifiedName());
                }
            } catch (Exception ignored) {
                // símbolo não resolvido (dependência externa fora do classpath) — best-effort
            }
            return java.util.Optional.empty();
        }

        private void calculateMetrics(Map<String, ClassNode> nodes, List<DependencyEdge> edges) {
            Map<String, Integer> cbo = CouplingAnalyzer.computeCbo(edges);

            Map<String, Long> fanOutMap = edges.stream()
                .collect(Collectors.groupingBy(e -> e.source,
                    Collectors.collectingAndThen(
                        Collectors.mapping(e -> e.target, Collectors.toSet()),
                        set -> (long) set.size())));

            Map<String, Long> fanInMap = edges.stream()
                .collect(Collectors.groupingBy(e -> e.target,
                    Collectors.collectingAndThen(
                        Collectors.mapping(e -> e.source, Collectors.toSet()),
                        set -> (long) set.size())));

            nodes.forEach((name, node) -> {
                node.fanOut = fanOutMap.getOrDefault(name, 0L).intValue();
                node.fanIn = fanInMap.getOrDefault(name, 0L).intValue();
                node.cbo = cbo.getOrDefault(name, 0);
            });
        }

        static class ExtractionResult {
            final List<ClassNode> nodes;
            final List<DependencyEdge> edges;

            ExtractionResult(List<ClassNode> nodes, List<DependencyEdge> edges) {
                this.nodes = nodes;
                this.edges = edges;
            }
        }
    }

    // ------------------------------------------------------------------
    // CBO (mesmo critério do plugin: nº de classes distintas acopladas,
    // em qualquer direção, sem contar a mesma classe duas vezes)
    // ------------------------------------------------------------------

    static class CouplingAnalyzer {
        static Map<String, Integer> computeCbo(List<DependencyEdge> edges) {
            Map<String, Set<String>> coupled = new HashMap<>();
            for (DependencyEdge e : edges) {
                coupled.computeIfAbsent(e.source, k -> new HashSet<>()).add(e.target);
                coupled.computeIfAbsent(e.target, k -> new HashSet<>()).add(e.source);
            }
            Map<String, Integer> result = new HashMap<>();
            coupled.forEach((k, v) -> result.put(k, v.size()));
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Detecção de ciclos (mesmo algoritmo do plugin — DFS com pilha)
    // ------------------------------------------------------------------

    static class CycleDetector {
        static List<List<String>> detect(List<DependencyEdge> edges) {
            Map<String, Set<String>> adj = new HashMap<>();
            for (DependencyEdge edge : edges) {
                adj.computeIfAbsent(edge.source, k -> new HashSet<>()).add(edge.target);
            }

            List<List<String>> cycles = new ArrayList<>();
            Set<List<String>> seen = new HashSet<>();
            Set<String> visited = new HashSet<>();
            Set<String> inStack = new HashSet<>();
            List<String> path = new ArrayList<>();

            for (String node : adj.keySet()) {
                if (!visited.contains(node)) {
                    dfs(node, adj, visited, inStack, path, cycles, seen);
                }
            }
            return cycles;
        }

        private static void dfs(String node, Map<String, Set<String>> adj, Set<String> visited,
                                 Set<String> inStack, List<String> path,
                                 List<List<String>> cycles, Set<List<String>> seen) {
            visited.add(node);
            inStack.add(node);
            path.add(node);

            for (String neighbor : adj.getOrDefault(node, java.util.Collections.emptySet())) {
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

    // ------------------------------------------------------------------
    // Serialização — mesmo formato JSON do HierarchyJsonBuilder do plugin,
    // via Gson (POJOs abaixo) em vez de StringBuilder manual.
    // ------------------------------------------------------------------

    public static class GraphRoot {
        String name;
        int maxFanIn;
        int maxFanOut;
        int maxLoc;
        List<JsonNode> children;
        List<JsonEdge> edges;
        List<List<String>> cycles;
    }

    public static class JsonNode {
        String name;
        String qualifiedName;
        String type;
        Integer value;
        Integer loc;
        Integer fanIn;
        Integer fanOut;
        Integer cbo;
        List<JsonNode> children;
    }

    public static class JsonEdge {
        final String source;
        final String target;
        final String type;

        JsonEdge(String source, String target, String type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }
    }

    static class HierarchyJsonBuilder {

        private HierarchyJsonBuilder() {}

        static GraphRoot build(ClassRelationExtractor.ExtractionResult result,
                                List<List<String>> cycles, String rootName) {
            PackageNode root = new PackageNode(rootName);
            for (ClassNode node : result.nodes) {
                PackageNode parent = root;
                if (!node.packageName.isEmpty()) {
                    for (String segment : node.packageName.split("\\.")) {
                        parent = parent.children.computeIfAbsent(segment, PackageNode::new);
                    }
                }
                parent.classes.add(node);
            }
            collapseChainsInPlace(root);

            GraphRoot graphRoot = new GraphRoot();
            graphRoot.name = rootName;
            graphRoot.maxFanIn = result.nodes.stream().mapToInt(n -> n.fanIn).max().orElse(1);
            graphRoot.maxFanOut = result.nodes.stream().mapToInt(n -> n.fanOut).max().orElse(1);
            graphRoot.maxLoc = result.nodes.stream().mapToInt(n -> n.loc).max().orElse(1);
            graphRoot.children = buildChildren(root);
            graphRoot.edges = result.edges.stream()
                .map(e -> new JsonEdge(e.source, e.target, e.type.name()))
                .collect(Collectors.toList());
            graphRoot.cycles = cycles;
            return graphRoot;
        }

        private static void collapseChainsInPlace(PackageNode node) {
            Map<String, PackageNode> collapsedChildren = new LinkedHashMap<>();
            for (PackageNode child : node.children.values()) {
                PackageNode collapsed = collapseChain(child);
                collapsedChildren.put(collapsed.name, collapsed);
            }
            node.children.clear();
            node.children.putAll(collapsedChildren);
        }

        private static PackageNode collapseChain(PackageNode node) {
            collapseChainsInPlace(node);
            while (node.classes.isEmpty() && node.children.size() == 1) {
                PackageNode onlyChild = node.children.values().iterator().next();
                PackageNode merged = new PackageNode(node.name + "." + onlyChild.name);
                merged.children.putAll(onlyChild.children);
                merged.classes.addAll(onlyChild.classes);
                node = merged;
            }
            return node;
        }

        private static List<JsonNode> buildChildren(PackageNode node) {
            List<PackageNode> pkgChildren = new ArrayList<>(node.children.values());
            pkgChildren.sort(Comparator.comparing(p -> p.name));
            List<ClassNode> classChildren = new ArrayList<>(node.classes);
            classChildren.sort(Comparator.comparing(c -> c.simpleName));

            List<JsonNode> result = new ArrayList<>();
            for (PackageNode child : pkgChildren) {
                JsonNode jn = new JsonNode();
                jn.name = child.name;
                jn.children = buildChildren(child);
                result.add(jn);
            }
            for (ClassNode n : classChildren) {
                JsonNode jn = new JsonNode();
                jn.name = n.simpleName;
                jn.qualifiedName = n.qualifiedName;
                jn.type = n.type.name();
                jn.value = Math.max(n.loc, 1);
                jn.loc = n.loc;
                jn.fanIn = n.fanIn;
                jn.fanOut = n.fanOut;
                jn.cbo = n.cbo;
                result.add(jn);
            }
            return result;
        }

        private static class PackageNode {
            final String name;
            final Map<String, PackageNode> children = new LinkedHashMap<>();
            final List<ClassNode> classes = new ArrayList<>();

            PackageNode(String name) { this.name = name; }
        }
    }
}
