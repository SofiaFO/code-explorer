package com.sofia.codeexplorer.ui;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.sofia.codeexplorer.analyzer.AnalysisCacheService;
import com.sofia.codeexplorer.analyzer.ClassRelationExtractor;
import com.sofia.codeexplorer.analyzer.CouplingAnalyzer;
import com.sofia.codeexplorer.analyzer.CycleDetector;
import com.sofia.codeexplorer.model.ClassNode;
import com.sofia.codeexplorer.model.DependencyEdge;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CodeExplorerToolWindowFactory implements ToolWindowFactory {

    // One instance per process is acceptable for a single-project dev tool
    private static JTable metricsTable;
    private static ClassTableModel tableModel;
    private static DefaultListModel<String> cyclesModel;
    private static DefaultListModel<String> edgesModel;
    private static JLabel statusLabel;
    private static JTextField packageFilter;
    private static JComboBox<String> typeFilter;
    private static JComboBox<String> severityFilter;
    private static JCheckBox useCacheBox;
    private static Project currentProject;
    private static ClassRelationExtractor.ExtractionResult lastResult;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        currentProject = project;

        // ── Models ──────────────────────────────────────────────────────────
        tableModel  = new ClassTableModel(Collections.emptyList());
        cyclesModel = new DefaultListModel<>();
        edgesModel  = new DefaultListModel<>();

        // ── Metrics table ────────────────────────────────────────────────────
        metricsTable = new JTable(tableModel);
        metricsTable.setAutoCreateRowSorter(true);
        metricsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        metricsTable.getTableHeader().setReorderingAllowed(false);
        metricsTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) navigateToSelected();
            }
        });
        // Give fixed-width columns to numeric ones
        int[] widths = {180, 160, 110, 60, 65, 50, 45, 45, 45, 80};
        for (int i = 0; i < widths.length; i++) {
            metricsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // ── Filters ──────────────────────────────────────────────────────────
        packageFilter  = new JTextField(14);
        typeFilter     = new JComboBox<>(new String[]{"Todos", "CLASS", "INTERFACE", "ABSTRACT_CLASS", "ENUM"});
        severityFilter = new JComboBox<>(new String[]{"Todos", "OK", "MODERADO", "ALTO", "CRITICO"});

        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilters(); }
            public void removeUpdate(DocumentEvent e)  { applyFilters(); }
            public void changedUpdate(DocumentEvent e) { applyFilters(); }
        };
        packageFilter.getDocument().addDocumentListener(dl);
        typeFilter.addActionListener(e -> applyFilters());
        severityFilter.addActionListener(e -> applyFilters());

        JPanel filtersPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filtersPanel.add(new JLabel("Pacote:"));
        filtersPanel.add(packageFilter);
        filtersPanel.add(new JLabel("Tipo:"));
        filtersPanel.add(typeFilter);
        filtersPanel.add(new JLabel("Severidade:"));
        filtersPanel.add(severityFilter);

        // ── Tabs ─────────────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Métricas",  new JBScrollPane(metricsTable));
        tabs.addTab("Ciclos",    new JBScrollPane(new JList<>(cyclesModel)));
        tabs.addTab("Relações",  new JBScrollPane(new JList<>(edgesModel)));

        JPanel center = new JPanel(new BorderLayout(2, 2));
        center.add(filtersPanel, BorderLayout.NORTH);
        center.add(tabs, BorderLayout.CENTER);

        // ── Toolbar ──────────────────────────────────────────────────────────
        statusLabel = new JLabel("Pronto");
        useCacheBox = new JCheckBox("Cache", true);
        useCacheBox.setToolTipText("Reusar resultado se nenhum arquivo mudou");

        JButton analyzeBtn = new JButton("Analisar");
        analyzeBtn.addActionListener(e -> runAnalysis(project, useCacheBox.isSelected()));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(analyzeBtn);
        toolbar.add(useCacheBox);
        toolbar.add(statusLabel);

        // ── Root ─────────────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(toolbar, BorderLayout.NORTH);
        root.add(center,  BorderLayout.CENTER);

        var content = toolWindow.getContentManager()
                .getFactory().createContent(root, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    // Called from AnalyzeProjectAction
    public static void triggerAnalysis(Project project) {
        currentProject = project;
        runAnalysis(project, useCacheBox != null && useCacheBox.isSelected());
    }

    private static void runAnalysis(Project project, boolean useCache) {
        if (statusLabel == null) return;
        statusLabel.setText("Analisando...");

        AnalysisCacheService cache = project.getService(AnalysisCacheService.class);

        if (useCache && cache.isCacheValid(project)) {
            lastResult = cache.getCached();
            List<List<String>> cycles = CycleDetector.detect(lastResult.edges);
            applyFilters();
            populateCycles(cycles);
            populateEdges(lastResult.edges);
            statusLabel.setText("(cache) " + summary(lastResult, cycles));
            return;
        }

        ReadAction.nonBlocking(() -> new ClassRelationExtractor(project).extract())
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                if (result.nodes.isEmpty()) {
                    statusLabel.setText("0 classes — verifique se o projeto tem fontes Java/Kotlin indexadas");
                    return;
                }
                List<List<String>> cycles = CycleDetector.detect(result.edges);
                cache.store(project, result);
                lastResult = result;
                applyFilters();
                populateCycles(cycles);
                populateEdges(result.edges);
                statusLabel.setText(summary(result, cycles));
            })
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError(ex -> SwingUtilities.invokeLater(() ->
                statusLabel.setText("Erro: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()))));
    }

    private static void applyFilters() {
        if (lastResult == null || tableModel == null) return;

        String pkg = packageFilter  != null ? packageFilter.getText().trim().toLowerCase() : "";
        String typ = typeFilter     != null ? (String) typeFilter.getSelectedItem()     : "Todos";
        String sev = severityFilter != null ? (String) severityFilter.getSelectedItem() : "Todos";

        List<ClassNode> filtered = lastResult.nodes.stream()
            .filter(n -> pkg.isEmpty() || n.getPackageName().toLowerCase().contains(pkg))
            .filter(n -> "Todos".equals(typ) || n.getType().name().equals(typ))
            .filter(n -> "Todos".equals(sev) || CouplingAnalyzer.classify(n).name().equals(sev))
            .collect(Collectors.toList());

        tableModel.setData(filtered);
    }

    private static void populateCycles(List<List<String>> cycles) {
        if (cyclesModel == null) return;
        cyclesModel.clear();
        if (cycles.isEmpty()) {
            cyclesModel.addElement("Nenhum ciclo detectado.");
            return;
        }
        for (int i = 0; i < cycles.size(); i++) {
            List<String> c = cycles.get(i);
            StringBuilder sb = new StringBuilder(String.format("[%d] ", i + 1));
            for (int j = 0; j < c.size(); j++) {
                sb.append(simpleName(c.get(j)));
                if (j < c.size() - 1) sb.append(" → ");
            }
            sb.append(" → ").append(simpleName(c.get(0)));
            cyclesModel.addElement(sb.toString());
        }
    }

    private static void populateEdges(List<DependencyEdge> edges) {
        if (edgesModel == null) return;
        edgesModel.clear();
        edges.forEach(e -> edgesModel.addElement(String.format("%-30s --[%-12s]--> %s",
            simpleName(e.getSource()), e.getType(), simpleName(e.getTarget()))));
    }

    private static void navigateToSelected() {
        if (metricsTable == null || lastResult == null || currentProject == null) return;
        int viewRow = metricsTable.getSelectedRow();
        if (viewRow < 0) return;
        ClassNode node = tableModel.getRow(metricsTable.convertRowIndexToModel(viewRow));
        if (node == null || node.getFilePath().isEmpty()) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(node.getFilePath());
        if (vf != null) {
            SwingUtilities.invokeLater(() -> new OpenFileDescriptor(currentProject, vf).navigate(true));
        }
    }


    private static String summary(ClassRelationExtractor.ExtractionResult r, List<List<String>> cycles) {
        return r.nodes.size() + " classes | " + r.edges.size() + " relações | " + cycles.size() + " ciclos";
    }

    private static String simpleName(String q) {
        int i = q.lastIndexOf('.');
        return i >= 0 ? q.substring(i + 1) : q;
    }

    // ── Table model ────────────────────────────────────────────────────────────

    private static final String[] COLUMNS = {
        "Classe", "Pacote", "Tipo", "Fan-In", "Fan-Out", "I", "DIT", "NOC", "Impl", "Severidade"
    };

    static class ClassTableModel extends AbstractTableModel {
        private List<ClassNode> data;

        ClassTableModel(List<ClassNode> data) { this.data = new ArrayList<>(data); }

        void setData(List<ClassNode> d) { this.data = new ArrayList<>(d); fireTableDataChanged(); }

        ClassNode getRow(int row) { return row >= 0 && row < data.size() ? data.get(row) : null; }

        @Override public int getRowCount()    { return data.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case 3, 4, 6, 7, 8 -> Integer.class;
                case 5              -> Double.class;
                default             -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int col) {
            ClassNode n = data.get(row);
            return switch (col) {
                case 0 -> n.getSimpleName();
                case 1 -> n.getPackageName();
                case 2 -> n.getType().name();
                case 3 -> n.getFanIn();
                case 4 -> n.getFanOut();
                case 5 -> Math.round(n.getInstability() * 100.0) / 100.0;
                case 6 -> n.getDit();
                case 7 -> n.getNoc();
                case 8 -> n.getImplementationsCount();
                case 9 -> CouplingAnalyzer.classify(n).name();
                default -> "";
            };
        }
    }
}
