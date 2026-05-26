package com.sofia.codeexplorer.model;

public class DependencyEdge {

    private final String source;
    private final String target;
    private final EdgeType type;

    public DependencyEdge(String source, String target, EdgeType type) {
        this.source = source;
        this.target = target;
        this.type   = type;
    }

    public String getSource()  { return source; }
    public String getTarget()  { return target; }
    public EdgeType getType()  { return type; }
}