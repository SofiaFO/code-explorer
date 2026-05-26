package com.sofia.codeexplorer.model;

public class ClassNode {

    private final String qualifiedName;
    private final String simpleName;
    private final NodeType type;
    private final String filePath;
    private int fanIn  = 0;
    private int fanOut = 0;

    public ClassNode(String qualifiedName, String simpleName, NodeType type, String filePath) {
        this.qualifiedName = qualifiedName;
        this.simpleName    = simpleName;
        this.type          = type;
        this.filePath      = filePath;
    }

    // getters e setters
    public String getQualifiedName() { return qualifiedName; }
    public String getSimpleName()    { return simpleName; }
    public NodeType getType()        { return type; }
    public String getFilePath()      { return filePath; }
    public int getFanIn()            { return fanIn; }
    public int getFanOut()           { return fanOut; }
    public void setFanIn(int v)      { this.fanIn  = v; }
    public void setFanOut(int v)     { this.fanOut = v; }
}
